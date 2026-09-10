using Microsoft.UI.Xaml;
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
    WebView2? browser; Uri? origin; bool connecting; bool started; bool closing; bool uiReady; bool allowClose; bool closePromptOpen; bool hasSavedServer;
    public MainWindow() {
        BuildShell(); Title = "RehaFlow · QureMed Industries";
        try { if(AppWindow.Presenter is Microsoft.UI.Windowing.OverlappedPresenter presenter) presenter.Maximize(); }
        catch { AppWindow.Resize(new Windows.Graphics.SizeInt32(1380, 900)); }
        try {
            var file = System.IO.Path.Combine(folder,"server.txt");
            var env = Environment.GetEnvironmentVariable("QUREMED_SERVER_URL");
            hasSavedServer = !string.IsNullOrWhiteSpace(env) || System.IO.File.Exists(file);
            Address.Text = env ?? (System.IO.File.Exists(file) ? System.IO.File.ReadAllText(file) : "https://192.168.1.106");
        } catch { Address.Text = "https://192.168.1.106"; }
        ShellToolbar.Visibility = hasSavedServer ? Visibility.Collapsed : Visibility.Visible;
        ChangeServerButton.Visibility = hasSavedServer ? Visibility.Visible : Visibility.Collapsed;
        AppWindow.Closing += AppWindow_Closing;
        Closed += (_, _) => { closing = true; browser?.Close(); http.Dispose(); };
        try {
            var icon = System.IO.Path.Combine(AppContext.BaseDirectory,"Assets","RehaFlow.ico");
            if(System.IO.File.Exists(icon)) AppWindow.SetIcon(icon);
        } catch { }
        App.StartupLog("Connection shell ready");
    }
    readonly TextBox Address = new() { PlaceholderText = "https://192.168.1.106" };
    readonly Button ConnectButton = new() { Content = "Підключитися" };
    readonly Button RuntimeButton = new() { Content = "Встановити Microsoft Edge WebView2", Visibility = Visibility.Collapsed };
    readonly Button ChangeServerButton = new() { Content = "Змінити адресу сервера", Visibility = Visibility.Collapsed };
    readonly Grid BrowserHost = new();
    readonly Grid ConnectionScreen = new();
    readonly Border ShellFooter = new();
    readonly Border ShellToolbar = new();
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
        ShellToolbar.Padding=new Thickness(18,10,18,10);ShellToolbar.BorderThickness=new Thickness(0,0,0,1);ShellToolbar.BorderBrush=line;ShellToolbar.Child=toolbar;root.Children.Add(ShellToolbar);
        Grid.SetRow(BrowserHost,1);root.Children.Add(BrowserHost);
        ConnectionScreen.Background=background;Grid.SetRow(ConnectionScreen,1);
        var panel=new StackPanel { MaxWidth=480, HorizontalAlignment=HorizontalAlignment.Center, VerticalAlignment=VerticalAlignment.Center, Spacing=20, Padding=new Thickness(24) };
        panel.Children.Add(new TextBlock { Text="QUREMED INDUSTRIES", Foreground=mint, FontSize=14 });
        panel.Children.Add(new TextBlock { Text="Ваш центр.\nЄдиний робочий простір.", FontSize=36, TextWrapping=TextWrapping.Wrap });
        StatusText.Foreground=Color(168,184,202);panel.Children.Add(StatusText);panel.Children.Add(Progress);
        RuntimeButton.Click+=Runtime_Click;panel.Children.Add(RuntimeButton);
        ChangeServerButton.Click+=ChangeServer_Click;panel.Children.Add(ChangeServerButton);
        ConnectionScreen.Children.Add(panel);root.Children.Add(ConnectionScreen);
        ShellFooter.BorderBrush=line;ShellFooter.BorderThickness=new Thickness(0,1,0,0);ShellFooter.Padding=new Thickness(18,10,18,10);
        ShellFooter.Child=new TextBlock { Text="QureMed Industries", Foreground=Color(148,162,184), FontSize=12 };
        Grid.SetRow(ShellFooter,2);root.Children.Add(ShellFooter);root.Loaded+=Root_Loaded;Content=root;
    }
    static Uri ValidateAddress(string value) {
        if (!Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri) || !string.IsNullOrEmpty(uri.UserInfo) || uri.AbsolutePath != "/" || uri.Query.Length > 0 || uri.Fragment.Length > 0)
            throw new Exception("Вкажіть адресу сервера без шляху, наприклад https://192.168.1.106");
        if (uri.Scheme != "https" && !(uri.Scheme == "http" && uri.IsLoopback))
            throw new Exception("Для мережі центру потрібна адреса https://. HTTP дозволено лише для localhost.");
        return new Uri(uri.GetLeftPart(UriPartial.Authority));
    }
    async void AppWindow_Closing(Microsoft.UI.Windowing.AppWindow sender, Microsoft.UI.Windowing.AppWindowClosingEventArgs args) {
        if(allowClose)return;
        args.Cancel=true;
        if(closePromptOpen||closing)return;
        if(Content is not FrameworkElement root || root.XamlRoot is null)return;
        closePromptOpen=true;
        try {
            var dialog=new ContentDialog {
                XamlRoot=root.XamlRoot,
                Title="Завершити зміну?",
                Content="Закрити RehaFlow на цьому комп’ютері?",
                PrimaryButtonText="Так",
                CloseButtonText="Ні",
                DefaultButton=ContentDialogButton.Close
            };
            if(await dialog.ShowAsync()==ContentDialogResult.Primary){allowClose=true;Close();}
        } finally { closePromptOpen=false; }
    }
    async void Root_Loaded(object sender,RoutedEventArgs e) {
        App.StartupLog("Native surface loaded");if(started)return;started=true;
        if(hasSavedServer)await Connect();
    }
    async void Connect_Click(object sender,RoutedEventArgs e) => await Connect();
    async void Address_KeyDown(object sender,KeyRoutedEventArgs e) { if(e.Key==Windows.System.VirtualKey.Enter){e.Handled=true;await Connect();} }
    async void Reload_Click(object sender,RoutedEventArgs e) { if(connecting)return;if(browser?.CoreWebView2!=null&&origin!=null){uiReady=false;ConnectionScreen.Visibility=Visibility.Visible;StatusText.Text="Оновлюємо робочий простір…";browser.CoreWebView2.Reload();}else await Connect(); }
    async void Runtime_Click(object sender,RoutedEventArgs e) => await Windows.System.Launcher.LaunchUriAsync(new Uri("https://developer.microsoft.com/microsoft-edge/webview2/"));
    void ChangeServer_Click(object sender,RoutedEventArgs e)=>ShowServerSetup();
    void ShowServerSetup(){
        if(closing)return;uiReady=false;ConnectionScreen.Visibility=Visibility.Visible;ShellToolbar.Visibility=Visibility.Visible;ShellFooter.Visibility=Visibility.Collapsed;ChangeServerButton.Visibility=Visibility.Collapsed;Progress.Visibility=Visibility.Collapsed;StatusText.Text="Вкажіть нову адресу локального сервера.";Address.Focus(FocusState.Programmatic);
    }
    void Failure(string text) {if(closing)return;StatusText.Text=text;Progress.Visibility=Visibility.Collapsed;ConnectionScreen.Visibility=Visibility.Visible;ShellFooter.Visibility=Visibility.Collapsed;ChangeServerButton.Visibility=hasSavedServer?Visibility.Visible:Visibility.Collapsed;}
    bool SameOrigin(string uri) => origin!=null && Uri.TryCreate(uri,UriKind.Absolute,out var target) && target.Scheme==origin.Scheme && target.Host==origin.Host && target.Port==origin.Port;
    async Task Connect() {
        if(connecting||closing)return;connecting=true;ConnectButton.IsEnabled=false;RuntimeButton.Visibility=Visibility.Collapsed;
        ConnectionScreen.Visibility=Visibility.Visible;ShellFooter.Visibility=Visibility.Collapsed;Progress.Visibility=Visibility.Visible;StatusText.Text="Перевіряємо підключення до центру…";
        try {
            var target=ValidateAddress(Address.Text);App.StartupLog("Checking server health");
            using var response=await http.GetAsync(new Uri(target,"/api/health"));
            if(!response.IsSuccessStatusCode)throw new Exception("Сервер не готовий. Перевірте запуск Start-QureMed.cmd і адресу.");
            var health=JsonNode.Parse(await response.Content.ReadAsStringAsync());
            if(health?["brand"]?.ToString()!="QureMed Industries"||health?["status"]?.ToString()!="ok")throw new Exception("За цією адресою немає сервера RehaFlow.");
            if(closing)return;
            StatusText.Text="Відкриваємо ваш робочий простір…";
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
            core.WebMessageReceived+=(_,args)=>{
                if(!SameOrigin(args.Source))return;
                try{
                    var message=JsonNode.Parse(args.WebMessageAsJson);var type=message?["type"]?.ToString();
                    if(type=="quremed.server.change"){ShowServerSetup();return;}
                    if(type!="quremed.ui.ready")return;
                    uiReady=true;hasSavedServer=true;ConnectionScreen.Visibility=Visibility.Collapsed;ShellToolbar.Visibility=Visibility.Collapsed;ShellFooter.Visibility=Visibility.Collapsed;ChangeServerButton.Visibility=Visibility.Collapsed;Progress.Visibility=Visibility.Collapsed;App.StartupLog("React UI ready");
                }catch{ }
            };
            System.IO.Directory.CreateDirectory(folder);
            System.IO.File.WriteAllText(System.IO.Path.Combine(folder,"server.txt"),target.GetLeftPart(UriPartial.Authority));
            hasSavedServer=true;
            core.Navigate(target.AbsoluteUri);
        } catch(HttpRequestException){Failure("Немає підключення. Перевірте адресу, мережу центру та довіру до QureMed-Local-CA.crt.");}
          catch(TaskCanceledException){Failure("Сервер не відповідає. Перевірте, чи запущено локальний сервер.");}
          catch(Exception ex){App.StartupLog("Connection initialization: "+ex);Failure(ex.Message);if(ex.GetType().Name.Contains("WebView2Runtime"))RuntimeButton.Visibility=Visibility.Visible;}
        finally{connecting=false;if(!closing){ConnectButton.IsEnabled=true;Progress.Visibility=Visibility.Collapsed;}}
    }
}
