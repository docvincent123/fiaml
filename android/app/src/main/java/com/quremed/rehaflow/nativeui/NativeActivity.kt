package com.quremed.rehaflow.nativeui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.quremed.rehaflow.MainActivity
import com.quremed.rehaflow.NativeSession
import com.quremed.rehaflow.R
import com.quremed.rehaflow.ShiftAlertsService
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private val palette = darkColorScheme(primary=Color(0xFF7EE3CD), onPrimary=Color(0xFF073B33),
    background=Color(0xFF0A1119),surface=Color(0xFF111E2A),surfaceVariant=Color(0xFF1C2C3B),
    onBackground=Color(0xFFE4EFF5),onSurface=Color(0xFFE4EFF5),onSurfaceVariant=Color(0xFFABBFCE))
private val roles=mapOf("ADMIN" to "Адміністратор","REGISTRAR" to "Реєстратор","DOCTOR" to "Лікар","NURSE" to "Медсестра","THERAPIST" to "Реабілітолог")
private data class Destination(val key:String,val title:String,val icon:ImageVector,val permission:String="")
private val destinations=listOf(Destination("home","Огляд",Icons.Outlined.Dashboard,"dashboard"),Destination("patients","Пацієнти",Icons.Outlined.People,"patients.read"),Destination("tasks","Завдання",Icons.Outlined.Assignment),Destination("messages","Повідомлення",Icons.Outlined.ChatBubbleOutline,"messages.use"),Destination("settings","Налаштування",Icons.Outlined.Settings))

