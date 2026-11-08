package io.haenggan.diary;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.json.*;

/** Fail-closed OpenID Connect verification. Unsupported signing algorithms require an app update. */
final class Oidc {
    static final String ISSUER = "https://auth.openai.com";
    static JSONObject verify(String token, JSONObject jwks, String clientId, String nonce, String subject, long now) throws Exception {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) throw new SecurityException("계정 확인에 실패했어요.");
        JSONObject header = new JSONObject(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        if (!"RS256".equals(header.optString("alg")) || header.has("crit")) throw new SecurityException("지원되지 않는 계정 서명이에요.");
        String kid = header.getString("kid"); JSONObject jwk = null;
        JSONArray keys = jwks.getJSONArray("keys");
        for (int i = 0; i < keys.length(); i++) {
            JSONObject k = keys.getJSONObject(i);
            if (kid.equals(k.optString("kid")) && "RSA".equals(k.optString("kty")) && "sig".equals(k.optString("use", "sig")) && "RS256".equals(k.optString("alg", "RS256"))) { jwk = k; break; }
        }
        if (jwk == null) throw new SecurityException("계정 서명을 확인할 수 없어요.");
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
            new BigInteger(1, Base64.getUrlDecoder().decode(jwk.getString("n"))),
            new BigInteger(1, Base64.getUrlDecoder().decode(jwk.getString("e")))));
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initVerify(key);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) throw new SecurityException("계정 서명이 일치하지 않아요.");
        JSONObject claims = new JSONObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        Object aud = claims.opt("aud"); boolean matches = clientId.equals(aud);
        if (aud instanceof JSONArray a) {
            for (int i=0;i<a.length();i++) matches |= clientId.equals(a.optString(i));
            if (a.length()>1 && !clientId.equals(claims.optString("azp"))) matches=false;
        }
        if (!ISSUER.equals(claims.optString("iss")) || !matches || claims.optLong("exp",0)<=now || claims.optLong("iat",now)>now+60 || claims.optLong("nbf",0)>now+60 || claims.optString("sub").isEmpty()) throw new SecurityException("계정 인증이 유효하지 않아요.");
        if (nonce != null && !constantTime(nonce, claims.optString("nonce"))) throw new SecurityException("로그인 요청이 일치하지 않아요.");
        if (subject != null && !subject.equals(claims.optString("sub"))) throw new SecurityException("다른 계정으로 바뀌어 연결을 중단했어요.");
        return claims;
    }
    static boolean constantTime(String a, String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
}
