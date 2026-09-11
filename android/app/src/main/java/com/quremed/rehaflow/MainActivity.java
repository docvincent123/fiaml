package com.quremed.rehaflow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
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
    private ValueCallback<Uri[]> fileCallback;
    private byte[] pendingDocument;
    private static final int PICK_FILE = 11, SAVE_FILE = 12;
    private static final String PREFS = "rehaflow_settings";
    private final int background = Color.rgb(11,16,26);

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((v,insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);

        // Migrate the address saved by older builds, then use one stable preference store.
        String saved = prefs().getString("server", "");
        if (saved.isEmpty()) {
            String legacy = getPreferences(MODE_PRIVATE).getString("server", "");
            if (!legacy.isEmpty()) { prefs().edit().putString("server", legacy).apply(); saved = legacy; }
        }
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
        destroyBrowser(); root.removeAllViews(); origin="";
        root.addView(text("QureMed Industries",18)); root.addView(text("RehaFlow\nВаш центр у телефоні",30));
        root.addView(text("Перший запуск: підключіться до Wi-Fi центру та введіть адресу сервера. Після успішного підключення застосунок запам’ятає її і надалі відкриватиметься автоматично.",16));
        EditText address = new EditText(this); address.setSingleLine(true); address.setHint("https://192.168.1.106"); address.setTextColor(Color.WHITE); address.setHintTextColor(Color.LTGRAY);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(prefs().getString("server","https://192.168.1.106")); root.addView(address);
        status=text("Якщо центр використовує локальний HTTPS, установіть QureMed-Local-CA.crt саме як CA-сертифікат. RehaFlow не вимикає перевірку TLS.",14);root.addView(status);
        Button connect=button("Підключитися"); root.addView(connect);
        connect.setOnClickListener(v->{try{String next=normalize(address.getText().toString());connect(next);}catch(Exception e){status.setText("Вкажіть HTTPS-адресу сервера без шляху, логіна чи пароля.");}});
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
    private String sslMessage(SslError error) {
        switch(error.getPrimaryError()) {
            case SslError.SSL_UNTRUSTED:
                return "Android не довіряє сертифікату сервера. Установіть QureMed-Local-CA.crt як CA-сертифікат, потім повністю закрийте й відкрийте RehaFlow.";
            case SslError.SSL_IDMISMATCH:
                return "Сертифікат виданий для іншої IP-адреси. Перевірте IP сервера; якщо IP змінювався, заново налаштуйте локальний HTTPS для поточної адреси.";
            case SslError.SSL_EXPIRED:
            case SslError.SSL_NOTYETVALID:
            case SslError.SSL_DATE_INVALID:
                return "Помилка дати HTTPS-сертифіката. Перевірте автоматичні дату й час на телефоні та сервері, потім перезапустіть локальний HTTPS.";
            default:
                return "HTTPS-сертифікат відхилено Android. Перевірте CA-сертифікат, IP сервера і дату/час. Код TLS: "+error.getPrimaryError();
        }
    }
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private void connect(String server) {
        destroyBrowser();root.removeAllViews();origin=server;
        LinearLayout toolbar=new LinearLayout(this);
        Button settings=button("Сервер"),reload=button("Оновити"),print=button("Друк");
        toolbar.addView(settings);toolbar.addView(reload);toolbar.addView(print);root.addView(toolbar);
        status=text("Підключення…",12);root.addView(status);
        settings.setOnClickListener(v->new AlertDialog.Builder(this).setMessage("Змінити адресу сервера? Поточна адреса залишиться збереженою, доки нове підключення не буде успішним.").setNegativeButton("Назад",null).setPositiveButton("Змінити",(d,w)->setup()).show());
        web=new WebView(this);web.setBackgroundColor(background);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setCacheMode(WebSettings.LOAD_NO_CACHE);s.setSupportMultipleWindows(false);s.setUserAgentString(s.getUserAgentString()+" RehaFlowAndroid/2.0");
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.addJavascriptInterface(new DocumentBridge(),"QureMedAndroid");
        reload.setOnClickListener(v->web.reload());print.setOnClickListener(v->printPage());
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon){loadFailed=false;status.setText("Підключення…");}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){return !sameOrigin(req.getUrl().toString());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest req){String url=req.getUrl().toString();if(!sameOrigin(url)&&!url.startsWith("data:")&&!url.startsWith("blob:"+origin+"/"))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));return null;}
            @Override public void onReceivedSslError(WebView v,SslErrorHandler handler,SslError error){handler.cancel();loadFailed=true;status.setText(sslMessage(error));}
            @Override public void onReceivedError(WebView v,WebResourceRequest req,WebResourceError error){if(req.isForMainFrame()){loadFailed=true;status.setText("Сервер недоступний. Перевірте Wi-Fi, адресу й запуск сервера. Натисніть Оновити.");}}
            @Override public void onPageFinished(WebView v,String url){if(sameOrigin(url)&&!loadFailed){prefs().edit().putString("server",origin).apply();status.setText("Підключено · "+origin);}}
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){if(fileCallback!=null)fileCallback.onReceiveValue(null);fileCallback=callback;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/pdf","image/jpeg","image/png"});try{startActivityForResult(intent,PICK_FILE);}catch(Exception e){callback.onReceiveValue(null);fileCallback=null;}return true;}
        });
        web.loadUrl(origin);
    }
    private void printPage(){if(web!=null&&sameOrigin(web.getUrl())){PrintManager manager=(PrintManager)getSystemService(PRINT_SERVICE);if(manager!=null)manager.print("RehaFlow",web.createPrintDocumentAdapter("RehaFlow"),null);}}
    public final class DocumentBridge {
        @JavascriptInterface public void print(){runOnUiThread(()->printPage());}
        @JavascriptInterface public void download(String encoded,String mime,String filename){
            if(encoded==null||encoded.length()>7000000||!("application/pdf".equals(mime)||"image/jpeg".equals(mime)||"image/png".equals(mime)))return;
            final byte[] bytes;try{bytes=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT);}catch(Exception e){return;}if(bytes.length>5*1024*1024)return;
            runOnUiThread(()->{if(web==null||!sameOrigin(web.getUrl())||pendingDocument!=null)return;pendingDocument=bytes;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(mime);intent.putExtra(Intent.EXTRA_TITLE,filename.replaceAll("[\\\\/]","_"));try{startActivityForResult(intent,SAVE_FILE);}catch(Exception e){pendingDocument=null;status.setText("Не вдалося відкрити збереження файлу");}});
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PICK_FILE&&fileCallback!=null){fileCallback.onReceiveValue(result==RESULT_OK&&data!=null?new Uri[]{data.getData()}:null);fileCallback=null;}if(request==SAVE_FILE){byte[] bytes=pendingDocument;pendingDocument=null;if(result==RESULT_OK&&data!=null&&bytes!=null){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out!=null)out.write(bytes);Toast.makeText(this,"Документ збережено",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"Не вдалося зберегти документ",Toast.LENGTH_LONG).show();}}}}
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onPause(){if(web!=null)web.onPause();super.onPause();}
    @Override protected void onResume(){super.onResume();if(web!=null)web.onResume();}
    @Override protected void onDestroy(){destroyBrowser();super.onDestroy();}
}