class NativeActivity : ComponentActivity() {
    private val model: ClinicModel by viewModels()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if(it) startAlerts() }
    private fun startAlerts() {
        if (NativeSession.token.isEmpty() || !getSharedPreferences("shift-alerts",MODE_PRIVATE).getBoolean("enabled",true)) return
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){permission.launch(Manifest.permission.POST_NOTIFICATIONS);return}
        runCatching { startForegroundService(Intent(this,ShiftAlertsService::class.java).putExtra("server",NativeSession.server).putExtra("token",NativeSession.token).putExtra("user",NativeSession.userId)) }
    }
    private fun stopAlerts(){stopService(Intent(this,ShiftAlertsService::class.java));getSystemService(NotificationManager::class.java).cancelAll()}
    private fun workspace(path:String){startActivity(Intent(this,MainActivity::class.java).putExtra("native_workspace",true).putExtra("destination",path))}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE)
        NativeSession.server=getSharedPreferences("MainActivity",MODE_PRIVATE).getString("server","") ?: ""
        setContent { MaterialTheme(colorScheme=palette) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent)
        model.select(when(intent.getStringExtra("destination")){"/messages"->"messages";"/tasks","/pool"->"tasks";"/settings"->"settings";else->"home"})
    }
    @Composable private fun App(){
        val m=model
        var server by rememberSaveable { mutableStateOf(NativeSession.server) }
        var editingServer by rememberSaveable { mutableStateOf(server.isEmpty()) }
        var close by remember { mutableStateOf(false) }
        val lifecycle=LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(m.user != null,m.page,m.changed){lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){while(true){m.refresh();delay(5000)}}}
        LaunchedEffect(m.user){if(m.user==null && NativeSession.token.isEmpty())stopAlerts()}
        BackHandler(m.user!=null){if(m.patient!=null)m.closePatient() else if(m.page!="home")m.select("home") else close=true}
        if(m.user==null){
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Spacer(Modifier.height(36.dp));Image(painterResource(R.drawable.rehaflow_icon),null,Modifier.size(72.dp));Spacer(Modifier.height(20.dp))
                Text("RehaFlow",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
                Text("QureMed Industries",color=MaterialTheme.colorScheme.primary)
                Column(Modifier.widthIn(max=480.dp).fillMaxWidth().padding(top=32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    if(editingServer){
                        Text("Підключення до центру",style=MaterialTheme.typography.headlineSmall)
                        Text("Підключіться до Wi-Fi центру. Введіть адресу комп’ютера, на якому працює сервер.")
                        OutlinedTextField(server,{server=it},label={Text("Адреса сервера")},placeholder={Text("https://192.168.1.106")},singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
                        Button(enabled=!m.busy,onClick={m.connect(server){valid->server=valid;getSharedPreferences("MainActivity",MODE_PRIVATE).edit().putString("server",valid).apply();editingServer=false}},modifier=Modifier.fillMaxWidth()){Text(if(m.busy)"Перевіряємо…" else "Підключитися")}
                        CertificateHelp()
                    }else{
                        var login by remember {mutableStateOf("")};var password by remember {mutableStateOf("")}
                        Text("Раді вас бачити",style=MaterialTheme.typography.headlineSmall)
                        Text("Увійдіть у свій обліковий запис. Для персоналу зміна починається автоматично.")
                        OutlinedTextField(login,{login=it},label={Text("Логін")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(password,{password=it},label={Text("Пароль")},visualTransformation=PasswordVisualTransformation(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
                        Button(enabled=!m.busy&&login.isNotBlank()&&password.isNotEmpty(),onClick={m.login(login,password,Build.MANUFACTURER+" "+Build.MODEL){password="";startAlerts()}},modifier=Modifier.fillMaxWidth()){Text(if(m.busy)"Входимо…" else "Увійти")}
                        TextButton(onClick={editingServer=true}){Text("Змінити сервер")}
                    }
                    if(m.error.isNotEmpty())ErrorCard(m.error)
                }
            };return
        }
        val nav=destinations.filter { if(it.key=="tasks")m.can("tasks.work")||m.can("tasks.read") else it.permission.isEmpty()||m.can(it.permission) }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()){
            val wide=maxWidth>=840.dp
            Row(Modifier.fillMaxSize()){
                if(wide)NavigationRail { Image(painterResource(R.drawable.rehaflow_icon),null,Modifier.padding(16.dp).size(44.dp));nav.forEach { d->NavigationRailItem(selected=m.page==d.key,onClick={m.select(d.key)},icon={Icon(d.icon,d.title)},label={Text(d.title)}) } }
                Scaffold(modifier=Modifier.weight(1f),bottomBar={if(!wide)NavigationBar{nav.forEach{d->NavigationBarItem(selected=m.page==d.key,onClick={m.select(d.key)},icon={Icon(d.icon,d.title)},label={Text(d.title)},alwaysShowLabel=false)}}}){padding->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=if(wide)28.dp else 16.dp)){
                        Row(Modifier.fillMaxWidth().padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically){
                            Image(painterResource(R.drawable.rehaflow_icon),null,Modifier.size(36.dp));Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)){Text(nav.find{it.key==m.page}?.title ?: "RehaFlow",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(roles[m.user?.s("role")] ?: "",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)}
                            if(m.loading)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                            IconButton(onClick={close=true}){Icon(Icons.Outlined.Logout,"Завершити роботу")}
                        }
                        if(m.error.isNotEmpty()){ErrorCard(m.error);if(m.uncertain)TextButton(onClick={m.checkedUncertain()}){Text("Стан дії перевірено")}}
                        if(m.message.isNotEmpty())Text(m.message,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
                        when(m.page){
                            "home"->Home(m,::workspace)
                            "patients"->Patients(m,wide,::workspace)
                            "tasks"->Tasks(m,::workspace)
                            "messages"->Messages(m,::workspace)
                            "settings"->Settings(m,onServer={m.write("/auth/logout"){m.clear();stopAlerts();editingServer=true}},onAlerts={startAlerts()})
                        }
                    }
                }
            }
        }
        if(close)AlertDialog(onDismissRequest={if(!m.busy)close=false},title={Text("Завершити роботу?")},text={Text("«Завершити зміну» закриє сесію. Якщо є незавершені завдання, спочатку виконайте або передайте їх.")},confirmButton={TextButton(enabled=!m.busy&&!m.uncertain,onClick={m.write("/auth/finish-work"){m.clear();stopAlerts();close=false;finish()}}){Text("Завершити зміну")}},dismissButton={Column{TextButton(onClick={close=false;finish()}){Text("Закрити без завершення")};TextButton(onClick={close=false}){Text("Повернутися")}}})
    }
    @Composable private fun CertificateHelp(){
        var help by remember {mutableStateOf(false)}
        TextButton(onClick={help=true}){Icon(Icons.Outlined.VerifiedUser,null);Spacer(Modifier.width(8.dp));Text("Допомога із сертифікатом")}
        if(help)AlertDialog(onDismissRequest={help=false},title={Text("Сертифікат центру")},text={Text("Скопіюйте актуальний QureMed-Local-CA.crt із папки працюючого сервера. Android → Безпека → Установити сертифікат → CA. Установіть у тому самому профілі, де працює RehaFlow. Перевірте дату й IP комп’ютера: 192.168.1.1 зазвичай є адресою роутера.")},confirmButton={TextButton(onClick={runCatching{startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))};help=false}){Text("Налаштування Android")}},dismissButton={TextButton(onClick={help=false}){Text("Закрити")}})
    }
    @Composable private fun Settings(m:ClinicModel,onServer:()->Unit,onAlerts:()->Unit){
        val info=remember {packageManager.getPackageInfo(packageName,0)}
        val identity=remember {val p=getSharedPreferences("device-identity",MODE_PRIVATE);p.getString("installation_id","")!!.ifEmpty{java.util.UUID.randomUUID().toString().also{p.edit().putString("installation_id",it).commit()}}}
        var alerts by remember {mutableStateOf(getSharedPreferences("shift-alerts",MODE_PRIVATE).getBoolean("enabled",true))}
        LazyColumn(verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(bottom=24.dp)){
            item{InfoCard("Ваш профіль",m.user?.s("name") ?: "",roles[m.user?.s("role")] ?: "")}
            item{InfoCard("Цей пристрій",Build.MANUFACTURER+" "+Build.MODEL,"Android ${Build.VERSION.RELEASE}\nRehaFlow ${info.versionName}\nКод установлення: $identity\nКод зміниться після очищення даних або перевстановлення.")}
            item{Card{Column(Modifier.padding(18.dp)){Text("Сповіщення",style=MaterialTheme.typography.titleMedium);Row(verticalAlignment=Alignment.CenterVertically){Text("Звук і нові завдання",Modifier.weight(1f));Switch(alerts,{alerts=it;getSharedPreferences("shift-alerts",MODE_PRIVATE).edit().putBoolean("enabled",it).apply();if(it)onAlerts() else stopAlerts()})};Text(ShiftAlertsService.state);TextButton(onClick={runCatching{startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))}}){Text("Звук і дозволи Android")}}}}
            item{InfoCard("З’єднання",NativeSession.server,"Списки перевіряються кожні 5 секунд, поки застосунок відкритий. Останній зв’язок: ${m.synchronizedAt.ifEmpty{"—"}}")}
            item{CertificateHelp();OutlinedButton(enabled=!m.busy,onClick=onServer){Text("Вийти та змінити сервер")}}
            item{OutlinedButton(onClick={workspace("/settings")}){Text("Пароль та інші налаштування")}}
        }
    }
}

