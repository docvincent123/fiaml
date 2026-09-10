using Microsoft.UI.Xaml;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.Web.WebView2.Core;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
namespace QureMed.Desktop;
public sealed class MainWindow : Window {
    readonly HttpClient http = new(new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(12) };
    readonly string folder = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "QureMed");
    WebView2? browser; Uri? origin; bool connecting; bool started; bool closing; bool uiReady; bool askingClose; bool allowClose; string? finishRequest; TaskCompletionSource<string?>? finishResult;
    public MainWindow() {
        BuildShell(); Title = "RehaFlow · QureMed Industries";
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1380, 900));
        (AppWindow.Presenter as OverlappedPresenter)?.Maximize();
        var icon=System.IO.Path.Combine(AppContext.BaseDirectory,"Assets","RehaFlow.ico");
        if(System.IO.File.Exists(icon))AppWindow.SetIcon(icon);
        AppWindow.Closing+=(_,args)=>{if(allowClose)return;args.Cancel=true;if(!askingClose)_=ConfirmClose();};
        try {
            var file = System.IO.Path.Combine(folder,"server.txt");
            Address.Text = Environment.GetEnvironmentVariable("QUREMED_SERVER_URL") ?? (System.IO.File.Exists(file) ? System.IO.File.ReadAllText(file) : "https://192.168.1.106");
        } catch { Address.Text = "https://192.168.1.106"; }
        Closed += (_, _) => { closing = true; browser?.Close(); http.Dispose(); };
        App.StartupLog("Connection shell ready");
    }
    // Build the small native connection surface directly; clinical screens are React.
    // This avoids a dependency on a separately deployed MainWindow XBF resource.
    readonly TextBox Address = new() { PlaceholderText = "https://192.168.1.106" };
    readonly Button ConnectButton = new() { Content = "Підключитися" };
    readonly Button RuntimeButton = new() { Content = "Встановити Microsoft Edge WebView2", Visibility = Visibility.Collapsed };
    readonly Grid BrowserHost = new();
    readonly Grid ConnectionScreen = new();
    readonly Border ConnectionToolbar = new();
    readonly TextBlock StatusText = new() { Text = "Вкажіть адресу локального сервера та підключіться.", FontSize = 16, TextWrapping = TextWrapping.Wrap };
    readonly ProgressBar Progress = new() { IsIndeterminate = true, Visibility = Visibility.Collapsed };
    static Microsoft.UI.Xaml.Media.SolidColorBrush Color(byte r,byte g,byte b) => new(Windows.UI.Color.FromArgb(255,r,g,b));
    void BuildShell() {
        var background=Color(11,16,26);var line=Color(37,48,68);var mint=Color(121,225,192);
        var root=new Grid { RequestedTheme=ElementTheme.Dark, Background=background };
        root.RowDefinitions.Add(new RowDefinition { Height=GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height=new GridLength(1,GridUnitType.Star) });
        root.RowDefinitions.Add(new RowDefinition { Height=GridLength.Auto });
        var toolbar=new Grid { ColumnSpacing=12 };
        foreach(var width in new[]{GridLength.Auto,new GridLength(1,GridUnitType.Star),GridLength.Auto,GridLength.Auto})toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width=width });
        toolbar.Children.Add(new TextBlock { Text="RehaFlow", FontSize=19, Foreground=mint, VerticalAlignment=VerticalAlignment.Center });
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(Address,"Адреса сервера");
        Address.KeyDown+=Address_KeyDown;Grid.SetColumn(Address,1);toolbar.Children.Add(Address);
        ConnectButton.Click+=Connect_Click;Grid.SetColumn(ConnectButton,2);toolbar.Children.Add(ConnectButton);
        var reload=new Button { Content="Оновити" };reload.Click+=Reload_Click;Grid.SetColumn(reload,3);toolbar.Children.Add(reload);
        ConnectionToolbar.Padding=new Thickness(18,10,18,10);ConnectionToolbar.BorderThickness=new Thickness(0,0,0,1);ConnectionToolbar.BorderBrush=line;ConnectionToolbar.Child=toolbar;root.Children.Add(ConnectionToolbar);
        Grid.SetRow(BrowserHost,1);root.Children.Add(BrowserHost);
        ConnectionScreen.Background=background;Grid.SetRow(ConnectionScreen,1);
        var panel=new StackPanel { MaxWidth=480, HorizontalAlignment=HorizontalAlignment.Center, VerticalAlignment=VerticalAlignment.Center, Spacing=20, Padding=new Thickness(24) };
        panel.Children.Add(new TextBlock { Text="QUREMED INDUSTRIES", Foreground=mint, FontSize=14 });
        panel.Children.Add(new TextBlock { Text="Ваш центр.\nЄдиний робочий простір.", FontSize=36, TextWrapping=TextWrapping.Wrap });
        StatusText.Foreground=Color(168,184,202);panel.Children.Add(StatusText);panel.Children.Add(Progress);
        RuntimeButton.Click+=Runtime_Click;panel.Children.Add(RuntimeButton);ConnectionScreen.Children.Add(panel);root.Children.Add(ConnectionScreen);
        root.Loaded+=Root_Loaded;Content=root;
    }
    async Task ConfirmClose() {
        askingClose=true;
        try {
            var dialog=new ContentDialog { XamlRoot=((FrameworkElement)Content).XamlRoot, RequestedTheme=ElementTheme.Dark, Title="Завершити зміну?", Content="Так — завершити роботу й вийти. Ні — закрити програму, залишивши зміну активною. Передача пацієнтів іншому лікарю виконується окремо.", PrimaryButtonText="Так", SecondaryButtonText="Ні", CloseButtonText="Скасувати" };
            var answer=await dialog.ShowAsync();
            if(answer==ContentDialogResult.None)return;
            if(answer==ContentDialogResult.Primary&&browser?.CoreWebView2!=null){
                if(!uiReady)throw new Exception("Немає зв’язку з інтерфейсом. Завершення зміни не підтверджено.");
                finishRequest=Guid.NewGuid().ToString();finishResult=new TaskCompletionSource<string?>();
                browser.CoreWebView2.PostWebMessageAsJson(System.Text.Json.JsonSerializer.Serialize(new {type="quremed.finish-work",requestId=finishRequest}));
                if(await Task.WhenAny(finishResult.Task,Task.Delay(25000))!=finishResult.Task)throw new Exception("Сервер не підтвердив завершення. Перевірте зв’язок та стан зміни.");
                var error=await finishResult.Task;if(error!=null)throw new Exception(error);
            }
            allowClose=true;Close();
        }catch(Exception ex){await new ContentDialog { XamlRoot=((FrameworkElement)Content).XamlRoot, RequestedTheme=ElementTheme.Dark, Title="Зміну не завершено", Content=ex.Message, CloseButtonText="Зрозуміло" }.ShowAsync();}
        finally {askingClose=false;finishRequest=null;finishResult=null;}
    }
    static Uri ValidateAddress(string value) {
        if (!Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri) || !string.IsNullOrEmpty(uri.UserInfo) || uri.AbsolutePath != "/" || uri.Query.Length > 0 || uri.Fragment.Length > 0)
            throw new Exception("Вкажіть адресу сервера без шляху, наприклад https://192.168.1.106");
        if (uri.Scheme != "https" && !(uri.Scheme == "http" && uri.IsLoopback))
            throw new Exception("Для мережі центру потрібна адреса https://. HTTP дозволено лише для localhost.");
        return new Uri(uri.GetLeftPart(UriPartial.Authority));
    }
    async void Root_Loaded(object sender,RoutedEventArgs e) {
        App.StartupLog("Native surface loaded");if(started)return;started=true;
        // CI or a previously configured installation can reconnect automatically.
        if(Environment.GetEnvironmentVariable("QUREMED_SERVER_URL")!=null || System.IO.File.Exists(System.IO.Path.Combine(folder,"server.txt"))){ConnectionToolbar.Visibility=Visibility.Collapsed;await Connect();}
    }
    async void Connect_Click(object sender,RoutedEventArgs e) => await Connect();
    async void Address_KeyDown(object sender,KeyRoutedEventArgs e) { if(e.Key==Windows.System.VirtualKey.Enter){e.Handled=true;await Connect();} }
    async void Reload_Click(object sender,RoutedEventArgs e) { if(connecting)return;if(browser?.CoreWebView2!=null&&origin!=null){uiReady=false;ConnectionScreen.Visibility=Visibility.Visible;StatusText.Text="Оновлюємо робочий простір…";browser.CoreWebView2.Reload();}else await Connect(); }
    async void Runtime_Click(object sender,RoutedEventArgs e) => await Windows.System.Launcher.LaunchUriAsync(new Uri("https://developer.microsoft.com/microsoft-edge/webview2/"));
    void Failure(string text) {if(closing)return;StatusText.Text=text;Progress.Visibility=Visibility.Collapsed;ConnectionScreen.Visibility=Visibility.Visible;ConnectionToolbar.Visibility=Visibility.Visible;}
    bool SameOrigin(string uri) => origin!=null && Uri.TryCreate(uri,UriKind.Absolute,out var target) && target.Scheme==origin.Scheme && target.Host==origin.Host && target.Port==origin.Port;
    async Task Connect() {
        if(connecting||closing)return;connecting=true;ConnectButton.IsEnabled=false;RuntimeButton.Visibility=Visibility.Collapsed;
        ConnectionScreen.Visibility=Visibility.Visible;Progress.Visibility=Visibility.Visible;StatusText.Text="Перевіряємо підключення до центру…";
        try {
            var target=ValidateAddress(Address.Text);App.StartupLog("Checking server health");
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
            App.StartupLog("Creating WebView2 environment");
            var environment=await CoreWebView2Environment.CreateWithOptionsAsync("",profile,new CoreWebView2EnvironmentOptions());
            App.StartupLog("Creating WebView2 control");browser=new WebView2();BrowserHost.Children.Add(browser);
            await browser.EnsureCoreWebView2Async(environment);App.StartupLog("WebView2 initialized");
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
            // Same-origin UI messages only; credentials never leave the web session.
            core.WebMessageReceived+=(_,args)=>{
                if(!SameOrigin(args.Source))return;
                try{var message=JsonNode.Parse(args.WebMessageAsJson);var type=message?["type"]?.ToString();
                    if(type=="quremed.server.settings"){ConnectionToolbar.Visibility=Visibility.Visible;return;}
                    if(type=="quremed.finish-result"&&message?["requestId"]?.ToString()==finishRequest){finishResult?.TrySetResult(message?["ok"]?.GetValue<bool>()==true?null:message?["error"]?.ToString()??"Не вдалося завершити зміну.");return;}
                    if(type!="quremed.ui.ready")return;
                    uiReady=true;ConnectionScreen.Visibility=Visibility.Collapsed;ConnectionToolbar.Visibility=Visibility.Collapsed;Progress.Visibility=Visibility.Collapsed;App.StartupLog("React UI ready");
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
