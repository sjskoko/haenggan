package io.haenggan.diary;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One pending OAuth callback. Only loopback requests with the pending state are accepted. */
final class LoopbackCallback implements Closeable {
    private final ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    private volatile Socket current;

    LoopbackCallback() throws IOException {}
    String redirectUri() { return "http://127.0.0.1:" + server.getLocalPort() + "/auth/callback"; }

    Map<String,String> await(String state, long timeoutMillis) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!server.isClosed()) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) throw new SocketTimeoutException("OAuth callback timeout");
            server.setSoTimeout((int)Math.max(1, remaining));
            try (Socket socket = server.accept()) {
                current = socket;
                socket.setSoTimeout((int)Math.max(1, Math.min(2000, remaining)));
                Map<String,String> params;
                try { params = readRequest(socket.getInputStream(), state); }
                catch (SocketTimeoutException e) { continue; } // Browsers can preconnect without a request.
                catch (IOException | IllegalArgumentException e) { replyQuietly(socket, false); continue; }
                if (params == null) { replyQuietly(socket, false); continue; }
                replyQuietly(socket, true);
                return params;
            } finally { current = null; }
        }
        throw new SocketException("OAuth listener closed");
    }

    private Map<String,String> readRequest(InputStream in, String state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int tail = 0;
        while (bytes.size() < 32768) {
            int next = in.read();
            if (next < 0) return null;
            bytes.write(next);
            tail = (tail << 8) | next;
            if (tail == 0x0d0a0d0a) break;
        }
        if (tail != 0x0d0a0d0a) return null;
        String[] lines = bytes.toString(StandardCharsets.US_ASCII).split("\r\n");
        String[] request = lines[0].split(" ");
        if (request.length != 3 || !request[0].equals("GET") || !request[2].equals("HTTP/1.1")) return null;
        String host = null;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) return null;
            String name = lines[i].substring(0, colon).trim();
            String value = lines[i].substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Host")) { if (host != null) return null; host = value; }
            if (name.equalsIgnoreCase("Transfer-Encoding") || (name.equalsIgnoreCase("Content-Length") && !value.equals("0"))) return null;
        }
        if (!("127.0.0.1:" + server.getLocalPort()).equals(host)) return null;
        URI target = URI.create(request[1]);
        if (target.isAbsolute() || target.getRawAuthority() != null || target.getRawFragment() != null || !"/auth/callback".equals(target.getRawPath()) || target.getRawQuery() == null) return null;
        Map<String,String> params = new HashMap<>();
        for (String field : target.getRawQuery().split("&")) {
            String[] parts = field.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            if (params.putIfAbsent(key, value) != null) return null;
        }
        if (!Oidc.constantTime(state, params.getOrDefault("state", ""))) return null;
        if (params.containsKey("error") == params.containsKey("code")) return null;
        return params;
    }

    private static void replyQuietly(Socket socket, boolean accepted) {
        String page = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>행간</title><body style='padding:48px;background:#f7f5ef;color:#263c30;font-family:sans-serif'><h1>행간</h1><p>" + (accepted ? "승인 응답을 받았어요. 행간 앱으로 돌아가 연결 결과를 확인해 주세요." : "현재 로그인 요청이 아니에요. 행간에서 다시 연결해 주세요.") + "</p>";
        byte[] body = page.getBytes(StandardCharsets.UTF_8);
        try {
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 " + (accepted ? "200 OK" : "400 Bad Request") + "\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body); out.flush();
        } catch (IOException ignored) { /* A browser closing its tab must not discard a valid grant. */ }
    }

    @Override public void close() throws IOException {
        server.close();
        Socket socket = current;
        if (socket != null) socket.close();
    }
}