@Composable private fun ErrorCard(text:String){Surface(color=MaterialTheme.colorScheme.errorContainer,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)){Text(text,Modifier.padding(14.dp),color=MaterialTheme.colorScheme.onErrorContainer)}}
@Composable private fun InfoCard(title:String,headline:String,body:String){Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(title,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge);Text(headline,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold);if(body.isNotEmpty())Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
@Composable private fun ActionTile(title:String,detail:String,icon:ImageVector,onClick:()->Unit){Card(onClick=onClick,modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(16.dp));Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleMedium);Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.Outlined.ChevronRight,null)}}}

@Composable private fun Home(m:ClinicModel,open:(String)->Unit){
    LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)){
        item{InfoCard("Ваш робочий простір",m.user?.s("name") ?: "",if(m.user?.s("role")=="ADMIN")"Керування центром" else if(m.user?.optBoolean("onShift")==true)"Зміна активна • дані центру поруч" else "Зміна не активна")}
        items((m.data as? JSONObject)?.optJSONArray("metrics")?.objects() ?: emptyList()){metric->ActionTile(metric.s("label"),metric.s("value"),Icons.Outlined.Insights){open(metric.s("path"))}}
        if(m.can("patients.read"))item{ActionTile("Пацієнти","Знайти картку та лікуючого лікаря",Icons.Outlined.People){m.select("patients")}}
        if(m.can("tasks.work")||m.can("tasks.read"))item{ActionTile("Завдання та призначення","План роботи і результати виконання",Icons.Outlined.Assignment){m.select("tasks")}}
        if(m.can("rooms.read"))item{ActionTile("Палати та ліжка","Вільні місця і розміщення",Icons.Outlined.Bed){open("/rooms")}}
        if(m.can("appointments.read"))item{ActionTile("Розклад","Записи та процедури",Icons.Outlined.CalendarMonth){open(if(m.can("appointments.manage"))"/cabinets" else "/schedule")}}
        if(m.can("handover.manage"))item{ActionTile("Передача пацієнтів","Підсумок зміни та прийняття пацієнтів",Icons.Outlined.SwapHoriz){open("/handovers")}}
        if(m.can("shift.handover"))item{ActionTile("Передати робочу зміну","Передача незавершених завдань колезі",Icons.Outlined.SwapHoriz){open("/pool")}}
        if(m.can("archive.read"))item{ActionTile("Архів","Виписки та історія госпіталізацій",Icons.Outlined.Inventory2){open("/archive")}}
        if(m.can("users.manage"))item{ActionTile("Команда і права","Облікові записи персоналу",Icons.Outlined.AdminPanelSettings){open("/users")}}
        if(m.can("sessions.manage"))item{ActionTile("Пристрої та сесії","Активні підключення",Icons.Outlined.Devices){open("/sessions")}}
        if(m.can("audit.read"))item{ActionTile("Журнал дій","Перевірка змін у системі",Icons.Outlined.History){open("/audit")}}
        item{OutlinedButton(onClick={open("/")},modifier=Modifier.fillMaxWidth()){Text("Відкрити повний робочий простір")}}
    }
}
@Composable private fun Patients(m:ClinicModel,wide:Boolean,open:(String)->Unit){
    if(!wide && m.patient!=null){PatientDetail(m,open);return}
    Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(20.dp)){
        Column(Modifier.weight(1f)){
            var query by remember {mutableStateOf(m.search)}
            OutlinedTextField(query,{query=it},label={Text("ПІБ, телефон, дата народження")},singleLine=true,modifier=Modifier.fillMaxWidth(),trailingIcon={IconButton(onClick={m.search=query;m.offset=0;m.changed++}){Icon(Icons.Outlined.Search,"Знайти")}})
            if(m.can("patients.manage"))TextButton(onClick={open("/patients")}){Icon(Icons.Outlined.PersonAdd,null);Spacer(Modifier.width(8.dp));Text("Зареєструвати пацієнта")}
            val rows=(m.data as? JSONArray)?.objects() ?: emptyList()
            LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=14.dp)){
                if(rows.isEmpty())item{Text(if(m.loading)"Завантаження…" else "Пацієнтів не знайдено")}
                items(rows,key={it.s("id")}){p->Card(onClick={m.openPatient(p.s("id"))},modifier=Modifier.fillMaxWidth()){
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Person,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(10.dp));Text(p.s("name"),fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));Icon(Icons.Outlined.ChevronRight,null)}
                        Text("Дата народження: ${p.s("birth_date").take(10).ifEmpty{"—"}}",style=MaterialTheme.typography.bodySmall)
                        Text("Лікуючий лікар: ${p.s("doctor_name").ifEmpty{"Не призначено"}}")
                        Text("Палата ${p.s("room_number").ifEmpty{"—"}} • ліжко ${p.s("bed_number").ifEmpty{"—"}}",color=MaterialTheme.colorScheme.primary)
                    }
                }}
                item{Row{TextButton(enabled=m.offset>0,onClick={m.offset=(m.offset-100).coerceAtLeast(0);m.changed++}){Text("Назад")};TextButton(enabled=rows.size==100,onClick={m.offset+=100;m.changed++}){Text("Далі")}}}
            }
        }
        if(wide)Box(Modifier.weight(1.2f)){if(m.patient==null)InfoCard("Картка пацієнта","Оберіть пацієнта","Натисніть будь-де на картці ліворуч.") else PatientDetail(m,open)}
    }
}
@Composable private fun PatientDetail(m:ClinicModel,open:(String)->Unit){
    val p=m.patient ?: return
    val ad=p.optJSONArray("admissions")?.objects()?.firstOrNull{it.s("discharged_at").isEmpty()}
    LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)){
        item{TextButton(onClick={m.closePatient()}){Icon(Icons.Outlined.ArrowBack,null);Text("До списку")}}
        item{InfoCard("Пацієнт",p.s("name"),"Дата народження: ${p.s("birth_date").take(10).ifEmpty{"—"}}\nТелефон: ${p.s("phone").ifEmpty{"—"}}")}
        item{InfoCard("Лікуючий лікар",ad?.s("doctor_name")?.ifEmpty{"Не призначено"} ?: "Не призначено","Палата ${ad?.s("room_number")?.ifEmpty{"—"} ?: "—"} • ліжко ${ad?.s("bed_number")?.ifEmpty{"—"} ?: "—"}\nНаправлення: ${ad?.s("referral")?.ifEmpty{"—"} ?: "—"}")}
        item{ActionTile("Повна медична картка","Огляди, спостереження, реабілітація, документи й виписка",Icons.Outlined.FolderShared){open("/patients?patient="+p.s("id"))}}
        if(m.can("tasks.create"))item{ActionTile("Призначити лікування","Ліки, догляд або реабілітація",Icons.Outlined.Medication){open("/tasks")}}
        items(p.optJSONArray("admissions")?.objects() ?: emptyList()){a->InfoCard("Госпіталізація",a.s("admitted_at").take(10),"${if(a.s("discharged_at").isEmpty())"Триває" else "Виписано: "+a.s("discharged_at").take(10)}\nЛікар: ${a.s("doctor_name").ifEmpty{"—"}}")}
    }
}
private val taskStatuses=mapOf("OPEN" to "У пулі","IN_PROGRESS" to "У роботі","COMPLETED" to "Закрито","CANCELLED" to "Скасовано")
@Composable private fun Tasks(m:ClinicModel,open:(String)->Unit){
    var prescribing by remember {mutableStateOf(false)}
    var filter by remember {mutableStateOf("OPEN")};var selected by remember {mutableStateOf<JSONObject?>(null)}
    val rows=(m.data as? JSONArray)?.objects() ?: emptyList()
    Column {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(selected=filter=="OPEN",onClick={filter="OPEN"},label={Text("У пулі")})
            FilterChip(selected=filter=="IN_PROGRESS",onClick={filter="IN_PROGRESS"},label={Text("У роботі")})
            FilterChip(selected=filter=="ALL",onClick={filter="ALL"},label={Text("Усі")})
        }
        if(m.can("tasks.create"))TextButton(onClick={prescribing=true}){Icon(Icons.Outlined.AddCircleOutline,null);Text("Нове призначення")}
        if(m.can("tasks.work")&&m.user?.optBoolean("onShift")!=true)Button(enabled=!m.busy,onClick={m.write("/shift",JSONObject().put("start",true))}){Text("Почати зміну")}
        LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)){
            val visible=rows.filter{filter=="ALL"||it.s("status")==filter}
            if(visible.isEmpty())item{Text(if(m.loading)"Завантаження…" else "У цьому списку завдань немає")}
            items(visible,key={it.s("id")}){t->Card(Modifier.fillMaxWidth()){
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(t.s("patient_name"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,modifier=Modifier.fillMaxWidth().clickable{if(m.can("patients.read"))open("/patients?patient="+t.s("patient_id"))})
                    Text("${taskStatuses[t.s("status")] ?: ""} • ${localTime(t.s("scheduled_at"))}",color=MaterialTheme.colorScheme.primary)
                    Text(t.s("description"));if(t.s("medication").isNotEmpty())Text("${t.s("medication")} • ${t.s("dose")} ${t.s("dose_unit")} • ${t.s("route")}")
                    Text("Палата ${t.s("room_number").ifEmpty{"—"}} • ${t.s("cabinet_name")}",style=MaterialTheme.typography.bodySmall)
                    if(t.optBoolean("not_done"))Text("Не виконано: ${t.s("outcome")}",color=MaterialTheme.colorScheme.error)
                    else if(t.s("outcome").isNotEmpty())Text("Результат: ${t.s("outcome")}")
                    if(m.can("tasks.work")&&t.s("status")=="OPEN"){
                        Row{TextButton(enabled=!m.busy&&!m.uncertain&&!t.optBoolean("acknowledged_by_me"),onClick={m.write("/tasks/${t.s("id")}/acknowledge")}){Text(if(t.optBoolean("acknowledged_by_me"))"Отримано" else "Підтвердити отримання")}}
                        Button(enabled=!m.busy&&!m.uncertain,onClick={m.write("/tasks/${t.s("id")}/claim")}){Text("Взяти в роботу")}
                    }
                    if(m.can("tasks.work")&&t.s("status")=="IN_PROGRESS"&&t.s("taken_by")==m.user?.s("id"))Button(enabled=!m.busy&&!m.uncertain,onClick={selected=t}){Text("Записати результат")}
                }
            }}
            item{OutlinedButton(onClick={open(if(m.can("tasks.work"))"/pool" else "/tasks")}){Text("Передача зміни та всі дії")}}
        }
    }
    if(prescribing)Prescription(m){prescribing=false}
    selected?.let{t->
        var outcome by remember(t.s("id")){mutableStateOf("")};var confirmed by remember(t.s("id")){mutableStateOf(false)};var action by remember(t.s("id")){mutableStateOf("complete")}
        AlertDialog(onDismissRequest={if(!m.busy)selected=null},title={Text("Результат виконання")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(t.s("patient_name"),fontWeight=FontWeight.Bold);Text("Дата народження: ${t.s("birth_date").take(10).ifEmpty{"Не вказано — уточніть картку"}}")
            Text(t.s("description"))
            listOf("complete" to "Виконано","not-done" to "Не виконано","release" to "Повернути в пул").forEach{(key,label)->Row(Modifier.fillMaxWidth().clickable{action=key},verticalAlignment=Alignment.CenterVertically){RadioButton(action==key,{action=key});Text(label)}}
            OutlinedTextField(outcome,{if(it.length<=4000)outcome=it},label={Text("Результат / причина")},modifier=Modifier.fillMaxWidth())
            if(action!="release")Row(verticalAlignment=Alignment.CenterVertically){Checkbox(confirmed,{confirmed=it});Text("ПІБ і дату народження звірено з пацієнтом")}
            if(m.error.isNotEmpty())ErrorCard(m.error)
        }},confirmButton={TextButton(enabled=!m.busy&&!m.uncertain&&(action=="release"||confirmed)&&(action!="not-done"||outcome.isNotBlank()),onClick={m.write("/tasks/${t.s("id")}/$action",JSONObject().put("outcome",outcome).put("identity_confirmed",confirmed)){selected=null}}){Text("Зберегти")}},dismissButton={TextButton(enabled=!m.busy,onClick={selected=null}){Text("Скасувати")}})
    }
}
private fun localTime(value:String):String=runCatching{java.time.OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))}.getOrDefault(value)
@Composable private fun Messages(m:ClinicModel,open:(String)->Unit){
    LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)){
        if(m.can("messages.send"))item{Button(onClick={open("/messages")}){Icon(Icons.Outlined.Edit,null);Spacer(Modifier.width(8.dp));Text("Написати повідомлення")}}
        val rows=(m.data as? JSONArray)?.objects() ?: emptyList()
        if(rows.isEmpty())item{Text(if(m.loading)"Завантаження…" else "Повідомлень поки немає")}
        items(rows,key={it.s("id")}){message->Card(Modifier.fillMaxWidth()){
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text(message.s("sender"),style=MaterialTheme.typography.titleMedium);if(message.optBoolean("urgent"))Text("Терміново",color=MaterialTheme.colorScheme.error)
                Text(message.s("body"));Text(localTime(message.s("created_at")),style=MaterialTheme.typography.bodySmall)
                if(message.s("recipient_id")==m.user?.s("id")&&message.s("read_at").isEmpty())TextButton(enabled=!m.busy&&!m.uncertain,onClick={m.write("/care/messages/${message.s("id")}/read")}){Text("Позначити прочитаним")}
                else Text(if(message.s("read_at").isEmpty())"Очікує прочитання" else "Прочитано",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
            }
        }}
    }
}

