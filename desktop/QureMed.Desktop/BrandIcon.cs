using Microsoft.UI.Windowing;
namespace QureMed.Desktop;

internal static class BrandIcon {
    private const string IconBase64 = "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";

    public static void Apply(AppWindow appWindow, string folder) {
        try {
            Directory.CreateDirectory(folder);
            var path = Path.Combine(folder, "RehaFlow.ico");
            var bytes = Convert.FromBase64String(IconBase64);
            if (!File.Exists(path) || !File.ReadAllBytes(path).AsSpan().SequenceEqual(bytes)) File.WriteAllBytes(path, bytes);
            appWindow.SetIcon(path);
        } catch (Exception ex) {
            App.StartupLog("Brand icon: " + ex.Message);
        }
    }
}
