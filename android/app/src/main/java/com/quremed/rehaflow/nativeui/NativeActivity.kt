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
import androidx.compose.foundation.horizontalScroll
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
import com.quremed.rehaflow.SessionStore
import com.quremed.rehaflow.NativeSession
import com.quremed.rehaflow.R
import com.quremed.rehaflow.ShiftAlertsService
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private val palette = lightColorScheme(primary=Color(0xFF087E72), onPrimary=Color.White,
    background=Color(0xFFF3F6F3),surface=Color.White,surfaceVariant=Color(0xFFE6EFE4),
    onBackground=Color(0xFF203B37),onSurface=Color(0xFF203B37),onSurfaceVariant=Color(0xFF52685D))
private val roles=mapOf("ADMIN" to "Адміністратор","REGISTRAR" to "Реєстратор","DOCTOR" to "Лікар","NURSE" to "Медсестра","THERAPIST" to "Реабілітолог")
private data class Destination(val key:String,val title:String,val icon:ImageVector,val permission:String="")
private val destinations=listOf(Destination("home","Огляд",Icons.Outlined.Dashboard,"dashboard"),Destination("patients","Пацієнти",Icons.Outlined.People,"patients.read"),Destination("tasks","Завдання",Icons.Outlined.Assignment),Destination("messages","Повідомлення",Icons.Outlined.ChatBubbleOutline,"messages.use"),Destination("settings","Налаштування",Icons.Outlined.Settings))

