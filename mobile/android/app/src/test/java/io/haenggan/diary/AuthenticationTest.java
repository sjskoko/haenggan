package io.haenggan.diary;

import org.junit.*;
import static org.junit.Assert.*;
import org.json.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.*;
import java.io.*;

public class AuthenticationTest {
    private KeyPair pair;private JSONObject jwks;private final long now=1800000000L;
    private static String b64(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static byte[] unsigned(BigInteger n){byte[] b=n.toByteArray();return b[0]==0?Arrays.copyOfRange(b,1,b.length):b;}
    @Before public void setup() throws Exception {
        KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);pair=gen.generateKeyPair();RSAPublicKey key=(RSAPublicKey)pair.getPublic();
        jwks=new JSONObject().put("keys",new JSONArray().put(new JSONObject().put("kid","test").put("kty","RSA").put("use","sig").put("n",b64(unsigned(key.getModulus()))).put("e",b64(unsigned(key.getPublicExponent())))));
    }
    private JSONObject claims() throws Exception{return new JSONObject().put("iss",Oidc.ISSUER).put("aud","client-a").put("sub","user-a").put("exp",now+60).put("iat",now-20).put("nonce","nonce-a");}
    private String token(JSONObject claims) throws Exception {
        String input=b64("{\"alg\":\"RS256\",\"kid\":\"test\"}".getBytes(StandardCharsets.UTF_8))+"."+b64(claims.toString().getBytes(StandardCharsets.UTF_8));Signature sign=Signature.getInstance("SHA256withRSA");sign.initSign(pair.getPrivate());sign.update(input.getBytes(StandardCharsets.US_ASCII));return input+"."+b64(sign.sign());
    }
    @Test public void acceptsVerifiedIdentity() throws Exception{assertEquals("user-a",Oidc.verify(token(claims()),jwks,"client-a","nonce-a",null,now).getString("sub"));}
    @Test(expected=SecurityException.class) public void rejectsOtherClient() throws Exception{Oidc.verify(token(claims()),jwks,"client-b","nonce-a",null,now);}
    @Test(expected=SecurityException.class) public void rejectsOtherNonce() throws Exception{Oidc.verify(token(claims()),jwks,"client-a","nonce-b",null,now);}
    @Test(expected=SecurityException.class) public void rejectsChangedAccount() throws Exception{Oidc.verify(token(claims()),jwks,"client-a",null,"user-b",now);}
    @Test(expected=SecurityException.class) public void rejectsExpiredIdentity() throws Exception{Oidc.verify(token(claims().put("exp",now)),jwks,"client-a","nonce-a",null,now);}
    @Test(expected=SecurityException.class) public void rejectsWrongIssuer() throws Exception{Oidc.verify(token(claims().put("iss","https://example.com")),jwks,"client-a","nonce-a",null,now);}
    @Test(expected=SecurityException.class) public void rejectsTamperedSignature() throws Exception{String t=token(claims());String[] p=t.split("\\.");byte[] signature=Base64.getUrlDecoder().decode(p[2]);signature[0]^=1;Oidc.verify(p[0]+"."+p[1]+"."+b64(signature),jwks,"client-a","nonce-a",null,now);}
    @Test(expected=SecurityException.class) public void rejectsUnsignedTokens() throws Exception{Oidc.verify(b64("{\"alg\":\"none\",\"kid\":\"test\"}".getBytes(StandardCharsets.UTF_8))+"."+b64(claims().toString().getBytes(StandardCharsets.UTF_8))+".",jwks,"client-a","nonce-a",null,now);}
    @Test(expected=SecurityException.class) public void rejectsAmbiguousAudience() throws Exception{Oidc.verify(token(claims().put("aud",new JSONArray().put("client-a").put("client-b"))),jwks,"client-a","nonce-a",null,now);}
    @Test public void acceptsExplicitAuthorizedParty() throws Exception{assertEquals("user-a",Oidc.verify(token(claims().put("aud",new JSONArray().put("client-a").put("client-b")).put("azp","client-a")),jwks,"client-a","nonce-a",null,now).getString("sub"));}
    private String stream(String raw) throws Exception{return ResponseStream.read(new BufferedReader(new StringReader(raw)));}
    @Test public void requiresSuccessfulTerminalEvent() throws Exception{assertEquals("오늘",stream("data: {\"type\":\"response.output_text.delta\",\"delta\":\"오늘\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"));}
    @Test(expected=IOException.class) public void rejectsTruncatedResponse() throws Exception{stream("data: {\"type\":\"response.output_text.delta\",\"delta\":\"오늘\"}\n\n");}
    @Test public void preservesUsageLimitFailureAfterText() throws Exception{try{stream("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\ndata: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n");fail();}catch(IOException e){assertTrue(e.getMessage().contains("사용 한도"));}}
    @Test(expected=IOException.class) public void rejectsIncomplete() throws Exception{stream("data: {\"type\":\"response.incomplete\"}\n\n");}
}
