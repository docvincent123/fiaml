package com.quremed.rehaflow.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** All routes here use Compose and the authenticated API; no browser or WebView. */
@Composable fun NativeSection(m:ClinicModel,open:(String)->Unit){
    val rows=(m.data as? JSONArray)?.objects() ?: emptyList()
    val titles=mapOf("rooms" to "Палати та ліжка","schedule" to "Розклад процедур","archive" to "Архів пацієнтів","handovers" to "Передача пацієнтів","users" to "Команда","sessions" to "Сесії","audit" to "Журнал дій","approvals" to "Початок зміни","registration" to "Реєстрація","compose" to "Нове повідомлення")
    LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)){
        item{Text(titles[m.page] ?: "Розділ",style=MaterialTheme.typography.headlineSmall);TextButton(onClick={m.select("home")}){Text("До огляду")}}
        when(m.page){
            "registration"->item{Registration(m)}
            "compose"->item{MessageComposer(m)}
            "schedule"->item{if(m.can("appointments.manage"))AppointmentForm(m)}
            "handovers"->item{HandoverForm(m)}
        }
        if(rows.isEmpty()&&m.page !in listOf("registration","compose"))item{Text(if(m.loading)"Завантажуємо…" else "Записів поки немає")}
        items(rows,key={it.s("id")}){r->
            Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                when(m.page){
                    "rooms"->{Text("Палата "+r.s("room_number"),style=MaterialTheme.typography.titleLarge);Text("Поверх: "+r.s("floor"))
                        r.optJSONArray("beds")?.objects()?.forEach{b->
                            OutlinedButton(enabled=b.s("patient_id").isNotEmpty()&&m.can("patients.read"),onClick={open("/patients?patient="+b.s("patient_id"))},modifier=Modifier.fillMaxWidth()){Text("Ліжко "+b.s("bed_number")+" · "+b.s("patient_name").ifEmpty{"Вільне"})}
                        }}
                    "schedule"->{Text(r.s("patient_name"),style=MaterialTheme.typography.titleLarge);Text(r.s("cabinet_name")+" · "+r.s("staff_name"));Text(displayTime(r.s("starts_at"))+" — "+displayTime(r.s("ends_at")));Text(mapOf("BOOKED" to "Записано","COMPLETED" to "Виконано","CANCELLED" to "Скасовано")[r.s("status")] ?: r.s("status"))
                        if(m.can("patients.read"))TextButton(onClick={open("/patients?patient="+r.s("patient_id"))}){Text("Картка пацієнта")}
                    }
                    "archive"->{Text(r.s("name"),style=MaterialTheme.typography.titleLarge);Text("№ "+r.s("patient_number")+" · "+r.s("birth_date").take(10));TextButton(onClick={open("/patients?patient="+r.s("id"))}){Text("Історія пацієнта")}}
                    "handovers"->{Text(r.s("patient_name"),style=MaterialTheme.typography.titleLarge);Text(r.s("from_name")+" → "+r.s("to_name"));Text(r.s("summary"))
                        if(r.s("status")=="PENDING"&&r.s("to_id")==m.user?.s("id"))Button(enabled=!m.busy&&!m.uncertain,onClick={m.write("/care/handovers/"+r.s("id")+"/accept")}){Text("Прийняти пацієнта")}
                    }
                    "approvals"->{Text(r.s("name"));Text(r.s("role_label").ifEmpty{r.s("role")});Row{
                        Button(enabled=!m.busy,onClick={m.write("/shift/requests/"+r.s("id")+"/approve")}){Text("Підтвердити")}
                        TextButton(enabled=!m.busy,onClick={m.write("/shift/requests/"+r.s("id")+"/reject")}){Text("Відхилити")}
                    }}
                    "users"->{Text(r.s("name"),style=MaterialTheme.typography.titleLarge);Text(r.s("role_label").ifEmpty{r.s("role")});Text(r.s("specialty"));Text(if(r.optBoolean("active"))"Активний" else "Вимкнений")}
                    "sessions"->{Text(r.s("name"));Text(r.s("device"));Text("Останній зв’язок: "+displayTime(r.s("last_seen_at")));TextButton(enabled=!m.busy,onClick={m.write("/sessions/"+r.s("id"),method="DELETE")}){Text("Відкликати сесію")}}
                    "audit"->{Text(r.s("action"));Text(r.s("actor_name").ifEmpty{r.s("name")});Text(displayTime(r.s("created_at")))}
                }
            }}
        }
        if(m.page=="archive")item{Row{TextButton(enabled=m.offset>0,onClick={m.offset=(m.offset-100).coerceAtLeast(0);m.changed++}){Text("Назад")};TextButton(enabled=rows.size==100,onClick={m.offset+=100;m.changed++}){Text("Далі")}}}
    }
}
private fun displayTime(v:String)=runCatching{java.time.OffsetDateTime.parse(v).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))}.getOrDefault(v)