class NativeActivity : ComponentActivity() {
    private val model: ClinicModel by viewModels()
    private var stationEnabled by mutableStateOf(false)
    private val scanner = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
        result.contents?.let { content ->
            try {
                require(model.user!=null && model.can("patients.read")) { "Увійдіть з доступом до карток пацієнтів." }
                val patientId=PatientQr.parse(content,NativeSession.server)
                model.select("patients");model.openPatient(patientId)
            } catch(e:Exception){model.fail(e)}
        }
    }
    private fun scanPatient(){
        scanner.launch(com.journeyapps.barcodescanner.ScanOptions()
            .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
            .setPrompt("Наведіть камеру на QR-код картки пацієнта")
            .setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(false))
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if(it) startAlerts() }
    private fun startAlerts() {
        if (!model.workspaceAllowed || NativeSession.token.isEmpty() || !getSharedPreferences("shift-alerts",MODE_PRIVATE).getBoolean("enabled",true)) return
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){permission.launch(Manifest.permission.POST_NOTIFICATIONS);return}
        runCatching { startForegroundService(Intent(this,ShiftAlertsService::class.java).putExtra("server",NativeSession.server).putExtra("token",NativeSession.token).putExtra("user",NativeSession.userId)) }
    }
    private fun stopAlerts(){stopService(Intent(this,ShiftAlertsService::class.java));getSystemService(NotificationManager::class.java).cancelAll()}
    private fun workspace(path:String){
        if(!model.workspaceAllowed)return
        val uri=android.net.Uri.parse(path)
        val page=when(uri.path){"/"->"home";"/pool","/tasks"->"tasks";"/cabinets","/schedule"->"schedule";else->uri.path?.removePrefix("/") ?: "home"}
        if(page=="archive")return
        model.select(page)
        uri.getQueryParameter("patient")?.let{model.openPatient(it)}
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState); DraftStore.initialize(this);stationEnabled=getSharedPreferences("shift-alerts",MODE_PRIVATE).getBoolean("station",false)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE)
        NativeSession.initialize(this)
        NativeSession.server=getSharedPreferences("MainActivity",MODE_PRIVATE).getString("server","") ?: ""
        if(NativeSession.token.isEmpty())SessionStore.restore(this)
        if(savedInstanceState==null)routeIntent(intent)
        setContent { MaterialTheme(colorScheme=palette) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
    override fun onResume(){super.onResume();ShiftAlertsService.setActivityVisible(this,true);if(model.workspaceAllowed && !ShiftAlertsService.running)startAlerts()}
    override fun onPause(){ShiftAlertsService.setActivityVisible(this,false);super.onPause()}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);routeIntent(intent)}
    private fun routeIntent(intent:Intent){if(!intent.hasExtra("destination"))return
        model.select(when(intent.getStringExtra("destination")){"/messages"->"messages";"/tasks","/pool"->"tasks";"/handovers"->"handovers";"/settings"->"settings";else->"home"})
    }
    @Composable private fun App(){
        val m=model
        LaunchedEffect(Unit){if(m.user==null)m.restore{startAlerts()}}
        var server by rememberSaveable { mutableStateOf(NativeSession.server) }
        var editingServer by rememberSaveable { mutableStateOf(server.isEmpty()) }
        var close by remember { mutableStateOf(false) }
        val lifecycle=LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(m.user != null,m.page,m.changed){lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){while(true){m.refresh();delay(5000)}}}
        LaunchedEffect(m.workspaceAllowed,stationEnabled){if(m.workspaceAllowed&&stationEnabled)window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        LaunchedEffect(m.workspaceAllowed){if(m.workspaceAllowed){if(!ShiftAlertsService.running)startAlerts()}else stopAlerts()}
        LaunchedEffect(m.user){if(m.user==null && NativeSession.token.isEmpty())stopAlerts()}
        BackHandler(m.user!=null){if(!m.workspaceAllowed)finish() else if(m.patient!=null)m.closePatient() else if(m.page!="home")m.select("home") else close=true}
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
                        Text("Після входу підтвердьте початок зміни. Адміністратор центру погодить запит.")
                        OutlinedTextField(login,{login=it},label={Text("Логін")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(password,{password=it},label={Text("Пароль")},visualTransformation=PasswordVisualTransformation(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
                        Button(enabled=!m.busy&&login.isNotBlank()&&password.isNotEmpty(),onClick={m.login(login,password,Build.MANUFACTURER+" "+Build.MODEL){password="";SessionStore.save(this@NativeActivity);startAlerts()}},modifier=Modifier.fillMaxWidth()){Text(if(m.busy)"Входимо…" else "Увійти")}
                        if(NativeSession.token.isNotEmpty())TextButton(enabled=!m.busy,onClick={m.restore{startAlerts()}}){Text("Відновити поточну сесію")}
                        TextButton(onClick={m.clear();editingServer=true}){Text("Змінити сервер")}
                    }
                    if(m.error.isNotEmpty())ErrorCard(m.error)
                }
            };return
        }
        if(!m.workspaceAllowed){
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
                Icon(Icons.Outlined.Schedule,null,Modifier.size(56.dp),tint=MaterialTheme.colorScheme.primary)
                Text("Почати зміну?",style=MaterialTheme.typography.headlineMedium)
                Text(m.user?.s("name") ?: "",Modifier.padding(12.dp))
                Text("Робочі розділи відкриються після вашого запиту та підтвердження адміністратора.",Modifier.padding(vertical=16.dp))
                if(m.message.isNotEmpty())Text("Запит надіслано. Очікуємо підтвердження адміністратора.")
                if(m.error.isNotEmpty())ErrorCard(m.error)
                if(m.uncertain)TextButton(onClick={m.checkedUncertain();m.changed++}){Text("Перевірити стан запиту")}
                Button(enabled=!m.busy&&!m.uncertain,onClick={m.write("/shift",JSONObject().put("start",true))}){Text("Так, почати зміну")}
                TextButton(enabled=!m.busy,onClick={m.write("/auth/logout"){m.clear();stopAlerts()}}){Text("Ні, вийти")}
            }
            return
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
                            Column(Modifier.weight(1f)){Text(nav.find{it.key==m.page}?.title ?: "RehaFlow",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(m.user?.s("role_label")?.takeIf{it.isNotEmpty()} ?: roles[m.user?.s("role")] ?: "",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)}
                            if(m.loading)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                            if(m.can("patients.read"))IconButton(onClick={scanPatient()}){Icon(Icons.Outlined.QrCodeScanner,"Сканувати QR пацієнта")}
                            if(m.can("messages.use"))IconButton(onClick={m.select("messages")}){Icon(Icons.Outlined.Notifications,"Повідомлення")}
                            IconButton(onClick={close=true}){Icon(Icons.Outlined.Logout,"Завершити роботу")}
                        }
                        if(m.error.isNotEmpty()){ErrorCard(m.error);if(m.uncertain)TextButton(onClick={m.checkedUncertain()}){Text("Повторити непідтверджений запит")}}
                        if(m.message.isNotEmpty())Text(m.message,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
                        when(m.page){
                            "home"->Home(m,::workspace)
                            "patients"->Patients(m,wide,::workspace)
                            "tasks"->Tasks(m,::workspace)
                            "messages"->Messages(m,::workspace)
                            "settings"->Settings(m,onServer={m.write("/auth/logout"){m.clear();stopAlerts();editingServer=true}},onAlerts={startAlerts()})
                            else->NativeSection(m,::workspace)
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
            item{InfoCard("Ваш профіль",m.user?.s("name") ?: "",m.user?.s("role_label")?.takeIf{it.isNotEmpty()} ?: roles[m.user?.s("role")] ?: "")}
            item{InfoCard("Цей пристрій",Build.MANUFACTURER+" "+Build.MODEL,"Android ${Build.VERSION.RELEASE}\nRehaFlow ${info.versionName}\nКод установлення: $identity\nКод зміниться після очищення даних або перевстановлення.")}
            item{Card{Column(Modifier.padding(18.dp)){Text("Сповіщення",style=MaterialTheme.typography.titleMedium);Row(verticalAlignment=Alignment.CenterVertically){Text("Звук і нові завдання",Modifier.weight(1f));Switch(alerts,{alerts=it;getSharedPreferences("shift-alerts",MODE_PRIVATE).edit().putBoolean("enabled",it).apply();if(it)onAlerts() else stopAlerts()})};Text(ShiftAlertsService.state);TextButton(onClick={runCatching{startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))}}){Text("Звук і дозволи Android")}}}}
            item{OutlinedButton(onClick={ShiftAlertsService.testSound(this@NativeActivity)}){Text("Перевірити звук сповіщення")}}
            item{Card{Column(Modifier.padding(18.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text("Режим поста",Modifier.weight(1f));Switch(stationEnabled,{stationEnabled=it;getSharedPreferences("shift-alerts",MODE_PRIVATE).edit().putBoolean("station",it).apply()})};Text("Під час зміни відкритий застосунок не гасить екран. Для планшета на заряджанні. Блокування або згортання повертає обмеження Android.");Text("Зміна до: "+m.user?.s("shiftEndsAt")?.let{localTime(it)});Text("Вхід діє до: "+m.user?.s("sessionExpiresAt")?.let{localTime(it)})}}}
            item{InfoCard("З’єднання",NativeSession.server,"Списки перевіряються кожні 5 секунд, поки застосунок відкритий. Останній зв’язок: ${m.synchronizedAt.ifEmpty{"—"}}")}
            item{CertificateHelp();OutlinedButton(enabled=!m.busy,onClick=onServer){Text("Вийти та змінити сервер")}}
            item{NativePassword(m)}
        }
    }
}

