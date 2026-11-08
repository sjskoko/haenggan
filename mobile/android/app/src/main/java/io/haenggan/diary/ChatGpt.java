package io.haenggan.diary;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.json.*;

/** Official SIWC for a local open-source installation. Tokens never cross the WebView bridge. */
final class ChatGpt {
    static final String RESOURCE="https://api.openai.com/v1";
    private final Vault vault;
    private volatile LoopbackCallback callback;
    private volatile boolean signInCancelled;
    private volatile String cancelReason="계정 연결을 취소했어요.";
    private volatile HttpURLConnection tokenConnection;
    private volatile String retryAccount="";
    interface Progress { void update(String message); }
    interface OpenBrowser { void open(String url); }
    ChatGpt(Vault vault) { this.vault=vault; }
    static String random() { byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String form(Map<String,String> params) {
        StringJoiner out=new StringJoiner("&");
        params.forEach((k,v)->out.add(URLEncoder.encode(k,StandardCharsets.UTF_8)+"="+URLEncoder.encode(v,StandardCharsets.UTF_8))); return out.toString();
    }
    static HttpURLConnection connect(String url,String method,String bearer,String contentType,byte[] body) throws Exception {
        URL target=new URL(url);
        if(!target.getProtocol().equals("https") || !(target.getHost().equals("auth.openai.com")||target.getHost().equals("api.openai.com")) || target.getUserInfo()!=null)throw new SecurityException("연결 주소를 확인할 수 없어요.");
        HttpURLConnection c=(HttpURLConnection)target.openConnection(); c.setInstanceFollowRedirects(false); c.setConnectTimeout(20000);c.setReadTimeout(120000);c.setRequestMethod(method);
        if(bearer!=null)c.setRequestProperty("Authorization","Bearer "+bearer);
        c.setRequestProperty("Accept", "application/json");
        if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type",contentType);try(OutputStream out=c.getOutputStream()){out.write(body);}}
        return c;
    }
    static String readLimited(InputStream in,int max) throws Exception {
        try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()) {byte[] b=new byte[8192];int n;while((n=stream.read(b))!=-1){if(out.size()+n>max)throw new IOException("응답이 너무 길어요.");out.write(b,0,n);}return out.toString(StandardCharsets.UTF_8);}
    }
    static JSONObject jsonRequest(String url,String method,String bearer,String type,byte[] body) throws Exception {
        HttpURLConnection c=connect(url,method,bearer,type,body);
        try {
            int status=c.getResponseCode();
            if(status<200||status>=300) {
                String code="";try{JSONObject raw=new JSONObject(readLimited(c.getErrorStream(),100000));Object e=raw.opt("error");code=e instanceof JSONObject?((JSONObject)e).optString("code"):String.valueOf(e);}catch(Exception ignored){}
                if(status==401||code.equals("invalid_grant")||code.contains("refresh_token"))throw new AuthExpired();
                if(status==429||code.contains("subscription_sharing_usage"))throw new IOException("ChatGPT 구독의 사용 한도에 도달했어요. 설정에서 사용량을 확인해 주세요.");
                if(status==403)throw new IOException("이 계정에서 구독 사용이 허용되지 않았어요. ChatGPT의 앱 연결 설정을 확인해 주세요.");
                throw new IOException("ChatGPT 연결을 마치지 못했어요. 잠시 후 다시 시도해 주세요.");
            }
            return new JSONObject(readLimited(c.getInputStream(),2000000));
        } finally {c.disconnect();}
    }
    static final class AuthExpired extends IOException { AuthExpired(){super("ChatGPT에 다시 로그인해 주세요.");} }
    JSONObject discovery() throws Exception { return jsonRequest(Oidc.ISSUER+"/.well-known/openid-configuration","GET",null,null,null); }
    JSONObject verifyTokens(JSONObject token,String client,String nonce,String priorSubject) throws Exception {
        if(!"Bearer".equalsIgnoreCase(token.optString("token_type")))throw new SecurityException("지원되지 않는 계정 연결이에요.");
        JSONObject keys=jsonRequest(Oidc.ISSUER+"/.well-known/jwks.json","GET",null,null,null);
        return Oidc.verify(token.getString("id_token"),keys,client,nonce,priorSubject,System.currentTimeMillis()/1000);
    }
    synchronized JSONObject signIn(String selected,OpenBrowser browser,Progress progress) throws Exception {
        signInCancelled=false;
        retryAccount=selected;
        String stage="WAIT";
        JSONObject root=vault.read(), accounts=root.optJSONObject("accounts"); if(accounts==null)accounts=new JSONObject();
        JSONObject prior=accounts.optJSONObject(selected); String client=prior==null?"dynamic_agent_client":prior.getString("clientId");
        String host=root.optString("hostId");if(host.isEmpty()){host="urn:uuid:"+UUID.randomUUID();root.put("hostId",host);vault.write(root);}
        String state=random(),nonce=random(),verifier=random();
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        try(LoopbackCallback server=new LoopbackCallback()) {
            callback=server;
            String redirect=server.redirectUri();
            Map<String,String> params=new LinkedHashMap<>();
            params.put("client_id",client);params.put("ext_agent_host_id",host);
            if(prior==null)params.put("agent_name_hint","행간");
            else { if(!prior.optString("idToken").isEmpty())params.put("id_token_hint",prior.getString("idToken"));if(!prior.optString("email").isEmpty())params.put("login_hint",prior.getString("email")); }
            params.put("response_type","code");params.put("redirect_uri",redirect);params.put("scope","openid profile email offline_access resource.invoke chatgpt.tokens.use.direct");params.put("resource",RESOURCE);params.put("state",state);params.put("nonce",nonce);params.put("code_challenge_method","S256");params.put("code_challenge",challenge);
            progress.update("브라우저의 승인 응답을 기다리고 있어요 · HG-WAIT");
            browser.open(Oidc.ISSUER+"/api/accounts/authorize?"+form(params));
            Map<String,String> result=server.await(state,150000);
            ensureSignInActive();
            stage="CONSENT";
            if(result.containsKey("error")) {
                String error=result.get("error");
                if("access_denied".equals(error))throw new IOException("ChatGPT에서 연결이 승인되지 않았어요. 승인 화면의 안내를 확인해 주세요. [HG-CONSENT]");
                throw new IOException("ChatGPT 승인 화면에서 오류가 반환됐어요. [HG-CONSENT / "+safeCode(error)+"]");
            }
            String issued=result.get("client_id");
            if(prior==null) {
                if(issued==null||!issued.matches("[A-Za-z0-9_-]{8,200}")||issued.equals("dynamic_agent_client"))throw new IOException("계정 등록 응답을 받지 못했어요. [HG-REGISTER]");
                client=issued; prior=new JSONObject().put("clientId",client).put("label","연결 재시도");
                retryAccount=issued;
                root=vault.read();accounts=root.optJSONObject("accounts");if(accounts==null)accounts=new JSONObject();accounts.put(client,prior);root.put("accounts",accounts);vault.write(root);
            } else if(issued!=null&&!client.equals(issued))throw new SecurityException("다른 계정의 연결 요청이에요. [HG-ACCOUNT]");
            String code=result.get("code");if(code==null||code.isBlank())throw new IOException("로그인 응답이 비어 있어요. [HG-CODE]");
            stage="TOKEN";
            progress.update("승인 응답을 받았어요. 계정을 연결하고 있어요 · HG-TOKEN");
            JSONObject token=signInRequest(Oidc.ISSUER+"/api/accounts/oauth/token",form(Map.of("grant_type","authorization_code","client_id",client,"code",code,"code_verifier",verifier,"redirect_uri",redirect,"resource",RESOURCE)).getBytes(StandardCharsets.UTF_8));
            ensureSignInActive();
            stage="VERIFY";
            progress.update("계정의 서명을 확인하고 있어요 · HG-VERIFY");
            if(!"Bearer".equalsIgnoreCase(token.optString("token_type")))throw new SecurityException("지원되지 않는 계정 연결이에요.");
            JSONObject keys=signInRequest(Oidc.ISSUER+"/.well-known/jwks.json",null);
            JSONObject identity=Oidc.verify(token.getString("id_token"),keys,client,nonce,prior.optString("subject").isEmpty()?null:prior.getString("subject"),System.currentTimeMillis()/1000);
            ensureSignInActive();
            JSONObject record=mergeTokens(prior,token,identity);
            root=vault.read();accounts=root.getJSONObject("accounts");accounts.put(client,record);root.put("activeAccount",client);vault.write(root);
            progress.update("연결을 확인했어요 · HG-DONE");
            return publicAccount(record);
        } catch(Exception error) {
            if(signInCancelled)throw new IOException(cancelReason);
            if(error instanceof SocketTimeoutException)throw new IOException(stage.equals("WAIT")?"승인 응답이 앱에 도착하지 않았어요. 연결을 다시 시작해 주세요. [HG-WAIT-TIMEOUT]":"ChatGPT 응답 시간이 초과됐어요. 다시 연결해 주세요. [HG-"+stage+"-TIMEOUT]");
            String message=error.getMessage();
            if(message!=null&&message.matches("(?s).*[가-힣].*"))throw new IOException(message+" [HG-"+stage+"]");
            throw new IOException("계정 연결을 마치지 못했어요. 다시 시도해 주세요. [HG-"+stage+"-"+errorKind(error)+"]");
        } finally {callback=null;tokenConnection=null;}
    }
    static String safeCode(String code){return code!=null&&code.matches("[a-zA-Z0-9_]{1,80}")?code:"unknown_error";}
    static String errorKind(Exception error){if(error instanceof javax.net.ssl.SSLException)return "TLS";if(error instanceof UnknownHostException)return "DNS";if(error instanceof SecurityException)return "IDENTITY";if(error instanceof SocketException)return "NETWORK";return "ERROR";}
    private void ensureSignInActive() throws IOException {if(signInCancelled)throw new IOException(cancelReason);}
    boolean isSignInPending(){return callback!=null&&!signInCancelled;}
    String retryAccount(){return retryAccount;}
    void cancelSignIn(){cancelSignIn("계정 연결을 취소했어요.");}
    void cancelSignIn(String reason){cancelReason=reason;signInCancelled=true;LoopbackCallback s=callback;if(s!=null)try{s.close();}catch(IOException ignored){}HttpURLConnection c=tokenConnection;if(c!=null)c.disconnect();}
    private JSONObject signInRequest(String url,byte[] body) throws Exception {
        ensureSignInActive();
        HttpURLConnection c=connect(url,body==null?"GET":"POST",null,body==null?null:"application/x-www-form-urlencoded",null);
        tokenConnection=c;c.setConnectTimeout(8000);c.setReadTimeout(10000);
        try {
            ensureSignInActive();
            if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");try(OutputStream out=c.getOutputStream()){out.write(body);}}
            int status=c.getResponseCode();
            if(status<200||status>=300){String code="unknown_error";try{Object e=new JSONObject(readLimited(c.getErrorStream(),100000)).opt("error");code=safeCode(e instanceof JSONObject?((JSONObject)e).optString("code"):String.valueOf(e));}catch(Exception ignored){}throw new IOException("ChatGPT가 연결 요청을 완료하지 못했어요. [HTTP "+status+" / "+code+"]");}
            return new JSONObject(readLimited(c.getInputStream(),2000000));
        } finally {c.disconnect();if(tokenConnection==c)tokenConnection=null;}
    }
    private JSONObject mergeTokens(JSONObject record,JSONObject t,JSONObject identity) throws Exception {
        if(identity!=null){record.put("subject",identity.getString("sub"));record.put("email",identity.optString("email"));record.put("label",identity.optString("email", "ChatGPT 계정"));}
        if(t.optString("access_token").isBlank())throw new IOException("계정 연결 정보를 받지 못했어요.");
        record.put("accessToken",t.getString("access_token"));
        if(t.has("refresh_token"))record.put("refreshToken",t.getString("refresh_token"));
        if(t.has("id_token"))record.put("idToken",t.getString("id_token"));
        if(t.has("scope"))record.put("scope",t.getString("scope"));
        record.put("expiresAt",System.currentTimeMillis()+t.optLong("expires_in",3600)*1000L);return record;
    }
    static boolean sharing(JSONObject a){return a!=null&&!a.optString("accessToken").isEmpty()&&Arrays.asList(a.optString("scope").split(" ")).contains("chatgpt.tokens.use.direct");}
    static JSONObject publicAccount(JSONObject a) throws Exception {return new JSONObject().put("id",a.getString("clientId")).put("label",a.optString("label","ChatGPT 계정")).put("connected",!a.optString("accessToken").isEmpty()).put("sharing",sharing(a)).put("welcomed",a.optBoolean("welcomed")).put("model",a.optString("model"));}
    private synchronized JSONObject active() throws Exception {
        JSONObject root=vault.read(),a=root.optJSONObject("accounts")==null?null:root.getJSONObject("accounts").optJSONObject(root.optString("activeAccount"));
        if(!sharing(a))throw new IOException("설정에서 ChatGPT 구독 사용을 연결해 주세요.");
        if(a.getLong("expiresAt")>System.currentTimeMillis()+60000)return a;
        try {
            JSONObject token=jsonRequest(Oidc.ISSUER+"/api/accounts/oauth/token","POST",null,"application/x-www-form-urlencoded",form(Map.of("grant_type","refresh_token","client_id",a.getString("clientId"),"refresh_token",a.getString("refreshToken"),"resource",RESOURCE)).getBytes(StandardCharsets.UTF_8));
            JSONObject identity=token.has("id_token")?verifyTokens(token,a.getString("clientId"),null,a.getString("subject")):null;
            a=mergeTokens(a,token,identity);root.getJSONObject("accounts").put(a.getString("clientId"),a);vault.write(root);return a;
        } catch(AuthExpired e) {clearTokens(a);root.getJSONObject("accounts").put(a.getString("clientId"),a);vault.write(root);throw e;}
    }
    JSONArray models() throws Exception {
        JSONObject a=active();JSONArray all=jsonRequest(RESOURCE+"/models","GET",a.getString("accessToken"),null,null).getJSONArray("models"),listed=new JSONArray();
        for(int i=0;i<all.length();i++){JSONObject m=all.getJSONObject(i);if("list".equals(m.optString("visibility")))listed.put(new JSONObject().put("id",m.getString("slug")).put("name",m.getString("display_name")));}
        return listed;
    }
    String complete(String instructions,String input) throws Exception {
        JSONObject a=active();JSONArray available=models();String model=a.optString("model");boolean found=false;
        for(int i=0;i<available.length();i++)if(available.getJSONObject(i).getString("id").equals(model))found=true;
        if(!found){if(available.length()==0)throw new IOException("이 계정에서 사용할 수 있는 모델이 없어요.");model=available.getJSONObject(0).getString("id");}
        a=active();
        JSONObject payload=new JSONObject().put("model",model).put("store",false).put("stream",true).put("instructions",instructions).put("input",new JSONArray().put(new JSONObject().put("role","user").put("content",input)));
        HttpURLConnection c=connect(RESOURCE+"/responses","POST",a.getString("accessToken"),"application/json",payload.toString().getBytes(StandardCharsets.UTF_8));
        try {
            int status=c.getResponseCode();
            if(status==401)throw new AuthExpired();
            if(status==429)throw new IOException("ChatGPT 구독의 사용 한도에 도달했어요. 설정에서 사용량을 확인해 주세요.");
            if(status<200||status>=300)throw new IOException("ChatGPT가 정리를 시작하지 못했어요. 계정의 앱 사용 권한을 확인해 주세요.");
            return ResponseStream.read(new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8)));
        } finally {c.disconnect();}
    }
    boolean signOut(String id) throws Exception {
        JSONObject root=vault.read(),a=root.getJSONObject("accounts").getJSONObject(id);boolean revoked=a.optString("refreshToken").isEmpty();
        if(!revoked)try {
            String endpoint=discovery().getString("revocation_endpoint");
            HttpURLConnection c=connect(endpoint,"POST",null,"application/x-www-form-urlencoded",form(Map.of("token",a.getString("refreshToken"),"token_type_hint","refresh_token","client_id",id)).getBytes(StandardCharsets.UTF_8));
            try{revoked=c.getResponseCode()==200;}finally{c.disconnect();}
        } catch(Exception ignored){}
        clearTokens(a);root.getJSONObject("accounts").put(id,a);vault.write(root);return revoked;
    }
    static void clearTokens(JSONObject a){for(String k:new String[]{"accessToken","refreshToken","idToken","scope","expiresAt"})a.remove(k);}
}
