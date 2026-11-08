package io.haenggan.diary;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class LoopbackCallbackTest {
    private LoopbackCallback listener;
    private ExecutorService executor;
    private URI uri;
    @Before public void setup() throws Exception {listener=new LoopbackCallback();executor=Executors.newSingleThreadExecutor();uri=URI.create(listener.redirectUri());}
    @After public void cleanup() throws Exception {listener.close();executor.shutdownNow();}
    private Future<Map<String,String>> waitForGrant(){return executor.submit(()->listener.await("pending-state",10000));}
    private Socket socket() throws Exception {Socket s=new Socket("127.0.0.1",uri.getPort());s.setSoTimeout(4000);return s;}
    private String request(String query,String host) throws Exception {
        try(Socket s=socket()){
            s.getOutputStream().write(("GET /auth/callback?"+query+" HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            return new String(s.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private String valid() throws Exception{return request("state=pending-state&code=grant&client_id=oaiapp_test",uri.getAuthority());}
    @Test public void receivesActualBrowserCallback() throws Exception {
        Future<Map<String,String>> result=waitForGrant();
        assertTrue(valid().startsWith("HTTP/1.1 200 OK"));
        assertEquals("grant",result.get(3,TimeUnit.SECONDS).get("code"));
    }
    @Test public void waitsForCompleteFragmentedHeaders() throws Exception {
        Future<Map<String,String>> result=waitForGrant();
        try(Socket s=socket()){
            OutputStream out=s.getOutputStream();
            out.write("GET /auth/callback?state=pending-state&code=grant HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));out.flush();
            try{result.get(100,TimeUnit.MILLISECONDS);fail("Must wait for the request headers");}catch(TimeoutException expected){}
            out.write(("Host: "+uri.getAuthority()+"\r\nUser-Agent: test\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
            assertTrue(new String(s.getInputStream().readAllBytes(),StandardCharsets.UTF_8).startsWith("HTTP/1.1 200 OK"));
        }
        assertEquals("grant",result.get(3,TimeUnit.SECONDS).get("code"));
    }
    @Test public void idlePreconnectDoesNotAbortSignIn() throws Exception {
        Future<Map<String,String>> result=waitForGrant();
        try(Socket idle=socket()){
            assertTrue(valid().startsWith("HTTP/1.1 200 OK"));
            assertEquals("grant",result.get(3,TimeUnit.SECONDS).get("code"));
        }
    }
    @Test public void wrongStateDoesNotConsumeAttempt() throws Exception {
        Future<Map<String,String>> result=waitForGrant();
        assertTrue(request("state=other&code=bad",uri.getAuthority()).startsWith("HTTP/1.1 400"));
        assertTrue(valid().startsWith("HTTP/1.1 200"));assertEquals("grant",result.get(3,TimeUnit.SECONDS).get("code"));
    }
    @Test public void wrongHostAndDuplicateParametersAreRejected() throws Exception {
        Future<Map<String,String>> result=waitForGrant();
        assertTrue(request("state=pending-state&code=bad","attacker.invalid").startsWith("HTTP/1.1 400"));
        assertTrue(request("state=other&state=pending-state&code=bad",uri.getAuthority()).startsWith("HTTP/1.1 400"));
        valid();assertEquals("grant",result.get(3,TimeUnit.SECONDS).get("code"));
    }
    @Test public void deniedConsentIsNotAnAuthorizationCode() throws Exception {
        Future<Map<String,String>> result=waitForGrant();
        request("state=pending-state&error=access_denied",uri.getAuthority());
        Map<String,String> value=result.get(3,TimeUnit.SECONDS);
        assertEquals("access_denied",value.get("error"));assertFalse(value.containsKey("code"));
    }
    @Test public void cancellationStopsListener() throws Exception {
        Future<Map<String,String>> result=waitForGrant();listener.close();
        try{result.get(3,TimeUnit.SECONDS);fail("Must stop");}catch(ExecutionException error){assertTrue(error.getCause() instanceof SocketException);}
    }
    @Test public void deadlineStopsWithoutCallback() throws Exception {
        try{listener.await("pending-state",50);fail("Must time out");}catch(SocketTimeoutException expected){}
    }
    @Test public void diagnosticCodesNeverEchoUrlsOrCredentials() {
        assertEquals("invalid_grant",ChatGpt.safeCode("invalid_grant"));
        assertEquals("unknown_error",ChatGpt.safeCode("https://auth.openai.com?code=secret"));
        assertEquals("unknown_error",ChatGpt.safeCode("Bearer secret-token"));
        assertEquals("unknown_error",ChatGpt.safeCode(null));
    }
}