@Composable private fun ErrorCard(text:String){Surface(color=MaterialTheme.colorScheme.errorContainer,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)){Text(text,Modifier.padding(14.dp),color=MaterialTheme.colorScheme.onErrorContainer)}}
@Composable private fun InfoCard(title:String,headline:String,body:String){Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(title,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge);Text(headline,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold);if(body.isNotEmpty())Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
@Composable private fun ActionTile(title:String,detail:String,icon:ImageVector,onClick:()->Unit){Card(onClick=onClick,modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(16.dp));Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleMedium);Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.Outlined.ChevronRight,null)}}}

@Composable private fun Home(m:ClinicModel,open:(String)->Unit){
    LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)){
        if(m.user?.s("role")!="ADMIN"&&m.user?.optBoolean("onShift")!=true)item{OutlinedButton(enabled=!m.busy,onClick={m.write("/shift",JSONObject().put("start",true))}){Text("Надіслати запит адміністратору")}}
        item{InfoCard("Ваш робочий простір",m.user?.s("name") ?: "",if(m.user?.s("role")=="ADMIN")"Керування центром" else if(m.user?.optBoolean("onShift")==true)"Зміна активна • дані центру поруч" else "Очікує підтвердження адміністратора")}
        items((m.data as? JSONObject)?.optJSONArray("appointments")?.objects() ?: emptyList()){ap->ActionTile("${localTime(ap.s("starts_at"))} — ${localTime(ap.s("ends_at"))}","${ap.s("patient_name")} · ${ap.s("cabinet_name")}",Icons.Outlined.CalendarMonth){open("/patients?patient="+ap.s("patient_id"))}}
        items((m.data as? JSONObject)?.optJSONArray("metrics")?.objects()?.filter{it.s("path")!="/archive"} ?: emptyList()){metric->ActionTile(metric.s("label"),metric.s("value"),Icons.Outlined.Insights){open(metric.s("path"))}}
        if(m.can("patients.read"))item{ActionTile("Пацієнти","Знайти картку та лікуючого лікаря",Icons.Outlined.People){m.select("patients")}}
        if(m.can("tasks.work")||m.can("tasks.read"))item{ActionTile("Завдання та призначення","План роботи і результати виконання",Icons.Outlined.Assignment){m.select("tasks")}}
        if(m.can("rooms.read"))item{ActionTile("Палати та ліжка","Вільні місця і розміщення",Icons.Outlined.Bed){open("/rooms")}}
        if(m.can("appointments.read"))item{ActionTile("Мої пацієнти сьогодні","Час, кабінет і картка кожного пацієнта",Icons.Outlined.CalendarMonth){open(if(m.can("appointments.manage"))"/cabinets" else "/schedule")}}
        if(m.can("handover.manage"))item{ActionTile("Передача пацієнтів","Підсумок зміни та прийняття пацієнтів",Icons.Outlined.SwapHoriz){open("/handovers")}}
        if(m.can("shift.handover"))item{ActionTile("Передати робочу зміну","Передача незавершених завдань колезі",Icons.Outlined.SwapHoriz){open("/pool")}}
        if(m.can("users.manage"))item{ActionTile("Команда і права","Облікові записи персоналу",Icons.Outlined.AdminPanelSettings){open("/users")}}
        if(m.can("sessions.manage"))item{ActionTile("Пристрої та сесії","Активні підключення",Icons.Outlined.Devices){open("/sessions")}}
        if(m.can("audit.read"))item{ActionTile("Журнал дій","Перевірка змін у системі",Icons.Outlined.History){open("/audit")}}
        if(m.user?.s("role")=="ADMIN")item{ActionTile("Запити на зміну","Підтвердити початок роботи персоналу",Icons.Outlined.VerifiedUser){open("/approvals")}}
    }
}
@Composable private fun Patients(m:ClinicModel,wide:Boolean,open:(String)->Unit){
    if(!wide && m.patient!=null){PatientDetail(m,open);return}
    Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(20.dp)){
        Column(Modifier.weight(1f)){
            var query by remember {mutableStateOf(m.search)}
            OutlinedTextField(query,{query=it},label={Text("ПІБ, телефон, дата народження")},singleLine=true,modifier=Modifier.fillMaxWidth(),trailingIcon={IconButton(onClick={m.search=query;m.offset=0;m.changed++}){Icon(Icons.Outlined.Search,"Знайти")}})
            if(m.can("patients.manage"))TextButton(onClick={open("/registration")}){Icon(Icons.Outlined.PersonAdd,null);Spacer(Modifier.width(8.dp));Text("Зареєструвати пацієнта")}
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
    var prescribing by remember(p.s("id")) {mutableStateOf(false)}
    var examining by remember(p.s("id")) {mutableStateOf(false)}
    if(prescribing)Prescription(m,{prescribing=false},p)
    if(examining)Examination(m,p){examining=false}
    val ad=p.optJSONArray("admissions")?.objects()?.firstOrNull{it.s("discharged_at").isEmpty()}
    LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)){
        item{TextButton(onClick={m.closePatient()}){Icon(Icons.Outlined.ArrowBack,null);Text("До списку")}}
        item{InfoCard("Прізвище, ім’я, по батькові · № ${p.s("patient_number")}",p.s("name"),"Дата народження: ${p.s("birth_date").take(10).ifEmpty{"—"}}\nТелефон: ${p.s("phone").ifEmpty{"—"}}")}
        item{InfoCard("Лікуючий лікар",ad?.s("doctor_name")?.ifEmpty{"Не призначено"} ?: "Не призначено","Палата ${ad?.s("room_number")?.ifEmpty{"—"} ?: "—"} • ліжко ${ad?.s("bed_number")?.ifEmpty{"—"} ?: "—"}\nНаправлення: ${ad?.s("referral")?.ifEmpty{"—"} ?: "—"}")}
        item{InfoCard("Домашня адреса",p.s("address").ifEmpty{"Не вказана"},"Контакт близької людини: "+p.s("emergency_contact").ifEmpty{"Не вказаний"})}
        item{InfoCard("Причина звернення",ad?.s("complaints")?.ifEmpty{"Не вказана"} ?: "Не вказана","Стать: "+(mapOf("FEMALE" to "Жіноча","MALE" to "Чоловіча","OTHER" to "Інша")[p.s("sex")] ?: "Не вказана"))}
        val assessment=p.optJSONArray("entries")?.objects()?.firstOrNull{it.s("kind")=="ASSESSMENT"&&it.s("admission_id")==ad?.s("id")}
        val corrected=assessment!=null&&p.optJSONArray("entries")?.objects()?.any{it.s("corrects_id")==assessment.s("id")}==true
        if(m.can("clinical.write")||m.can("observations.write")||m.can("rehab.write"))item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text("Алергії · запис лікаря",style=MaterialTheme.typography.labelLarge)
            Text(assessment?.optJSONObject("data")?.s("allergies")?.ifEmpty{"Ще не уточнено лікарем"} ?: "Ще не уточнено лікарем",style=MaterialTheme.typography.titleMedium)
            if(assessment!=null)Text(assessment.s("author")+" · "+localTime(assessment.s("created_at")),style=MaterialTheme.typography.bodySmall)
            if(corrected)Text("Запис має виправлення — перевірте медичні записи",color=MaterialTheme.colorScheme.error)
        }}}
        item{NativeClinicalActions(m,p)}
        if(ad!=null && m.can("tasks.create"))item{ActionTile("Призначити лікування","Пацієнта вже обрано · ліки, догляд, реабілітація",Icons.Outlined.Medication){prescribing=true}}
        if(ad!=null && m.can("clinical.write"))item{ActionTile("Новий огляд","Скарги, діагноз, алергії та план",Icons.Outlined.EditNote){examining=true}}
        item{Text("Лікування та призначення",style=MaterialTheme.typography.titleLarge)}
        val treatments=(p.optJSONArray("timelineTasks")?.objects() ?: emptyList()).filter{it.s("admission_id")==ad?.s("id")}
        if(treatments.isEmpty())item{Text("У поточній госпіталізації призначень немає")}
        items(treatments,key={"treatment:"+it.s("id")}){t->
            InfoCard(t.s("task_type")+" · "+localTime(t.s("scheduled_at")),t.s("description"),
                "Додав: "+t.s("creator").ifEmpty{"—"}+" · "+localTime(t.s("created_at"))+"\n"+
                (if(t.s("medication").isNotEmpty())t.s("medication")+" · "+t.s("dose")+" "+t.s("dose_unit")+" · "+t.s("route")+"\n" else "")+
                (if(t.optBoolean("not_done"))"Не виконано" else taskStatuses[t.s("status")] ?: t.s("status"))+
                (if(t.s("executor").isNotEmpty())" · "+t.s("executor") else "")+
                (if(t.s("outcome").isNotEmpty())"\nРезультат: "+t.s("outcome") else ""))
        }
        item{Text("Огляди та медичні записи",style=MaterialTheme.typography.titleLarge)}
        items(p.optJSONArray("entries")?.objects() ?: emptyList()){e->
            InfoCard("Медичний запис · ${localTime(e.s("created_at"))}","Додав: ${e.s("author")}",e.s("body"))
            val labels=mapOf("complaints" to "Скарги","history" to "Анамнез","diagnosis" to "Діагноз","allergies" to "Алергії","plan" to "План","goals" to "Цілі","assessment" to "Оцінка","result" to "Результат","next_plan" to "Наступний план","recommendations" to "Рекомендації")
            e.optJSONObject("data")?.let{data->labels.forEach{(key,label)->if(data.s(key).isNotEmpty())Text(label+": "+data.s(key))}}
        }
        items(p.optJSONArray("admissions")?.objects() ?: emptyList()){a->InfoCard("Госпіталізація",a.s("admitted_at").take(10),"${if(a.s("discharged_at").isEmpty())"Триває" else "Виписано: "+a.s("discharged_at").take(10)}\nЛікар: ${a.s("doctor_name").ifEmpty{"—"}}")}
    }
}
private val taskStatuses=mapOf("OPEN" to "У пулі","IN_PROGRESS" to "У роботі","COMPLETED" to "Закрито","CANCELLED" to "Скасовано")
@Composable private fun Tasks(m:ClinicModel,open:(String)->Unit){
    var prescribing by remember {mutableStateOf(false)}
    var filter by remember {mutableStateOf("ALL")};var selected by remember {mutableStateOf<JSONObject?>(null)}
    val rows=(m.data as? JSONArray)?.objects() ?: emptyList()
    LaunchedEffect(m.search){delay(350);m.offset=0;m.changed++}
    Column {
        Text("Завдання за зміну",style=MaterialTheme.typography.titleLarge)
        Text("Заплановані до кінця зміни, незавершені та виконані за зміну",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(m.search,{m.search=it.take(200)},label={Text("ПІБ, номер, палата або призначення")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,modifier=Modifier.fillMaxWidth().padding(vertical=8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(selected=filter=="OPEN",onClick={filter="OPEN"},label={Text("У пулі")})
            FilterChip(selected=filter=="IN_PROGRESS",onClick={filter="IN_PROGRESS"},label={Text("У роботі")})
            FilterChip(selected=filter=="COMPLETED",onClick={filter="COMPLETED"},label={Text("Виконані")})
            FilterChip(selected=filter=="ALL",onClick={filter="ALL"},label={Text("Усі")})
        }
        if(m.can("tasks.create"))TextButton(onClick={prescribing=true}){Icon(Icons.Outlined.AddCircleOutline,null);Text("Додати призначення")}
        if(m.can("tasks.work")&&m.user?.optBoolean("onShift")!=true)Button(enabled=!m.busy,onClick={m.write("/shift",JSONObject().put("start",true))}){Text("Запит на відкриття зміни")}
        LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)){
            val visible=rows.filter{filter=="ALL"||it.s("status")==filter}
            if(visible.isEmpty())item{Text(if(m.loading)"Завантаження…" else "У цьому списку завдань немає")}
            items(visible,key={it.s("id")}){t->Card(Modifier.fillMaxWidth()){
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(t.s("patient_name"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,modifier=Modifier.fillMaxWidth().clickable{if(m.can("patients.read"))open("/patients?patient="+t.s("patient_id"))})
                    Text("${taskStatuses[t.s("status")] ?: ""} • ${localTime(t.s("scheduled_at"))}",color=MaterialTheme.colorScheme.primary)
                    Text("Додав: ${t.s("creator").ifEmpty{"—"}} · ${localTime(t.s("created_at"))}",style=MaterialTheme.typography.bodySmall)
                    if(t.s("status") in listOf("OPEN","IN_PROGRESS")&&runCatching{java.time.Instant.parse(t.s("scheduled_at")).isBefore(java.time.Instant.now())}.getOrDefault(false))Text("Час виконання минув",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelLarge)
                    Text(t.s("description"));if(t.s("medication").isNotEmpty())Text("${t.s("medication")} • ${t.s("dose")} ${t.s("dose_unit")} • ${t.s("route")}")
                    Text("Палата ${t.s("room_number").ifEmpty{"—"}} • Ліжко ${t.s("bed_number").ifEmpty{"—"}} • ${t.s("cabinet_name")}",style=MaterialTheme.typography.bodySmall)
                    if(t.optBoolean("not_done"))Text("Не виконано: ${t.s("outcome")}",color=MaterialTheme.colorScheme.error)
                    else if(t.s("outcome").isNotEmpty())Text("Результат: ${t.s("outcome")}")
                    if(m.can("tasks.work")&&t.s("status")=="OPEN"){
                        Row{TextButton(enabled=!m.busy&&!m.uncertain&&!t.optBoolean("acknowledged_by_me"),onClick={m.write("/tasks/${t.s("id")}/acknowledge")}){Text(if(t.optBoolean("acknowledged_by_me"))"Отримано" else "Підтвердити отримання")}}
                        Button(enabled=!m.busy&&!m.uncertain,onClick={m.write("/tasks/${t.s("id")}/claim")}){Text("Взяти в роботу")}
                    }
                    if(m.can("tasks.work")&&t.s("status")=="IN_PROGRESS"&&t.s("taken_by")==m.user?.s("id"))Button(enabled=!m.busy&&!m.uncertain,onClick={selected=t}){Text("Записати результат")}
                }
            }}
            item{Row{TextButton(enabled=m.offset>0,onClick={m.offset=(m.offset-100).coerceAtLeast(0);m.changed++}){Text("Назад")};TextButton(enabled=rows.size==100,onClick={m.offset+=100;m.changed++}){Text("Далі")}}}
            if(m.can("shift.handover"))item{NativeShiftHandover(m)}
        }
    }
    if(prescribing)Prescription(m,{prescribing=false})
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
        if(m.can("messages.send"))item{Button(onClick={open("/compose")}){Icon(Icons.Outlined.Edit,null);Spacer(Modifier.width(8.dp));Text("Написати повідомлення")}}
        val rows=(m.data as? JSONArray)?.objects() ?: emptyList()
        if(rows.isEmpty())item{Text(if(m.loading)"Завантаження…" else "Повідомлень поки немає")}
        items(rows,key={it.s("id")}){message->Card(Modifier.fillMaxWidth()){
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                if(message.s("patient_id").isNotEmpty()&&m.can("patients.read"))TextButton(onClick={open("/patients?patient="+message.s("patient_id"))}){Text("Відкрити картку пацієнта")}
                Text(message.s("sender"),style=MaterialTheme.typography.titleMedium);if(message.optBoolean("urgent"))Text("Терміново",color=MaterialTheme.colorScheme.error)
                Text(message.s("body"));Text(localTime(message.s("created_at")),style=MaterialTheme.typography.bodySmall)
                if(message.s("recipient_id")==m.user?.s("id")&&message.s("read_at").isEmpty())TextButton(enabled=!m.busy&&!m.uncertain,onClick={m.write("/care/messages/${message.s("id")}/read")}){Text("Позначити прочитаним")}
                else Text(if(message.s("read_at").isEmpty())"Очікує прочитання" else "Прочитано",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
            }
        }}
    }
}

