using Microsoft.UI.Xaml;
namespace QureMed.Desktop;
public partial class App : Application {
    private Window? window;
    public static void StartupLog(string message) {
        try {
            var folder = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "QureMed");
            System.IO.Directory.CreateDirectory(folder);
            System.IO.File.AppendAllText(System.IO.Path.Combine(folder,"startup.log"), DateTime.UtcNow.ToString("O") + " " + message + Environment.NewLine);
        } catch { }
    }
    public App() {
        // Unpackaged XAML resources must resolve from the installation directory,
        // including when started by a shortcut or a process in another directory.
        Environment.CurrentDirectory = AppContext.BaseDirectory;
        AppDomain.CurrentDomain.UnhandledException += (_, e) => StartupLog(e.ExceptionObject.ToString() ?? "Unhandled exception");
        UnhandledException += (_, e) => StartupLog(e.Message + " " + e.Exception.ToString());
        StartupLog("Initializing application resources");
        InitializeComponent();
        StartupLog("Application resources ready");
    }
    protected override void OnLaunched(LaunchActivatedEventArgs args) {
        try { StartupLog("Creating window"); window = new MainWindow(); StartupLog("Activating window"); window.Activate(); StartupLog("Window activated"); }
        catch (Exception ex) { StartupLog(ex.ToString()); throw; }
    }
}
