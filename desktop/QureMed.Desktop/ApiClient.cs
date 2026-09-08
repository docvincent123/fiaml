using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json.Nodes;
namespace QureMed.Desktop;
public sealed class ApiClient {
    readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(20) };
    public string BaseUrl { get; private set; } = "http://localhost:3000";
    public string Token { get; set; } = "";
    public event Action? SessionEnded;
    public void Configure(string url) {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https")) throw new Exception("Вкажіть адресу http:// або https://");
        BaseUrl = url.TrimEnd('/');
    }
    public async Task<JsonNode> Send(string path, string method = "GET", object? body = null) {
        using var req = new HttpRequestMessage(new HttpMethod(method), BaseUrl + "/api" + path);
        if (Token.Length > 0) req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", Token);
        if (body != null) req.Content = JsonContent.Create(body);
        using var response = await http.SendAsync(req);
        var text = await response.Content.ReadAsStringAsync();
        JsonNode? json; try { json = JsonNode.Parse(text); } catch { throw new Exception("Сервер повернув неочікувану відповідь. Перевірте адресу API."); }
        if (!response.IsSuccessStatusCode) {
            if (response.StatusCode == HttpStatusCode.Unauthorized && path != "/auth/login") { Token = ""; SessionEnded?.Invoke(); }
            throw new Exception(json?["message"]?.ToString() ?? "Помилка сервера");
        }
        return json ?? new JsonObject();
    }
    public async Task<string> Qr(string uid) {
        using var req = new HttpRequestMessage(HttpMethod.Get, BaseUrl + "/api/beds/qr/" + uid + "/image");
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", Token);
        using var res = await http.SendAsync(req); res.EnsureSuccessStatusCode(); return await res.Content.ReadAsStringAsync();
    }
}