@Composable private fun Prescription(m:ClinicModel,close:()->Unit,initialPatient:JSONObject?=null){
    val draft=rememberDraft("prescription:"+(initialPatient?.s("id")?:"new")+":"+(initialPatient?.optJSONArray("admissions")?.objects()?.firstOrNull{it.s("discharged_at").isEmpty()}?.s("id")?:""))
    val requestId=draft.id
    var patients by remember {mutableStateOf(emptyList<JSONObject>())};var selected by remember {mutableStateOf<JSONObject?>(initialPatient?:draft.get("patient").takeIf{it.isNotEmpty()}?.let{JSONObject(it)})}
    var query by remember {mutableStateOf("")};var loadError by remember {mutableStateOf("")}
    var description by draftText(draft,"description","");var medication by draftText(draft,"medication","")
    var dose by draftText(draft,"dose","");var unit by draftText(draft,"unit","");var route by draftText(draft,"route","")
    var kind by draftText(draft,"kind","Догляд");var executor by draftText(draft,"executor","NURSE")
    var count by draftText(draft,"count","1");var interval by draftText(draft,"interval","24")
    var scheduledText by draftText(draft,"scheduled",java.time.ZonedDateTime.now().withSecond(0).withNano(0).toString())
    val scheduled=java.time.ZonedDateTime.parse(scheduledText)
    var reviewing by remember {mutableStateOf(false)}
    val context=LocalContext.current
    LaunchedEffect(query){delay(300);try{patients=(ClinicApi.request("/patients?q="+java.net.URLEncoder.encode(query,"UTF-8")) as JSONArray).objects();loadError=""}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;loadError=e.message ?: "Помилка пошуку"}}
    AlertDialog(onDismissRequest={if(!m.busy)close()},title={Text(if(reviewing)"Перевірте призначення" else "Нове призначення")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        DraftNotice(draft)
        if(reviewing){
            Text(selected?.s("name") ?: "",fontWeight=FontWeight.Bold);Text("Дата народження: ${selected?.s("birth_date")?.take(10)}")
            Text("$kind • ${roles[executor]}\n$description")
            if(medication.isNotBlank())Text("$medication\n$dose $unit • $route")
            Text("Початок: ${scheduled.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))}\nКількість: $count • інтервал: $interval год")
        }else{
            if(selected==null){
                OutlinedTextField(query,{if(it.length<=200)query=it},label={Text("Пацієнт, номер, палата або ліжко")},singleLine=true,modifier=Modifier.fillMaxWidth())
                if(loadError.isNotEmpty())ErrorCard(loadError)
                patients.take(8).forEach{p->TextButton(onClick={selected=p;draft.set("patient",p.toString())},modifier=Modifier.fillMaxWidth()){Text("${p.s("name")} • № ${p.s("patient_number")} • Палата ${p.s("room_number").ifEmpty{"—"}} • Ліжко ${p.s("bed_number").ifEmpty{"—"}}")}}
                Text("Уточніть пошук, якщо потрібної картки немає серед перших результатів.",style=MaterialTheme.typography.bodySmall)
            }else{Text(selected!!.s("name"),fontWeight=FontWeight.Bold);TextButton(onClick={selected=null;draft.set("patient","")}){Text("Інший пацієнт")}}
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
            OutlinedButton(onClick={android.app.DatePickerDialog(context,{_,year,month,day->scheduledText=scheduled.withDayOfMonth(1).withYear(year).withMonth(month+1).withDayOfMonth(day).toString()},scheduled.year,scheduled.monthValue-1,scheduled.dayOfMonth).show()}){Icon(Icons.Outlined.CalendarMonth,null);Text(scheduled.format(java.time.format.DateTimeFormatter.ofPattern(" dd.MM.yyyy")))}
            OutlinedButton(onClick={android.app.TimePickerDialog(context,{_,hour,minute->scheduledText=scheduled.withHour(hour).withMinute(minute).toString()},scheduled.hour,scheduled.minute,true).show()}){Icon(Icons.Outlined.Schedule,null);Text(scheduled.format(java.time.format.DateTimeFormatter.ofPattern(" HH:mm")))}
            OutlinedTextField(count,{count=it.filter(Char::isDigit).take(2)},label={Text("Кількість виконань (1–90)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
            OutlinedTextField(interval,{interval=it.take(6)},label={Text("Інтервал, годин (1–720)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)
        }
        if(m.error.isNotEmpty())ErrorCard(m.error)
    }},confirmButton={TextButton(enabled=draft.error.isEmpty()&&!m.busy&&!m.uncertain&&selected!=null&&description.isNotBlank()&&(count.toIntOrNull() ?: 0) in 1..90&&(interval.toDoubleOrNull() ?: 0.0) in 1.0..720.0&&(kind!="Ліки"||(medication.isNotBlank()&&dose.isNotBlank()&&unit.isNotBlank()&&route.isNotBlank())),onClick={
        if(!reviewing){reviewing=true}else m.write("/tasks",JSONObject().put("request_id",requestId).put("expected_admission_id",selected!!.s("admission_id").ifEmpty{selected!!.optJSONArray("admissions")?.objects()?.firstOrNull{it.s("discharged_at").isEmpty()}?.s("id")}).put("patient_id",selected!!.s("id")).put("description",description).put("task_type",kind).put("executor_role",executor).put("medication",medication).put("dose",dose).put("dose_unit",unit).put("route",route).put("scheduled_at",scheduled.toInstant().toString()).put("repeat_count",count.toInt()).put("interval_hours",interval.toDouble()),draftScope=draft.scope){close()}
    }){Text(if(reviewing)"Призначити" else "Перевірити")}},dismissButton={TextButton(enabled=!m.busy,onClick={if(reviewing)reviewing=false else close()}){Text(if(reviewing)"Редагувати" else "Скасувати")}})
}
@Composable private fun Choice(label:String,value:String,options:List<String>,change:(String)->Unit){
    var expanded by remember {mutableStateOf(false)}
    Box{OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text("$label: $value",Modifier.weight(1f));Icon(Icons.Outlined.ExpandMore,null)};DropdownMenu(expanded,{expanded=false}){options.forEach{item->DropdownMenuItem(text={Text(item)},onClick={change(item);expanded=false})}}}
}

@Composable private fun Examination(m:ClinicModel,patient:JSONObject,close:()->Unit){
    val admission=patient.optJSONArray("admissions")?.objects()?.firstOrNull{it.s("discharged_at").isEmpty()}?.s("id")?:""
    val draft=rememberDraft("assessment:"+patient.s("id")+":"+admission)
    var complaints by draftText(draft,"complaints")
    var history by draftText(draft,"history")
    var diagnosis by draftText(draft,"diagnosis")
    var allergies by draftText(draft,"allergies")
    var plan by draftText(draft,"plan")
    var summary by draftText(draft,"summary")
    AlertDialog(
        onDismissRequest={if(!m.busy)close()},
        title={Column{Text("Огляд лікаря");Text(patient.s("name"),style=MaterialTheme.typography.titleSmall)}},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            DraftNotice(draft)
            Text("Запис буде збережено з вашим ім’ям і часом. Заповнюйте лише перевірені дані.")
            OutlinedTextField(value=complaints,onValueChange={complaints=it.take(4000)},label={Text("Скарги")},minLines=2,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=history,onValueChange={history=it.take(4000)},label={Text("Анамнез")},minLines=3,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=diagnosis,onValueChange={diagnosis=it.take(4000)},label={Text("Діагноз / робочий висновок")},minLines=2,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=allergies,onValueChange={allergies=it.take(4000)},label={Text("Алергії — вкажіть, якщо не уточнено")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=plan,onValueChange={plan=it.take(10000)},label={Text("План лікування")},minLines=3,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=summary,onValueChange={summary=it.take(20000)},label={Text("Підсумок огляду")},minLines=2,modifier=Modifier.fillMaxWidth())
            if(m.error.isNotEmpty())ErrorCard(m.error)
            if(m.uncertain)Text("Збереження не підтверджено. Перевірте історію картки перед повторною дією.")
        }},
        confirmButton={TextButton(enabled=draft.error.isEmpty()&&!m.busy&&!m.uncertain&&listOf(complaints,diagnosis,allergies,plan,summary).all{it.isNotBlank()},onClick={
            m.write("/patients/"+patient.s("id")+"/entries",JSONObject().put("request_id",draft.id).put("expected_admission_id",admission).put("kind","ASSESSMENT").put("body",summary).put("data",JSONObject().put("complaints",complaints).put("history",history).put("diagnosis",diagnosis).put("allergies",allergies).put("plan",plan)),draftScope=draft.scope){
                m.openPatient(patient.s("id"));close()
            }
        }){Text(if(m.busy)"Зберігаємо…" else "Зберегти огляд")}},
        dismissButton={TextButton(enabled=!m.busy,onClick=close){Text("Закрити")}}
    )
}
