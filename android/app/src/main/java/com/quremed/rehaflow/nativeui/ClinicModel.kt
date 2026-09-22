package com.quremed.rehaflow.nativeui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quremed.rehaflow.NativeSession
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ClinicModel : ViewModel() {
    var user by mutableStateOf<JSONObject?>(null); private set
    var page by mutableStateOf("home")
    var data by mutableStateOf<Any?>(null); private set
    var patient by mutableStateOf<JSONObject?>(null); private set
    var error by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var search by mutableStateOf("")
    var offset by mutableIntStateOf(0)
    var changed by mutableIntStateOf(0)
    var synchronizedAt by mutableStateOf(""); private set
    var uncertain by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    val workspaceAllowed: Boolean get() = user?.s("role") == "ADMIN" || user?.optBoolean("onShift") == true
    fun can(permission: String) = user?.optJSONArray("permissions")?.let { a -> (0 until a.length()).any { a.optString(it) == permission } } == true
    fun fail(e: Exception) {
        error = e.message ?: "Не вдалося виконати дію."
        if (e is ApiFailure && e.status == 401) { NativeSession.clear(); user = null; data = null; patient = null }
    }
    fun checkedUncertain() { if(runCatching{DraftStore.read("pending")}.getOrNull()!=null){retryPending();return};uncertain = false; error = "" }
    fun connect(address: String, done: (String) -> Unit) {
        if (busy) return
        busy = true; error = ""
        viewModelScope.launch {
            try {
                val server = ClinicApi.normalize(address)
                val health = ClinicApi.request("/health", server = server, bearer = "") as JSONObject
                require(health.optInt("apiMajor") == 1 && health.s("brand") == "QureMed Industries") { "Це не сумісний сервер RehaFlow." }
                NativeSession.server = server; done(server)
            } catch (e: Exception) { fail(e) } finally { busy = false }
        }
    }
    fun login(login: String, password: String, device: String, done: () -> Unit) {
        if (busy) return
        busy = true; error = ""
        viewModelScope.launch {
            try {
                val result = ClinicApi.request("/auth/login", "POST", JSONObject().put("login", login.trim()).put("password", password).put("device", device).put("requestShift", false), bearer = "") as JSONObject
                NativeSession.token = result.getString("token")
                user = ClinicApi.request("/auth/me") as JSONObject; NativeSession.userId = user!!.getString("id"); uncertain=DraftStore.read("pending")!=null
                changed++; done()
            } catch (e: Exception) { fail(e) } finally { busy = false }
        }
    }
    fun restore(done:()->Unit){
        if(NativeSession.token.isEmpty()||busy)return
        busy=true
        viewModelScope.launch{try{user=ClinicApi.request("/auth/me") as JSONObject;NativeSession.userId=user!!.getString("id");uncertain=DraftStore.read("pending")!=null;changed++;done()}catch(e:Exception){fail(e)}finally{busy=false}}
    }
    private fun todayAppointmentsPath(): String {
        val start=java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault())
        return "/appointments?mine=true&from="+start.toInstant().toString()+"&to="+start.plusDays(1).toInstant().toString()
    }
    fun select(next: String) { page = next; data = null; patient = null; error = ""; search = ""; offset = 0; changed++ }
    suspend fun refresh() {
        if (loading || busy || user == null) return
        if (NativeSession.token.isEmpty()) { user = null; data = null; patient = null; return }
        val selected = page; val auth = NativeSession.token; val selectedPatient = patient?.s("id")
        loading = true
        try {
            // Heartbeat also detects revoked permissions/sessions and updates shift status.
            val me = ClinicApi.request("/auth/me") as JSONObject
            if (auth != NativeSession.token) return
            user = me
            if (!workspaceAllowed) { data = null; patient = null; return }
            val path = when (selected) {
                "home" -> "/operations"
                "patients" -> "/patients?status=ACTIVE&offset=$offset&q=" + java.net.URLEncoder.encode(search, "UTF-8")
                "tasks" -> "/tasks?scope=shift&offset=$offset&q="+java.net.URLEncoder.encode(search,"UTF-8")
                "messages" -> "/care/messages"
                "rooms" -> "/rooms"
                "schedule" -> todayAppointmentsPath()
                "handovers" -> "/care/handovers"
                "users" -> "/users"
                "sessions" -> "/sessions"
                "audit" -> "/audit"
                "approvals" -> "/shift/requests"
                else -> null
            }
            val next = if (path == null) null else ClinicApi.request(path)
            val detail = if (selectedPatient != null) ClinicApi.request("/patients/$selectedPatient") as JSONObject else null
            if (selected == page && auth == NativeSession.token) {
                data = next
                if (patient?.s("id") == selectedPatient) patient = detail
                if (!uncertain) error = ""
                synchronizedAt = java.time.LocalTime.now().withNano(0).toString()
            }
        } catch (e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; if (auth == NativeSession.token && selected == page) fail(e) } finally { loading = false }
    }
    fun openPatient(id: String) {
        if (!workspaceAllowed) return
        val selectedPage = page
        viewModelScope.launch {
            try { val auth = NativeSession.token; val detail = ClinicApi.request("/patients/$id") as JSONObject; if(auth == NativeSession.token && workspaceAllowed && page == selectedPage) patient = detail } catch (e: Exception) { fail(e) }
        }
    }
    fun closePatient() { patient = null }
    fun write(path: String, body: JSONObject = JSONObject(), method: String = "POST", draftScope:String?=null, done: () -> Unit = {}) {
        if (busy || uncertain) return
        busy = true; error = ""; message = ""
        viewModelScope.launch {
            try {
                val safe=method=="POST"&&(path=="/tasks"||path=="/patients"||path=="/appointments"||path.matches(Regex("/patients/[0-9a-fA-F-]+/(entries|readmit)")))
                if(safe){if(!body.has("request_id"))body.put("request_id",java.util.UUID.randomUUID().toString());DraftStore.save("pending",JSONObject().put("path",path).put("method",method).put("body",body).put("draft",draftScope?:""))}
                ClinicApi.request(path, method, body)
                if(safe)DraftStore.remove("pending");if(draftScope!=null)DraftStore.remove(draftScope)
                changed++; message = "Збережено"; done()
            }
            catch (e: Exception) { if (e is ApiFailure && (e.status == 0||e.status>=500)) uncertain = true else if(e is ApiFailure){runCatching{DraftStore.remove("pending")}}; fail(e) }
            finally { busy = false }
        }
    }
    fun retryPending(){
        if(busy)return
        val operation=try{DraftStore.read("pending")}catch(e:Exception){fail(e);return}?:run{error="Перевірте стан дії у картці перед повтором.";uncertain=false;return}
        busy=true
        viewModelScope.launch{try{
            ClinicApi.request(operation.getString("path"),operation.getString("method"),operation.getJSONObject("body"))
            DraftStore.remove("pending");operation.s("draft").takeIf{it.isNotEmpty()}?.let{DraftStore.remove(it)}
            uncertain=false;error="";message="Збереження підтверджено без повторного запису";select("home");changed++
        }catch(e:Exception){if(e is ApiFailure&&e.status in 400..499&&e.status!=401){DraftStore.remove("pending");uncertain=false};fail(e)}finally{busy=false}}
    }
    fun clear() { NativeSession.clear(); user = null; data = null; patient = null; uncertain = false; error = "" }
}
