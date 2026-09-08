using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.Web.WebView2.Core;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
namespace QureMed.Desktop;
public sealed partial class MainWindow : Window {
    readonly HttpClient http = new(new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(12) };
    readonly string folder = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "QureMed");
    WebView2? browser; Uri? origin; bool connecting; bool started; bool closing; bool uiReady;
    public MainWindow() {
        InitializeComponent(); Title = "RehaFlow · QureMed Industries";
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1380, 900));
        try {
            var file = System.IO.Path.Combine(folder,"server.txt");
            Address.Text = Environment.GetEnvironmentVariable("QUREMED_SERVER_URL") ?? (System.IO.File.Exists(file) ? System.IO.File.ReadAllText(file) : "https://192.168.1.100");
        } catch { Address.Text = "https://192.168.1.100"; }
        Closed += (_, _) => { closing = true; browser?.Close(); http.Dispose(); };
        App.StartupLog("Connection shell ready");
    }
    static Uri ValidateAddress(string value) {
        if (!Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri) || !string.IsNullOrEmpty(uri.UserInfo) || uri.AbsolutePath != "/" || uri.Query.Length > 0 || uri.Fragment.Length > 0)
            throw new Exception("Вкажіть адресу сервера без шляху, наприклад https://192.168.1.100");
        if (uri.Scheme != "https" && !(uri.Scheme == "http" && uri.IsLoopback))
            throw new Exception("Для мережі центру потрібна адреса https://. HTTP дозволено лише для localhost.");
        return new Uri(uri.GetLeftPart(UriPartial.Authority));
    }
    async void Root_Loaded(object sender,RoutedEventArgs e) {
        if(started)return;started=true;
        // CI or a previously configured installation can reconnect automatically.
        if(Environment.GetEnvironmentVariable("QUREMED_SERVER_URL")!=null || System.IO.File.Exists(System.IO.Path.Combine(folder,"server.txt")))await Connect();
    }
    async void Connect_Click(object sender,RoutedEventArgs e) => await Connect();
    async void Address_KeyDown(object sender,KeyRoutedEventArgs e) { if(e.Key==Windows.System.VirtualKey.Enter){e.Handled=true;await Connect();} }
    async void Reload_Click(object sender,RoutedEventArgs e) { if(connecting)return;if(browser?.CoreWebView2!=null&&origin!=null){ConnectionScreen.Visibility=Visibility.Visible;StatusText.Text="Оновлюємо робочий простір…";browser.CoreWebView2.Reload();}else await Connect(); }
    async void Runtime_Click(object sender,RoutedEventArgs e) => await Windows.System.Launcher.LaunchUriAsync(new Uri("https://developer.microsoft.com/microsoft-edge/webview2/"));
    void Failure(string text) {if(closing)return;StatusText.Text=text;Progress.Visibility=Visibility.Collapsed;ConnectionScreen.Visibility=Visibility.Visible;ShellFooter.Visibility=Visibility.Visible;}
    bool SameOrigin(string uri) => origin!=null && Uri.TryCreate(uri,UriKind.Absolute,out var target) && target.Scheme==origin.Scheme && target.Host==origin.Host && target.Port==origin.Port;
    async Task Connect() {
        if(connecting||closing)return;connecting=true;ConnectButton.IsEnabled=false;RuntimeButton.Visibility=Visibility.Collapsed;
        ConnectionScreen.Visibility=Visibility.Visible;ShellFooter.Visibility=Visibility.Visible;Progress.Visibility=Visibility.Visible;StatusText.Text="Перевіряємо підключення до центру…";
        try {
            var target=ValidateAddress(Address.Text);
            using var response=await http.GetAsync(new Uri(target,"/api/health"));
            if(!response.IsSuccessStatusCode)throw new Exception("Сервер не готовий. Перевірте запуск Start-QureMed.cmd і адресу.");
            var health=JsonNode.Parse(await response.Content.ReadAsStringAsync());
            if(health?["brand"]?.ToString()!="QureMed Industries"||health?["status"]?.ToString()!="ok")throw new Exception("За цією адресою немає сервера RehaFlow.");
            if(closing)return;
            StatusText.Text="Відкриваємо ваш робочий простір…";
            // Closing the prior WebView clears its sessionStorage; each server uses a separate browser profile.
            browser?.Close();BrowserHost.Children.Clear();origin=target;uiReady=false;
            var profile=System.IO.Path.Combine(folder,"WebView2",Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(target.AbsoluteUri)))[..16]);
            System.IO.Directory.CreateDirectory(profile);
            var environment=await CoreWebView2Environment.CreateWithOptionsAsync("",profile,new CoreWebView2EnvironmentOptions());
            browser=new WebView2();BrowserHost.Children.Add(browser);
            await browser.EnsureCoreWebView2Async(environment);
            if(closing)return;
            var core=browser.CoreWebView2;
            core.Settings.AreDevToolsEnabled=false;core.Settings.AreDefaultContextMenusEnabled=false;
            core.Settings.IsStatusBarEnabled=false;core.Settings.IsPasswordAutosaveEnabled=false;core.Settings.IsGeneralAutofillEnabled=false;
            core.NavigationStarting+=(_,args)=>{if(!SameOrigin(args.Uri)&&!args.Uri.StartsWith("blob:"+target.GetLeftPart(UriPartial.Authority)+"/",StringComparison.Ordinal))args.Cancel=true;};
            core.NewWindowRequested+=(_,args)=>{args.Handled=true;if(SameOrigin(args.Uri))core.Navigate(args.Uri);};
            core.PermissionRequested+=(_,args)=>{args.State=CoreWebView2PermissionState.Deny;};
            core.ServerCertificateErrorDetected+=(_,args)=>{args.Action=CoreWebView2ServerCertificateErrorAction.Cancel;Failure("Сертифікат сервера не довірений. Встановіть QureMed-Local-CA.crt згідно з інструкцією.");};
            core.ProcessFailed+=(_,_)=>Failure("Вікно інтерфейсу зупинилося. Натисніть «Підключитися», щоб відкрити його знову.");
            core.NavigationCompleted+=async(_,args)=>{
                if(!args.IsSuccess){Failure("Не вдалося відкрити інтерфейс. Перевірте доступність сервера та спробуйте підключитися ще раз.");return;}
                await Task.Delay(12000);
                if(!closing&&ReferenceEquals(browser?.CoreWebView2,core)&&!uiReady)Failure("Інтерфейс не завершив завантаження. Оновіть локальний сервер і натисніть «Підключитися».");
            };
            // One-way readiness signal only. No filesystem, command execution, or native auth bridge is exposed.
            core.WebMessageReceived+=(_,args)=>{
                if(!SameOrigin(args.Source))return;
                try{var message=JsonNode.Parse(args.WebMessageAsJson);if(message?["type"]?.ToString()!="quremed.ui.ready")return;
                    uiReady=true;ConnectionScreen.Visibility=Visibility.Collapsed;ShellFooter.Visibility=Visibility.Collapsed;Progress.Visibility=Visibility.Collapsed;App.StartupLog("React UI ready");
                }catch{ }
            };
            System.IO.File.WriteAllText(System.IO.Path.Combine(folder,"server.txt"),target.GetLeftPart(UriPartial.Authority));
            core.Navigate(target.AbsoluteUri);
        } catch(HttpRequestException){Failure("Немає підключення. Перевірте адресу, мережу центру та довіру до QureMed-Local-CA.crt.");}
          catch(TaskCanceledException){Failure("Сервер не відповідає. Перевірте, чи запущено локальний сервер.");}
          catch(Exception ex){App.StartupLog("Connection initialization: "+ex);Failure(ex.Message);if(ex.GetType().Name.Contains("WebView2Runtime"))RuntimeButton.Visibility=Visibility.Visible;}
        finally{connecting=false;if(!closing){ConnectButton.IsEnabled=true;Progress.Visibility=Visibility.Collapsed;}}
    }
}
