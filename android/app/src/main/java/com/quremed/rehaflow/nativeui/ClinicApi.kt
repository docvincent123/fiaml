package com.quremed.rehaflow.nativeui

import com.quremed.rehaflow.NativeSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

class ApiFailure(val status: Int, message: String) : Exception(message)
object ClinicApi {
    fun normalize(value: String): String {
        val raw = value.trim().let { if (it.contains("://")) it else "https://$it" }
        val u = URI(raw)
        require(u.scheme.equals("https", true) && u.host != null && u.userInfo == null &&
            u.query == null && u.fragment == null && (u.path.isNullOrEmpty() || u.path == "/") &&
            (u.port == -1 || u.port in 1..65535)) { "Вкажіть HTTPS-адресу ПК сервера без шляху та пароля." }
        return URI("https", null, u.host.lowercase(), if (u.port == 443) -1 else u.port, null, null, null).toString()
    }
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null,
                        server: String = NativeSession.server, bearer: String = NativeSession.token): Any = withContext(Dispatchers.IO) {
        val c = URL(server + "/api" + path).openConnection() as HttpsURLConnection
        // Use Android's system/user CA trust and hostname verification. Never bypass TLS.
        c.requestMethod = method; c.connectTimeout = 12000; c.readTimeout = 15000
        c.instanceFollowRedirects = false
        c.setRequestProperty("X-RehaFlow-API", "1")
        c.setRequestProperty("Content-Type", "application/json")
        if (bearer.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $bearer")
        try {
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val parsed = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
            if (code !in 200..299) throw ApiFailure(code, (parsed as? JSONObject)?.optString("message")?.takeIf { it.isNotBlank() }
                ?: "Сервер відхилив запит ($code).")
            parsed ?: throw ApiFailure(0, "Невідома відповідь сервера. Перевірте стан дії перед повтором.")
        } catch (e: SSLException) {
            throw ApiFailure(0, "Не вдалося перевірити сертифікат $server. Перевірте IP ПК, дату й час та актуальний QureMed-Local-CA.crt, установлений як CA у цьому профілі Android.")
        } catch (e: ApiFailure) { throw e
        } catch (e: java.io.IOException) {
            throw ApiFailure(0, if (method == "GET") "Немає зв’язку з сервером. Перевірте Wi-Fi і роботу ПК."
                else "Відповіді немає. Дію могло бути збережено: перевірте її стан перед повтором.")
        } finally { c.disconnect() }
    }
}
fun JSONObject.s(key: String): String = if (isNull(key)) "" else optString(key)
fun org.json.JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
