package com.quremed.rehaflow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.print.PrintManager;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.*;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Locale;

public final class MainActivity extends Activity {
    private LinearLayout root;
    private WebView web;
    private TextView status;
    private String origin = "";
    private boolean loadFailed;
    private boolean nativeWorkspace=false, nativeSeeded=false, nativeAuthSeen=false;
    private String alertToken="",alertUser="";
    private boolean activityVisible=false;
    private String destination="/";
    private LinearLayout connectionBar;
    private ValueCallback<Uri[]> fileCallback;
    private byte[] pendingDocument;
    private static final int PICK_FILE = 11, SAVE_FILE = 12;
    private final int background = Color.rgb(11,16,26);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        nativeWorkspace=getIntent().getBooleanExtra("native_workspace",false);
        destination=safeDestination(getIntent().getStringExtra("destination"));
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((v,insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        String saved = getPreferences(MODE_PRIVATE).getString("server", "");
        if (saved.isEmpty()) setup(); else {
            try { connect(normalize(saved)); } catch(Exception e) { setup(); }
        }
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(Color.rgb(220,237,241)); view.setPadding(20,14,20,14); return view;
    }
    private Button button(String title) { Button b = new Button(this); b.setText(title); b.setAllCaps(false); return b; }
    private void destroyBrowser() {
        if(fileCallback!=null){fileCallback.onReceiveValue(null);fileCallback=null;}
        pendingDocument=null;
        if(web!=null){root.removeView(web);web.stopLoading();web.removeJavascriptInterface("QureMedAndroid");web.destroy();web=null;}
    }
    private void setup() {
        if(nativeWorkspace){startActivity(new Intent(this,com.quremed.rehaflow.nativeui.NativeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("destination","/settings"));finish();return;}
        clearAlertSession();destroyBrowser(); root.removeAllViews(); origin="";
        root.addView(text("QureMed Industries",18)); root.addView(text("RehaFlow\nВаш центр у телефоні",30));
        root.addView(text("Підключіться до Wi-Fi центру. Введіть адресу сервера один раз — застосунок її запам’ятає.",16));
        EditText address = new EditText(this); address.setSingleLine(true); address.setHint("https://192.168.1.106"); address.setTextColor(Color.WHITE); address.setHintTextColor(Color.LTGRAY);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(getPreferences(MODE_PRIVATE).getString("server","https://192.168.1.106")); root.addView(address);
        status=text("Адреса — IP комп’ютера з сервером, наприклад 192.168.1.106. Адреса роутера та IP телефона не підходять.",14);root.addView(status);
        Button help=button("Як установити сертифікат центру");root.addView(help);help.setOnClickListener(v->certificateHelp());
        Button connect=button("Підключитися"); root.addView(connect);
        connect.setOnClickListener(v->{try{String next=normalize(address.getText().toString());getPreferences(MODE_PRIVATE).edit().putString("server",next).apply();connect(next);}catch(Exception e){status.setText("Вкажіть HTTPS-адресу сервера без шляху, логіна чи пароля.");}});
    }
    static String normalize(String value) throws Exception {
        String s=value.trim();if(!s.contains("://"))s="https://"+s;
        URI uri=new URI(s);
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||(!uri.getPath().isEmpty()&&!uri.getPath().equals("/"))||uri.getPort()==0||uri.getPort()>65535)throw new IllegalArgumentException();
        return new URI("https",null,uri.getHost().toLowerCase(Locale.ROOT),uri.getPort()==443?-1:uri.getPort(),null,null,null).toString();
    }
    private boolean sameOrigin(String value) {
        try { URI u=new URI(value),o=new URI(origin);return "https".equalsIgnoreCase(u.getScheme())&&o.getHost().equalsIgnoreCase(u.getHost())&&(u.getPort()==-1?443:u.getPort())==(o.getPort()==-1?443:o.getPort())&&u.getUserInfo()==null; }catch(Exception e){return false;}
    }
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private void connect(String server) {
        clearAlertSession();destroyBrowser();root.removeAllViews();origin=server;
        LinearLayout toolbar=new LinearLayout(this);connectionBar=toolbar;toolbar.setVisibility(View.GONE);
        Button settings=button("Сервер"),reload=button("Оновити"),print=button("Друк");
        toolbar.addView(settings);toolbar.addView(reload);Button help=button("Сертифікат");toolbar.addView(help);help.setOnClickListener(v->certificateHelp());root.addView(toolbar);
        status=text("Підключення…",12);root.addView(status);
        settings.setOnClickListener(v->new AlertDialog.Builder(this).setMessage("Вийти з поточного вікна та змінити сервер?").setNegativeButton("Назад",null).setPositiveButton("Змінити",(d,w)->setup()).show());
        web=new WebView(this);web.setBackgroundColor(background);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setCacheMode(WebSettings.LOAD_NO_CACHE);s.setSupportMultipleWindows(false);s.setUserAgentString(s.getUserAgentString()+" RehaFlowAndroid/2.4.0");
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.addJavascriptInterface(new DocumentBridge(),"QureMedAndroid");
        reload.setOnClickListener(v->web.reload());print.setOnClickListener(v->printPage());
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon){loadFailed=false;status.setVisibility(View.VISIBLE);status.setText("Підключення…");}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){return !sameOrigin(req.getUrl().toString());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest req){String url=req.getUrl().toString();if(!sameOrigin(url)&&!url.startsWith("data:")&&!url.startsWith("blob:"+origin+"/"))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));return null;}
            @Override public void onReceivedSslError(WebView v,SslErrorHandler handler,SslError error){handler.cancel();loadFailed=true;connectionBar.setVisibility(View.VISIBLE);status.setVisibility(View.VISIBLE);status.setText(tlsExplanation(error));}
            @Override public void onReceivedError(WebView v,WebResourceRequest req,WebResourceError error){if(req.isForMainFrame()&&!loadFailed){loadFailed=true;connectionBar.setVisibility(View.VISIBLE);status.setVisibility(View.VISIBLE);status.setText("Сервер недоступний. Перевірте Wi-Fi, адресу й запуск сервера. Натисніть Оновити.");}}
            @Override public void onPageFinished(WebView v,String url){if(sameOrigin(url)&&!loadFailed){
                if(nativeWorkspace&&!nativeSeeded&&origin.equals(NativeSession.server)&&!NativeSession.token.isEmpty()){
                    nativeSeeded=true;
                    v.evaluateJavascript("sessionStorage.setItem('quremed-token',"+org.json.JSONObject.quote(NativeSession.token)+");location.replace("+org.json.JSONObject.quote(origin+destination)+");",null);return;
                }
                status.setVisibility(View.GONE);connectionBar.setVisibility(View.GONE);
            }}
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){if(fileCallback!=null)fileCallback.onReceiveValue(null);fileCallback=callback;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/pdf","image/jpeg","image/png"});try{startActivityForResult(intent,PICK_FILE);}catch(Exception e){callback.onReceiveValue(null);fileCallback=null;}return true;}
        });
        web.loadUrl(origin+destination);
    }
    private static String safeDestination(String path){if(path!=null&&path.matches("/patients\\?patient=[0-9a-fA-F-]{36}"))return path;return path!=null&&java.util.Arrays.asList("/","/pool","/messages","/tasks","/handovers","/settings","/patients","/archive","/rooms","/cabinets","/schedule","/users","/sessions","/audit").contains(path)?path:"/";}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);destination=safeDestination(intent.getStringExtra("destination"));if(web!=null&&sameOrigin(web.getUrl()))web.loadUrl(origin+destination);}
    private void clearAlertSession(){alertToken="";alertUser="";stopService(new Intent(this,ShiftAlertsService.class));getSystemService(android.app.NotificationManager.class).cancelAll();}
    private void startAlerts(boolean ask){
        if(!activityVisible||alertToken.isEmpty()||!getSharedPreferences("shift-alerts",MODE_PRIVATE).getBoolean("enabled",true))return;
        if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){if(ask)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},25);return;}
        if(!getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()){ShiftAlertsService.state="Дозвольте сповіщення в налаштуваннях Android.";return;}
        Intent service=new Intent(this,ShiftAlertsService.class).putExtra("server",origin).putExtra("token",alertToken).putExtra("user",alertUser);
        try{startForegroundService(service);}catch(Exception e){ShiftAlertsService.state="Фонова перевірка недоступна. Відкрийте застосунок і спробуйте ще раз.";}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==25&&results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)startAlerts(false);}
    private String tlsExplanation(SslError error){
        String reason;
        switch(error.getPrimaryError()){
            case SslError.SSL_IDMISMATCH: reason="Адреса не збігається із сертифікатом. Введіть IP ПК сервера, для якого створено HTTPS, а не 192.168.1.1 (роутер).";break;
            case SslError.SSL_EXPIRED: reason="Сертифікат прострочений. Перевірте дату телефона й сервера та запустіть сервер для оновлення сертифіката.";break;
            case SslError.SSL_NOTYETVALID: reason="Сертифікат ще не чинний. Перевірте дату й час телефона та сервера.";break;
            case SslError.SSL_UNTRUSTED: reason="Android не довіряє центру сертифікації. Потрібен актуальний QureMed-Local-CA.crt саме з поточної папки сервера, встановлений як CA у тому самому профілі Android, де працює застосунок. Сертифікат Wi-Fi або старого сервера не підходить.";break;
            default: reason="Не вдалося перевірити HTTPS-сертифікат. Перевірте адресу, час і актуальний CA сервера.";
        }
        return reason+"\nСервер: "+origin+"\nКод TLS: "+error.getPrimaryError()+" · APK 2.4.0\nПісля встановлення CA повністю закрийте застосунок і відкрийте знову.";
    }
    private void certificateHelp(){new AlertDialog.Builder(this).setTitle("Довіра до сервера центру").setMessage("1. На ПК сервера знайдіть QureMed-Local-CA.crt поряд зі Start-QureMed.\n2. Скопіюйте цей файл на телефон через USB або передайте його особисто.\n3. Налаштування Android → Безпека → Інші налаштування безпеки → Установити сертифікат → Сертифікат CA. Назви меню можуть відрізнятися.\n4. Оберіть файл центру й підтвердьте встановлення. Поверніться сюди та натисніть «Оновити».\n\nАдреса застосунку — IP ПК сервера, а не шлюз роутера. Перевірте дату й час телефона.").setNegativeButton("Закрити",null).setPositiveButton("Налаштування безпеки",(d,w)->{try{startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));}catch(Exception e){Toast.makeText(this,"Відкрийте налаштування безпеки Android",Toast.LENGTH_LONG).show();}}).show();}
    private void printPage(){if(web!=null&&sameOrigin(web.getUrl())){PrintManager manager=(PrintManager)getSystemService(PRINT_SERVICE);if(manager!=null)manager.print("RehaFlow",web.createPrintDocumentAdapter("RehaFlow"),null);}}
    public final class DocumentBridge {
        @JavascriptInterface public synchronized String deviceInfo(){
            try{
                android.content.SharedPreferences prefs=getSharedPreferences("device-identity",MODE_PRIVATE);
                String id=prefs.getString("installation_id","");
                if(id.isEmpty()){id=java.util.UUID.randomUUID().toString();if(!prefs.edit().putString("installation_id",id).commit())return "{}";}
                android.content.pm.PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),0);
                return new org.json.JSONObject().put("id",id).put("model",android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL).put("android",android.os.Build.VERSION.RELEASE).put("version",info.versionName).put("package",getPackageName()).toString();
            }catch(Exception e){return "{}";}
        }
        @JavascriptInterface public void syncAuth(String token,String user){runOnUiThread(()->{
            if(!nativeWorkspace||!nativeSeeded||web==null||!sameOrigin(web.getUrl())||token==null||token.length()>8192)return;
            if(!token.isEmpty()&&user!=null&&!user.isEmpty()){nativeAuthSeen=true;NativeSession.token=token;NativeSession.userId=user;}
            else if(nativeAuthSeen)NativeSession.clear();
        });}
        @JavascriptInterface public void session(String token,String user){runOnUiThread(()->{if(web==null||!sameOrigin(web.getUrl())||token==null||token.length()>8192)return;alertToken=token;alertUser=user;startAlerts(true);});}
        @JavascriptInterface public void clearSession(){runOnUiThread(()->{clearAlertSession();});}
        @JavascriptInterface public void enableAlerts(){runOnUiThread(()->{getSharedPreferences("shift-alerts",MODE_PRIVATE).edit().putBoolean("enabled",true).apply();startAlerts(true);});}
        @JavascriptInterface public void disableAlerts(){runOnUiThread(()->{getSharedPreferences("shift-alerts",MODE_PRIVATE).edit().putBoolean("enabled",false).apply();stopService(new Intent(MainActivity.this,ShiftAlertsService.class));ShiftAlertsService.state="Фонові сповіщення вимкнено";});}
        @JavascriptInterface public String notificationStatus(){try{return new org.json.JSONObject().put("running",ShiftAlertsService.running).put("message",ShiftAlertsService.state).toString();}catch(Exception e){return "{}";}}
        @JavascriptInterface public void testAlert(){runOnUiThread(()->ShiftAlertsService.alert(MainActivity.this,"RehaFlow","Перевірка звуку сповіщень","/settings"));}
        @JavascriptInterface public void alertSettings(){runOnUiThread(()->{Intent intent=new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName());try{startActivity(intent);}catch(Exception e){Toast.makeText(MainActivity.this,"Відкрийте дозволи сповіщень RehaFlow у налаштуваннях Android",Toast.LENGTH_LONG).show();}});}
        @JavascriptInterface public void settings(){runOnUiThread(()->{if(web!=null&&sameOrigin(web.getUrl()))new AlertDialog.Builder(MainActivity.this).setMessage("Вийти з робочого простору та змінити сервер?").setNegativeButton("Назад",null).setPositiveButton("Змінити",(d,w)->setup()).show();});}
        @JavascriptInterface public void print(){runOnUiThread(()->printPage());}
        @JavascriptInterface public void download(String encoded,String mime,String filename){
            if(encoded==null||encoded.length()>7000000||!("application/pdf".equals(mime)||"image/jpeg".equals(mime)||"image/png".equals(mime)))return;
            final byte[] bytes;try{bytes=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT);}catch(Exception e){return;}if(bytes.length>5*1024*1024)return;
            runOnUiThread(()->{if(web==null||!sameOrigin(web.getUrl())||pendingDocument!=null)return;pendingDocument=bytes;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(mime);intent.putExtra(Intent.EXTRA_TITLE,filename.replaceAll("[\\\\/]","_"));try{startActivityForResult(intent,SAVE_FILE);}catch(Exception e){pendingDocument=null;status.setText("Не вдалося відкрити збереження файлу");}});
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PICK_FILE&&fileCallback!=null){fileCallback.onReceiveValue(result==RESULT_OK&&data!=null?new Uri[]{data.getData()}:null);fileCallback=null;}if(request==SAVE_FILE){byte[] bytes=pendingDocument;pendingDocument=null;if(result==RESULT_OK&&data!=null&&bytes!=null){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out!=null)out.write(bytes);Toast.makeText(this,"Документ збережено",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"Не вдалося зберегти документ",Toast.LENGTH_LONG).show();}}}}
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onPause(){activityVisible=false;if(web!=null)web.onPause();super.onPause();}
    @Override protected void onResume(){super.onResume();activityVisible=true;if(web!=null)web.onResume();if(!ShiftAlertsService.running)startAlerts(false);}
    @Override protected void onDestroy(){destroyBrowser();super.onDestroy();}
}
