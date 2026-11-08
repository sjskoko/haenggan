package io.haenggan.diary;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.speech.*;
import android.webkit.*;
import android.view.*;
import java.io.*;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
    private WebView web;private Vault vault;private ChatGpt chat;private DiaryStore diary;
    private final ExecutorService queue=Executors.newSingleThreadExecutor();
    private SpeechRecognizer speech;private String exportText;private boolean wantSpeech=false,allowNetwork=false;
    private static final String ORIGIN="https://appassets.androidplatform.net";
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {vault=new Vault(this);chat=new ChatGpt(vault);diary=new DiaryStore(vault,chat);}
        catch(Exception error){new AlertDialog.Builder(this).setTitle("기록을 열지 못했어요").setMessage("암호화된 보관함을 확인할 수 없어요. 기록을 보호하기 위해 앱을 멈췄어요. 앱 데이터를 삭제하지 말고 백업 파일이 있는지 확인해 주세요.").setPositiveButton("닫기",(d,w)->finish()).show();return;}
        web=new WebView(this);web.setBackgroundColor(0xfff7f5ef);setContentView(web);
        web.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setDomStorageEnabled(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSaveFormData(false);
        CookieManager.getInstance().setAcceptCookie(false);web.addJavascriptInterface(new Bridge(),"Haenggan");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request) {
                Uri u=request.getUrl();String path=u.getPath();
                if(!ORIGIN.equals(u.getScheme()+"://"+u.getAuthority())||!request.getMethod().equals("GET")||!Set.of("/index.html","/app.js","/style.css").contains(path))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                try {
                    String mime=path.endsWith(".js")?"text/javascript":path.endsWith(".css")?"text/css":"text/html";
                    return new WebResourceResponse(mime,"UTF-8",200,"OK",Map.of("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; base-uri 'none'; form-action 'none'; frame-src 'none'","Cache-Control","no-store","X-Content-Type-Options","nosniff"),getAssets().open(path.substring(1)));
                } catch(IOException e){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
            }
            @Override public void onPageFinished(WebView v,String url){if(getIntent().getBooleanExtra("write",false)){event("write",new JSONObject());getIntent().removeExtra("write");}}
        });
        web.loadUrl(ORIGIN+"/index.html");
    }
    private void reply(String id,Object result,String error) {
        runOnUiThread(()->{if(web==null)return;try{JSONObject r=new JSONObject().put("id",id).put("data",result==null?JSONObject.NULL:result).put("error",error==null?JSONObject.NULL:error);web.evaluateJavascript("window.nativeReply("+r+")",null);}catch(Exception ignored){}});
    }
    private void event(String name,JSONObject data){runOnUiThread(()->{if(web!=null)web.evaluateJavascript("window.nativeEvent("+JSONObject.quote(name)+","+data+")",null);});}
    private void browser(String url){runOnUiThread(()->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(ActivityNotFoundException e){chat.cancelSignIn();event("error",message("기본 브라우저를 설치한 뒤 다시 연결해 주세요."));}});}
    private static JSONObject message(String text){try{return new JSONObject().put("text",text);}catch(Exception e){return new JSONObject();}}
    private JSONObject state() throws Exception {
        JSONObject s=diary.state();NotificationManager n=getSystemService(NotificationManager.class);AlarmManager a=getSystemService(AlarmManager.class);
        boolean enabled=ReminderReceiver.prefs(this).getBoolean("enabled",false),granted=n.areNotificationsEnabled();
        s.put("reminders",enabled&&granted).put("reminderRequested",enabled).put("exactAlarm",Build.VERSION.SDK_INT<31||a.canScheduleExactAlarms()).put("onDeviceSpeech",Build.VERSION.SDK_INT>=31&&SpeechRecognizer.isOnDeviceRecognitionAvailable(this));
        JSONArray entries=s.getJSONArray("entries");boolean hasToday=false;for(int i=0;i<entries.length();i++)hasToday|=DiaryStore.today().equals(entries.getJSONObject(i).optString("date"));
        ReminderReceiver.prefs(this).edit().putString("lastSavedDay",hasToday?DiaryStore.today():"").apply();return s;
    }
    private final class Bridge {
        @JavascriptInterface public void call(String id,String method,String raw) {
            if(id.length()>100||method.length()>50||raw.length()>180000){reply(id,null,"입력이 너무 길어요.");return;}
            if(method.equals("cancelConnect")){chat.cancelSignIn();reply(id,true,null);return;}
            if(method.equals("speech")||method.equals("stopSpeech")||method.equals("reminder")||method.equals("exactAlarm")||method.equals("testReminder")||method.equals("usage")) {
                runOnUiThread(()->{try {JSONObject input=new JSONObject(raw);Object result=true;
                    switch(method) {
                        case "speech" -> {allowNetwork=input.optBoolean("allowNetwork");if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){wantSpeech=true;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10);}else startSpeech();}
                        case "stopSpeech" -> {if(speech!=null)speech.stopListening();}
                        case "usage" -> browser("https://chatgpt.com/settings/usage");
                        case "reminder" -> {boolean enabled=input.getBoolean("enabled");ReminderReceiver.prefs(MainActivity.this).edit().putBoolean("enabled",enabled).apply();if(enabled&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);ReminderReceiver.schedule(MainActivity.this);}
                        case "exactAlarm" -> {if(Build.VERSION.SDK_INT>=31)startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));}
                        case "testReminder" -> {if(!getSystemService(NotificationManager.class).areNotificationsEnabled())throw new IllegalStateException("휴대폰 설정에서 행간의 알림을 허용해 주세요.");ReminderReceiver.show(MainActivity.this);}
                    }
                    reply(id,result,null);
                } catch(Exception e){reply(id,null,safeError(e));}});return;
            }
            queue.execute(()->{try {
                JSONObject input=new JSONObject(raw);Object result;
                switch(method) {
                    case "state" -> result=state();
                    case "draft" -> {diary.draft(input.getString("text"));result=true;}
                    case "save" -> {result=diary.save(input);state();}
                    case "delete" -> {diary.delete(input.getString("id"));state();result=true;}
                    case "summary" -> result=diary.summarize(input.getString("id"));
                    case "week" -> result=diary.week(input.getString("start"));
                    case "summaryWeek" -> result=diary.summarizeWeek(input.getString("start"));
                    case "intention" -> {diary.intention(input.getString("start"),input.getString("text"));result=true;}
                    case "question" -> {String text=input.getString("text");if(text.length()<10||text.length()>16000)throw new IllegalArgumentException("일기를 조금 더 남긴 뒤 질문을 받아보세요.");result=chat.complete(DiaryStore.EDITOR+"사용자가 언급한 사건이나 감정에서 질문 딱 하나를 60자 이내로 반환하라. 조언이나 추측은 하지 마라.",text);}
                    case "connect" -> result=chat.signIn(input.optString("id"),MainActivity.this::browser);
                    case "selectAccount" -> {diary.select(input.getString("id"));result=state();}
                    case "disconnect" -> result=new JSONObject().put("revoked",chat.signOut(input.getString("id")));
                    case "welcome" -> {diary.accountSetting("welcomed","");result=true;}
                    case "models" -> result=chat.models();
                    case "model" -> {String model=input.getString("id");JSONArray models=chat.models();boolean valid=false;for(int i=0;i<models.length();i++)valid|=models.getJSONObject(i).getString("id").equals(model);if(!valid)throw new IllegalArgumentException("이 계정에서 사용할 수 없는 모델이에요.");diary.accountSetting("model",model);result=true;}
                    case "export" -> {exportText=diary.export();runOnUiThread(()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"haenggan-"+DiaryStore.today()+".json"),12));result=true;}
                    default -> throw new IllegalArgumentException("지원하지 않는 요청이에요.");
                }
                reply(id,result,null);
            }catch(Exception e){reply(id,null,safeError(e));}});
        }
    }
    private static String safeError(Exception e){if(e instanceof SocketException)return "계정 연결을 취소했어요.";String text=e.getMessage();if((e instanceof IOException||e instanceof SecurityException||e instanceof IllegalArgumentException)&&text!=null&&text.matches(".*[가-힣].*"))return text;return "작업을 마치지 못했어요. 잠시 후 다시 시도해 주세요.";}
    private void startSpeech() {
        if(speech!=null){speech.destroy();speech=null;}
        boolean local=Build.VERSION.SDK_INT>=31&&SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
        if(!local&&!allowNetwork){event("error",message("이 기기는 오프라인 음성 인식을 지원하지 않아요. 키보드의 음성 입력을 이용할 수 있어요."));return;}
        if(!SpeechRecognizer.isRecognitionAvailable(this)&&!local){event("error",message("휴대폰의 음성 인식 기능을 사용할 수 없어요."));return;}
        speech=local?SpeechRecognizer.createOnDeviceSpeechRecognizer(this):SpeechRecognizer.createSpeechRecognizer(this);
        speech.setRecognitionListener(new RecognitionListener(){
            public void onReadyForSpeech(Bundle p){event("listening",new JSONObject());}
            public void onBeginningOfSpeech(){}public void onRmsChanged(float v){}public void onBufferReceived(byte[] b){}
            public void onEndOfSpeech(){event("speechEnding",new JSONObject());}
            public void onError(int error){event("speechStopped",new JSONObject());event("error",message(error==SpeechRecognizer.ERROR_NO_MATCH||error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT?"말소리가 인식되지 않았어요. 다시 이야기해 주세요.":"음성 인식을 마치지 못했어요. 휴대폰의 한국어 음성 모델과 마이크 권한을 확인해 주세요."));}
            public void onResults(Bundle results){ArrayList<String> values=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(values!=null&&!values.isEmpty())event("speechResult",message(values.get(0)));event("speechStopped",new JSONObject());}
            public void onPartialResults(Bundle results){ArrayList<String> values=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(values!=null&&!values.isEmpty())event("speechPartial",message(values.get(0)));}
            public void onEvent(int type,Bundle params){}
        });
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR").putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
        speech.startListening(i);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==10&&wantSpeech){wantSpeech=false;if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startSpeech();else event("error",message("마이크 권한을 허용해야 목소리로 남길 수 있어요."));}if(request==11){ReminderReceiver.schedule(this);event("refresh",new JSONObject());}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==12&&result==RESULT_OK&&data!=null&&data.getData()!=null&&exportText!=null){String content=exportText;Uri target=data.getData();exportText=null;queue.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(target)){if(out==null)throw new IOException();out.write(content.getBytes(StandardCharsets.UTF_8));event("notice",message("선택한 계정의 일기를 백업했어요."));}catch(Exception e){event("error",message("백업 파일을 저장하지 못했어요."));}});}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("write",false))event("write",new JSONObject());}
    @Override protected void onResume(){super.onResume();if(web!=null){ReminderReceiver.schedule(this);event("refresh",new JSONObject());}}
    @Override protected void onPause(){super.onPause();if(speech!=null){speech.cancel();event("speechStopped",new JSONObject());}}
    @Override protected void onDestroy(){if(chat!=null)chat.cancelSignIn();if(speech!=null)speech.destroy();queue.shutdownNow();if(web!=null){web.removeJavascriptInterface("Haenggan");web.destroy();web=null;}super.onDestroy();}
    @Override public void onBackPressed(){if(web!=null)web.evaluateJavascript("window.appBack()",null);else super.onBackPressed();}
}