@Composable private fun Prescription(m:ClinicModel,close:()->Unit){
    var patients by remember {mutableStateOf(emptyList<JSONObject>())};var selected by remember {mutableStateOf<JSONObject?>(null)}
    var query by remember {mutableStateOf("")};var loadError by remember {mutableStateOf("")}
    var description by remember {mutableStateOf("")};var medication by remember {mutableStateOf("")}
    var dose by remember {mutableStateOf("")};var unit by remember {mutableStateOf("")};var route by remember {mutableStateOf("")}
    var kind by remember {mutableStateOf("Догляд")};var executor by remember {mutableStateOf("NURSE")}
    var count by remember {mutableStateOf("1")};var interval by remember {mutableStateOf("24")}
    var scheduled by remember {mutableStateOf(java.time.ZonedDateTime.now().withSecond(0).withNano(0))}
    var reviewing by remember {mutableStateOf(false)}
    val context=LocalContext.current
    LaunchedEffect(query){delay(300);try{patients=(ClinicApi.request("/patients?q="+java.net.URLEncoder.encode(query,"UTF-8")) as JSONArray).objects();loadError=""}catch(e:Exception){loadError=e.message ?: "Помилка пошуку"}}
    AlertDialog(onDismissRequest={if(!m.busy)close()},title={Text(if(reviewing)"Перевірте призначення" else "Нове призначення")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(reviewing){
            Text(selected?.s("name") ?: "",fontWeight=FontWeight.Bold);Text("Дата народження: ${selected?.s("birth_date")?.take(10)}")
            Text("$kind • ${roles[executor]}\n$description")
            if(medication.isNotBlank())Text("$medication\n$dose $unit • $route")
            Text("Початок: ${scheduled.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))}\nКількість: $count • інтервал: $interval год")
        }else{
            if(selected==null){
                OutlinedTextField(query,{if(it.length<=200)query=it},label={Text("Знайти пацієнта")},singleLine=true,modifier=Modifier.fillMaxWidth())
                if(loadError.isNotEmpty())ErrorCard(loadError)
                patients.take(8).forEach{p->TextButton(onClick={selected=p},modifier=Modifier.fillMaxWidth()){Text("${p.s("name")} • ${p.s("birth_date").take(10)}")}}
                Text("Уточніть пошук, якщо потрібної картки немає серед перших результатів.",style=MaterialTheme.typography.bodySmall)
            }else{Text(selected!!.s("name"),fontWeight=FontWeight.Bold);TextButton(onClick={selected=null}){Text("Інший пацієнт")}}
            Choice("Вид призначення",kind,listOf("Догляд","Ліки","Реабілітація")){kind=it;executor=if(it=="Реабілітація")"THERAPIST" else "NURSE";if(it!="Ліки"){medication="";dose="";unit="";route=""}}
            OutlinedTextField(description,{if(it.length<=4000)description=it},label={Text("Що потрібно виконати")},modifier=Modifier.fillMaxWidth())
            if(kind=="Ліки"){
                OutlinedTextField(medication,{if(it.length<=200)medication=it},label={Text("Препарат")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(dose,{if(it.length<=100)dose=it},label={Text("Доза за призначенням лікаря")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(unit,{if(it.length<=50)unit=it},label={Text("Одиниці: мг, мл тощо")},modifier=Modifier.fillMaxWidth())
                Choice("Шлях введення",route.ifEmpty{"Оберіть"},listOf("Перорально","Внутрішньовенно","Внутрішньом’язово","Підшкірно","Інгаляційно","Зовнішньо")){route=it}
                OutlinedTextField(route,{if(it.length<=100)route=it},label={Text("Шлях введення / уточнення")},modifier=Modifier.fillMaxWidth())
            }
            if(kind!="Ліки")Choice("Виконавець",roles[executor] ?: "",listOf("Медсестра","Реабілітолог")){executor=if(it=="Медсестра")"NURSE" else "THERAPIST"}
            OutlinedButton(onClick={android.app.DatePickerDialog(context,{_,year,month,day->scheduled=scheduled.withDayOfMonth(1).withYear(year).withMonth(month+1).withDayOfMonth(day)},scheduled.year,scheduled.monthValue-1,scheduled.dayOfMonth).show()}){Icon(Icons.Outlined.CalendarMonth,null);Text(scheduled.format(java.time.format.DateTimeFormatter.ofPattern(" dd.MM.yyyy")))}
            OutlinedButton(onClick={android.app.TimePickerDialog(context,{_,hour,minute->scheduled=scheduled.withHour(hour).withMinute(minute)},scheduled.hour,scheduled.minute,true).show()}){Icon(Icons.Outlined.Schedule,null);Text(scheduled.format(java.time.format.DateTimeFormatter.ofPattern(" HH:mm")))}
            OutlinedTextField(count,{count=it.filter(Char::isDigit).take(2)},label={Text("Кількість виконань (1–90)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
            OutlinedTextField(interval,{interval=it.take(6)},label={Text("Інтервал, годин (1–720)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)
        }
        if(m.error.isNotEmpty())ErrorCard(m.error)
    }},confirmButton={TextButton(enabled=!m.busy&&!m.uncertain&&selected!=null&&description.isNotBlank()&&(count.toIntOrNull() ?: 0) in 1..90&&(interval.toDoubleOrNull() ?: 0.0) in 1.0..720.0&&(kind!="Ліки"||(medication.isNotBlank()&&dose.isNotBlank()&&unit.isNotBlank()&&route.isNotBlank())),onClick={
        if(!reviewing){reviewing=true}else m.write("/tasks",JSONObject().put("patient_id",selected!!.s("id")).put("description",description).put("task_type",kind).put("executor_role",executor).put("medication",medication).put("dose",dose).put("dose_unit",unit).put("route",route).put("scheduled_at",scheduled.toInstant().toString()).put("repeat_count",count.toInt()).put("interval_hours",interval.toDouble())){close()}
    }){Text(if(reviewing)"Призначити" else "Перевірити")}},dismissButton={TextButton(enabled=!m.busy,onClick={if(reviewing)reviewing=false else close()}){Text(if(reviewing)"Редагувати" else "Скасувати")}})
}
@Composable private fun Choice(label:String,value:String,options:List<String>,change:(String)->Unit){
    var expanded by remember {mutableStateOf(false)}
    Box{OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text("$label: $value",Modifier.weight(1f));Icon(Icons.Outlined.ExpandMore,null)};DropdownMenu(expanded,{expanded=false}){options.forEach{item->DropdownMenuItem(text={Text(item)},onClick={change(item);expanded=false})}}}
}