@Composable private fun Picker(label:String,path:String,value:String,change:(String)->Unit,filter:(JSONObject)->Boolean={true}){
    var rows by remember(path){mutableStateOf(emptyList<JSONObject>())};var error by remember(path){mutableStateOf("")};var expanded by remember{mutableStateOf(false)}
    LaunchedEffect(path){try{rows=(ClinicApi.request(path) as JSONArray).objects().filter(filter)}catch(e:Exception){error=e.message ?: "Помилка"}}
    Column{
        OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text(label+": "+(rows.find{it.s("id")==value}?.let{it.s("name").ifEmpty{it.s("patient_name")}} ?: "Оберіть"))}
        DropdownMenu(expanded,{expanded=false}){rows.forEach{r->DropdownMenuItem(text={Text(r.s("name").ifEmpty{r.s("patient_name")}+if(r.has("patient_count"))" · пацієнтів: "+r.s("patient_count") else "")},onClick={change(r.s("id"));expanded=false})}}
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
    }
}
@Composable fun NativePassword(m:ClinicModel){
    var show by remember{mutableStateOf(false)};var old by remember{mutableStateOf("")};var next by remember{mutableStateOf("")}
    OutlinedButton(onClick={show=true}){Text("Змінити пароль")}
    if(show)AlertDialog(onDismissRequest={show=false},title={Text("Змінити пароль")},text={Column{
        OutlinedTextField(old,{old=it},label={Text("Поточний пароль")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
        OutlinedTextField(next,{next=it},label={Text("Новий пароль")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
        if(m.error.isNotEmpty())Text(m.error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(enabled=!m.busy&&next.length>=(if(m.user?.s("role")=="ADMIN")12 else 1),onClick={m.write("/auth/password",JSONObject().put("currentPassword",old).put("newPassword",next)){m.clear();show=false}}){Text("Зберегти й вийти")}},dismissButton={TextButton(onClick={show=false}){Text("Скасувати")}})
}
@Composable private fun MessageComposer(m:ClinicModel){
    var recipient by remember{mutableStateOf("")};var body by remember{mutableStateOf("")};var urgent by remember{mutableStateOf(false)}
    if(!m.can("messages.send"))return
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Picker("Працівник","/users",recipient,{recipient=it},{it.optBoolean("active")})
        OutlinedTextField(body,{body=it.take(20000)},label={Text("Повідомлення")},minLines=3,modifier=Modifier.fillMaxWidth())
        Row{Checkbox(urgent,{urgent=it});Text("Терміново")}
        Button(enabled=!m.busy&&!m.uncertain&&recipient.isNotEmpty()&&body.isNotBlank(),onClick={m.write("/care/messages",JSONObject().put("recipient_id",recipient).put("body",body).put("urgent",urgent)){m.select("messages")}}){Text("Надіслати")}
    }
}
@Composable private fun AppointmentForm(m:ClinicModel){
    var expanded by remember{mutableStateOf(false)};var patient by remember{mutableStateOf("")};var cabinet by remember{mutableStateOf("")};var staff by remember{mutableStateOf("")}
    var start by remember{mutableStateOf(java.time.LocalDateTime.now().withSecond(0).withNano(0).toString())};var duration by remember{mutableStateOf("30")}
    OutlinedButton(onClick={expanded=!expanded}){Text("Записати на процедуру")}
    if(expanded)Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Picker("Пацієнт","/patients?status=ACTIVE",patient,{patient=it});Picker("Кабінет","/cabinets",cabinet,{cabinet=it});Picker("Спеціаліст","/staff",staff,{staff=it})
        OutlinedTextField(start,{start=it},label={Text("Дата й час: 2026-09-19T14:30")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(duration,{duration=it.filter(Char::isDigit).take(3)},label={Text("Тривалість, хвилин")})
        val at=runCatching{java.time.LocalDateTime.parse(start).atZone(java.time.ZoneId.systemDefault())}.getOrNull()
        Button(enabled=!m.busy&&!m.uncertain&&patient.isNotEmpty()&&cabinet.isNotEmpty()&&at!=null&&(duration.toIntOrNull() ?: 0)>0,onClick={
            m.write("/appointments",JSONObject().put("patient_id",patient).put("cabinet_id",cabinet).put("staff_id",staff.ifEmpty{null}).put("starts_at",at!!.toInstant().toString()).put("ends_at",at.plusMinutes(duration.toLong()).toInstant().toString())){expanded=false}
        }){Text("Записати")}
    }
}
@Composable private fun Registration(m:ClinicModel){
    val values=remember{mutableStateMapOf<String,String>()}
    var doctor by remember{mutableStateOf("")};var duplicate by remember{mutableStateOf(false)}
    if(!m.can("patients.manage"))return
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        listOf("name" to "ПІБ *","birth_date" to "Дата народження: РРРР-ММ-ДД *","phone" to "Телефон","address" to "Адреса","emergency_contact" to "Контакт близької людини","complaints" to "Скарги, причина звернення *","referral" to "Направлення").forEach{(key,label)->OutlinedTextField(values[key] ?: "",{values[key]=it.take(1000)},label={Text(label)},modifier=Modifier.fillMaxWidth())}
        Picker("Лікуючий лікар","/staff",doctor,{doctor=it},{it.s("role")=="DOCTOR"})
        Text("Реєстрація без розміщення у ліжку. Розміщення можна оформити з ПК.")
        Row{Checkbox(duplicate,{duplicate=it});Text("Перевірено: це інша людина, навіть якщо ПІБ і дата народження збігаються")}
        Button(enabled=!m.busy&&!m.uncertain&&!values["name"].isNullOrBlank()&&!values["complaints"].isNullOrBlank()&&runCatching{java.time.LocalDate.parse(values["birth_date"] ?: "")}.isSuccess,onClick={val b=JSONObject();values.forEach{(k,v)->b.put(k,v)};b.put("doctor_id",doctor.ifEmpty{null}).put("duplicate_ack",duplicate);m.write("/patients",b){m.select("patients")}}){Text("Зареєструвати")}
    }
}
@Composable private fun HandoverForm(m:ClinicModel){
    var patient by remember{mutableStateOf("")};var doctor by remember{mutableStateOf("")};var summary by remember{mutableStateOf("")};var show by remember{mutableStateOf(false)}
    OutlinedButton(onClick={show=!show}){Text("Передати пацієнта")}
    if(show)Column{
        Picker("Мій пацієнт","/patients?status=ACTIVE",patient,{patient=it},{it.s("doctor_id")==m.user?.s("id")})
        Picker("Новий лікар","/staff",doctor,{doctor=it},{it.s("role")=="DOCTOR"&&it.s("id")!=m.user?.s("id")})
        OutlinedTextField(summary,{summary=it.take(20000)},label={Text("Підсумок лікування та наступні дії")})
        Button(enabled=!m.busy&&!m.uncertain&&patient.isNotEmpty()&&doctor.isNotEmpty()&&summary.isNotBlank(),onClick={m.write("/care/handovers",JSONObject().put("patient_ids",JSONArray().put(patient)).put("to_id",doctor).put("summary",summary)){show=false}}){Text("Передати")}
    }
}
@Composable fun NativeShiftHandover(m:ClinicModel){
    var show by remember{mutableStateOf(false)};var recipient by remember{mutableStateOf("")};var summary by remember{mutableStateOf("")};var rows by remember{mutableStateOf(emptyList<JSONObject>())}
    LaunchedEffect(m.changed){try{rows=(ClinicApi.request("/care/team-handovers") as JSONArray).objects()}catch(e:Exception){m.fail(e)}}
    OutlinedButton(onClick={show=!show}){Text("Передача робочої зміни")}
    if(show)Column{
        Picker("Колега","/staff",recipient,{recipient=it},{it.s("role")==m.user?.s("role")&&it.s("id")!=m.user?.s("id")})
        OutlinedTextField(summary,{summary=it.take(20000)},label={Text("Підсумок зміни")})
        Button(enabled=!m.busy&&!m.uncertain&&recipient.isNotEmpty()&&summary.isNotBlank(),onClick={m.write("/care/team-handovers",JSONObject().put("recipient_id",recipient).put("summary",summary)){show=false}}){Text("Передати")}
        rows.filter{it.s("recipient_id")==m.user?.s("id")&&it.s("accepted_at").isEmpty()}.forEach{r->Text(r.s("author")+": "+r.s("summary"));Button(enabled=!m.busy,onClick={m.write("/care/team-handovers/"+r.s("id")+"/accept")}){Text("Прийняти зміну")}}
    }
}
@Composable fun NativeClinicalActions(m:ClinicModel,p:JSONObject){
    var kind by remember{mutableStateOf("")};var body by remember{mutableStateOf("")}
    val fields=remember{mutableStateMapOf<String,String>()}
    Column{
        if(m.can("observations.write"))OutlinedButton(onClick={kind="OBSERVATION"}){Text("Додати спостереження")}
        if(m.can("rehab.write"))OutlinedButton(onClick={kind="REHAB"}){Text("Реабілітаційний запис")}
        p.optJSONArray("notes")?.objects()?.forEach{Text(it.s("author")+" · "+displayTime(it.s("created_at")));Text(it.s("body"))}
    }
    if(kind.isNotEmpty())AlertDialog(onDismissRequest={kind=""},title={Text("Новий запис")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        OutlinedTextField(body,{body=it.take(20000)},minLines=4,label={Text("Перевірені дані та результати")})
        if(kind=="REHAB")listOf("goals" to "Цілі","assessment" to "Оцінка","result" to "Результат","next_plan" to "Наступний план").forEach{(key,label)->OutlinedTextField(fields[key] ?: "",{fields[key]=it.take(4000)},label={Text(label)})}
        if(m.error.isNotEmpty())Text(m.error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(enabled=!m.busy&&!m.uncertain&&body.isNotBlank(),onClick={
        val data=JSONObject()
        if(kind=="OBSERVATION")data.put("observed_at",java.time.Instant.now().toString())
        if(kind=="REHAB")listOf("goals","assessment","result","next_plan").forEach{data.put(it,fields[it] ?: "")}
        m.write("/patients/"+p.s("id")+"/entries",JSONObject().put("kind",kind).put("body",body).put("data",data)){kind="";body="";fields.clear();m.openPatient(p.s("id"))}
    }){Text("Зберегти")}},dismissButton={TextButton(onClick={kind=""}){Text("Скасувати")}})
}
