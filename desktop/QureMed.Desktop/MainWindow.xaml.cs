using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using System.Text.Json.Nodes;
using Windows.Storage;
using Windows.Storage.Pickers;
using Windows.Storage.Streams;
namespace QureMed.Desktop;
public sealed partial class MainWindow : Window {
    readonly ApiClient api = new();
    readonly DispatcherTimer heartbeat = new() { Interval = TimeSpan.FromSeconds(30) };
    readonly string config = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "QureMed", "server.txt");
    JsonNode? user; StackPanel page = new(); NavigationView? navigation; string current = "dashboard"; int generation; bool checking;
    static string S(JsonNode? n, string key) => n?[key]?.ToString() ?? "";
    bool Can(string p) => user?["permissions"]?.AsArray().Any(x => x?.ToString() == p) == true;
    static IEnumerable<JsonNode> Rows(JsonNode? n) => n is JsonArray a ? a.OfType<JsonNode>() : Enumerable.Empty<JsonNode>();
    static TextBlock Text(string s, double size = 15) => new() { Text = s, FontSize = size, TextWrapping = TextWrapping.Wrap };
    static StackPanel Stack(params UIElement[] controls) { var p = new StackPanel { Spacing = 12 }; foreach (var c in controls) p.Children.Add(c); return p; }
    static Border Card(UIElement child) => new() { Child = child, Background = new SolidColorBrush(Windows.UI.Color.FromArgb(255, 18, 27, 42)), BorderBrush = new SolidColorBrush(Windows.UI.Color.FromArgb(255, 37, 48, 68)), BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(12), Padding = new Thickness(22), Margin = new Thickness(0, 0, 0, 12) };
    Button Button(string label, Func<Task> action) { var b = new Button { Content = label, Padding = new Thickness(16, 10, 16, 10), MinHeight = 42 }; b.Click += async (_, _) => { b.IsEnabled = false; try { await action(); } catch (Exception e) { await Message(e.Message); } finally { b.IsEnabled = true; } }; return b; }
    static StackPanel Row(params UIElement[] cs) { var s = Stack(cs); s.Orientation = Orientation.Horizontal; return s; }
    async Task Message(string text) { if (Root.XamlRoot == null) return; var d = new ContentDialog { XamlRoot = Root.XamlRoot, Title = "RehaFlow", Content = Text(text), CloseButtonText = "Закрити", RequestedTheme = ElementTheme.Dark }; try { await d.ShowAsync(); } catch { } }
    async Task<bool> Confirm(string title, string text) { var d = new ContentDialog { XamlRoot = Root.XamlRoot, Title = title, Content = Text(text), PrimaryButtonText = "Підтвердити", CloseButtonText = "Скасувати", RequestedTheme = ElementTheme.Dark }; return await d.ShowAsync() == ContentDialogResult.Primary; }
    public MainWindow() {
        InitializeComponent(); Title = "RehaFlow · QureMed Industries";
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1380, 900));
        if (System.IO.File.Exists(config)) { try { api.Configure(System.IO.File.ReadAllText(config)); } catch { } }
        api.SessionEnded += () => DispatcherQueue.TryEnqueue(() => { user = null; Login(); });
        heartbeat.Tick += async (_, _) => { if (checking || user == null) return; checking = true; try { user = await api.Send("/auth/me"); if (current is "tasks" or "sessions") await Show(current); } catch { } finally { checking = false; } };
        heartbeat.Start(); Closed += (_, _) => heartbeat.Stop(); Login();
    }
    void Login() {
        generation++; navigation = null; Surface.Children.Clear();
        var address = new TextBox { Header = "Адреса локального сервера", Text = api.BaseUrl };
        var login = new TextBox { Header = "Логін", PlaceholderText = "Ваш логін" };
        var password = new PasswordBox { Header = "Пароль" };
        var error = Text("");
        var form = Stack(Text("QUREMED INDUSTRIES", 16), Text("RehaFlow", 40), Text("Турбота про людей. Порядок у роботі.", 20), address, login, password, error);
        form.Children.Add(Button("Увійти в робочий кабінет", async () => {
            try { api.Configure(address.Text); var r = await api.Send("/auth/login", "POST", new { login = login.Text, password = password.Password, device = Environment.MachineName + " · WinUI" }); api.Token = S(r, "token"); password.Password = ""; user = await api.Send("/auth/me"); System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(config)!); System.IO.File.WriteAllText(config, api.BaseUrl); Shell(); }
            catch (Exception ex) { error.Text = ex.Message; }
        }));
        form.Width = 470; form.HorizontalAlignment = HorizontalAlignment.Center; form.VerticalAlignment = VerticalAlignment.Center;
        Surface.Children.Add(new ScrollViewer { Content = form, Padding = new Thickness(40) });
    }
    void Shell() {
        Surface.Children.Clear(); navigation = new NavigationView { IsBackButtonVisible = NavigationViewBackButtonVisible.Collapsed, IsSettingsVisible = true, PaneTitle = "RehaFlow", OpenPaneLength = 255, Header = "QureMed Industries / Реабілітаційний центр" };
        foreach (var n in new[] { ("dashboard", "Огляд центру", "dashboard"), ("patients", "Пацієнти", "patients.read"), ("rooms", "Палати та ліжка", "rooms.manage"), ("cabinets", "Кабінети і розклад", "cabinets.manage"), ("schedule", "Розклад процедур", "appointments.read"), ("tasks", "Призначення", "tasks.read"), ("tasks", "Моя зміна", "tasks.work"), ("archive", "Архів", "archive.read"), ("users", "Команда та права", "users.manage"), ("sessions", "Онлайн-сесії", "sessions.manage"), ("audit", "Журнал дій", "audit.read") }) {
            if (Can(n.Item3)) navigation.MenuItems.Add(new NavigationViewItem { Content = n.Item2, Tag = n.Item1 });
        }
        navigation.PaneFooter = Text(S(user, "name") + "\n" + S(user, "specialty"));
        navigation.ItemInvoked += async (_, e) => { try { await Show(e.IsSettingsInvoked ? "settings" : (e.InvokedItemContainer?.Tag?.ToString() ?? "dashboard")); } catch (Exception ex) { await Message(ex.Message); } };
        Surface.Children.Add(navigation); _ = SafeShow("dashboard");
    }
    async Task SafeShow(string section) { try { await Show(section); } catch (Exception e) { await Message(e.Message); } }
    async Task Show(string section) {
        if (user == null || navigation == null) return;
        current = section; var stamp = ++generation; var content = new StackPanel { Spacing = 12, Margin = new Thickness(30), MaxWidth = 1500, HorizontalAlignment = HorizontalAlignment.Stretch };
        var title = new Dictionary<string,string> { ["dashboard"]="Огляд центру",["patients"]="Пацієнти",["archive"]="Архів пацієнтів",["rooms"]="Палати та ліжка",["cabinets"]="Кабінети і розклад",["schedule"]="Розклад",["tasks"]="Призначення",["users"]="Команда та права",["sessions"]="Онлайн-сесії",["audit"]="Журнал дій",["settings"]="Налаштування" };
        content.Children.Add(Text(title.GetValueOrDefault(section, section), 30));
        content.Children.Add(Text("RehaFlow / " + S(user,"name"), 14));
        content.Children.Add(Button("Оновити", () => Show(section)));
        switch(section) {
            case "dashboard":
                if(!Can("dashboard")) break;
                var d = await api.Send("/dashboard");
                content.Children.Add(Card(Stack(Text("Більше уваги пацієнтам.", 28), Text("Спільна робота лікарів, реєстратури та медсестер."))));
                foreach(var metric in new[]{("active","Пацієнтів у центрі"),("occupied","Зайнято ліжок"),("open","Завдань у пулі"),("completed","Виконано сьогодні")}) content.Children.Add(Card(Row(Text(S(d,metric.Item1),32),Text(metric.Item2,18))));
                break;
            case "patients": case "archive":
                bool archived=section=="archive"; if(!Can(archived?"archive.read":"patients.read")) break;
                if(!archived && Can("patients.manage")) content.Children.Add(Button("+ Зареєструвати пацієнта",()=>PatientForm(null)));
                var search=new TextBox {PlaceholderText="Пошук за ім’ям",MaxWidth=500,HorizontalAlignment=HorizontalAlignment.Left};
                var list=new StackPanel();
                async Task LoadPatients(){list.Children.Clear();var ps=await api.Send("/patients?status="+(archived?"ARCHIVED":"ACTIVE")+"&q="+Uri.EscapeDataString(search.Text));foreach(var p in Rows(ps)){
                    var actions=Row(Button("Картка",()=>PatientCard(S(p,"id"))));
                    if(Can("patients.manage"))actions.Children.Add(Button(archived?"Госпіталізувати":"Редагувати",()=>PatientForm(p,archived)));
                    list.Children.Add(Card(Stack(Text(S(p,"name"),21),Text("Палата "+S(p,"room_number")+" · ліжко "+S(p,"bed_number")+" / "+S(p,"doctor_name")),Text(S(p,"phone")),actions)));
                }if(list.Children.Count==0)list.Children.Add(Text("Пацієнтів не знайдено."));}
                content.Children.Add(Row(search,Button("Знайти",LoadPatients)));content.Children.Add(list);await LoadPatients(); break;
            case "rooms":
                if(!Can("rooms.manage"))break;
                content.Children.Add(Button("+ Додати палату",()=>SimpleForm("Нова палата","/rooms",new[]{("room_number","Номер палати") })));
                foreach(var r in Rows(await api.Send("/rooms"))){var stack=Stack(Text("Палата "+S(r,"room_number"),22),Button("+ Ліжко",()=>SimpleForm("Нове ліжко","/rooms/"+S(r,"id")+"/beds",new[]{("bed_number","Номер ліжка")})));
                    foreach(var bed in Rows(r["beds"]))stack.Children.Add(Row(Text("Ліжко "+S(bed,"bed_number")+" · "+(S(bed,"patient_name")==""?"Вільне":S(bed,"patient_name"))),Button("QR-код",()=>Qr(S(bed,"qr_uid")))));
                    content.Children.Add(Card(stack));}break;
            case "cabinets": case "schedule":
                bool manage=Can("cabinets.manage");if(!manage&&!Can("appointments.read"))break;
                if(manage)content.Children.Add(Row(Button("+ Кабінет",CabinetForm),Button("+ Записати пацієнта",AppointmentForm)));
                foreach(var c in Rows(await api.Send("/cabinets")))content.Children.Add(Text(S(c,"name")+" / "+S(c,"type"),18));
                foreach(var ap in Rows(await api.Send("/appointments"))){var row=Stack(Text(S(ap,"patient_name")+" · "+S(ap,"cabinet_name"),20),Text(S(ap,"starts_at")+" → "+S(ap,"ends_at")),Text(S(ap,"status")));
                    if(manage&&S(ap,"status")=="BOOKED")row.Children.Add(Row(Button("Виконано",async()=>{await api.Send("/appointments/"+S(ap,"id"),"PATCH",new{status="COMPLETED"});await Show(section);}),Button("Скасувати",async()=>{await api.Send("/appointments/"+S(ap,"id"),"PATCH",new{status="CANCELLED"});await Show(section);})));content.Children.Add(Card(row));}break;
            case "tasks":
                if(Can("tasks.create"))content.Children.Add(Button("+ Призначення",TaskForm));
                bool nurse=Can("tasks.work"); if(!nurse&&!Can("tasks.read"))break;
                if(nurse){bool on=user["onShift"]?.GetValue<bool>()==true;content.Children.Add(Button(on?"Завершити зміну":"Почати зміну",async()=>{await api.Send("/shift","POST",new{start=!on});user=await api.Send("/auth/me");await Show(section);}));if(!on){content.Children.Add(Text("Почніть зміну, щоб побачити завдання."));break;}}
                foreach(var t in Rows(await api.Send("/tasks"))){string id=S(t,"id"),status=S(t,"status");var stack=Stack(Text(S(t,"patient_name"),21),Text(S(t,"description")),Text(S(t,"scheduled_at")+" · "+status+" · "+S(t,"nurse_name")));
                    async Task Act(string action){await api.Send("/tasks/"+id+"/"+action,"POST");await Show("tasks");}
                    if(nurse&&status=="OPEN")stack.Children.Add(Button("Взяти в роботу",()=>Act("claim")));
                    if(nurse&&status=="IN_PROGRESS"&&S(t,"taken_by")==S(user,"id"))stack.Children.Add(Row(Button("Виконано",()=>Act("complete")),Button("Повернути у пул",()=>Act("release"))));
                    if(Can("tasks.create")&&S(t,"created_by")==S(user,"id")&&status is "OPEN" or "IN_PROGRESS")stack.Children.Add(Button("Скасувати",()=>Act("cancel")));
                    content.Children.Add(Card(stack));}break;
            case "users":
                if(!Can("users.manage"))break;
                content.Children.Add(Button("+ Працівник",()=>UserForm(null)));
                foreach(var u in Rows(await api.Send("/users")))content.Children.Add(Card(Stack(Text(S(u,"name"),21),Text(S(u,"role")+" · "+S(u,"specialty")+" · "+S(u,"login")),Button("Налаштувати",()=>UserForm(u)))));break;
            case "sessions":
                if(!Can("sessions.manage"))break;
                foreach(var s in Rows(await api.Send("/sessions"))){var stack=Stack(Text(S(s,"device"),21),Text(S(s,"name")+" · "+S(s,"ip")),Text("Остання активність: "+S(s,"last_seen_at")+" / "+(s["online"]?.GetValue<bool>()==true?"Онлайн":"Неактивна")));
                    if(s["revoked_at"]==null)stack.Children.Add(Button("Завершити сесію",async()=>{if(await Confirm("Завершити сесію?",S(s,"name")+" має увійти знову.")){await api.Send("/sessions/"+S(s,"id"),"DELETE");await Show("sessions");}}));content.Children.Add(Card(stack));}break;
            case "audit":
                if(!Can("audit.read"))break;
                foreach(var e in Rows(await api.Send("/audit")))content.Children.Add(Card(Text(S(e,"created_at")+" · "+S(e,"actor")+" · "+S(e,"action"))));break;
            case "settings":
                content.Children.Add(Card(Stack(Text("Локальний сервер",22),Text(api.BaseUrl),Text("Змініть адресу на екрані входу для підключення до іншого центру."))));
                content.Children.Add(Button("Змінити пароль",PasswordForm));
                content.Children.Add(Button("Вийти",async()=>{try{await api.Send("/auth/logout","POST");}finally{api.Token="";user=null;Login();}}));break;
        }
        if(stamp!=generation||user==null||navigation==null)return;
        page=content;navigation.Content=new ScrollViewer{Content=content,HorizontalScrollBarVisibility=ScrollBarVisibility.Auto};
    }
    async Task Form(string title,StackPanel fields,Func<Task> save){
        var error=Text("");fields.Children.Add(error);
        var d=new ContentDialog{XamlRoot=Root.XamlRoot,Title=title,Content=new ScrollViewer{Content=fields,MaxHeight=560},PrimaryButtonText="Зберегти",CloseButtonText="Скасувати",RequestedTheme=ElementTheme.Dark};
        d.PrimaryButtonClick+=async(_,e)=>{var def=e.GetDeferral();d.IsPrimaryButtonEnabled=false;try{await save();}catch(Exception ex){e.Cancel=true;error.Text=ex.Message;}finally{d.IsPrimaryButtonEnabled=true;def.Complete();}};
        if(await d.ShowAsync()==ContentDialogResult.Primary)await Show(current);
    }
    async Task SimpleForm(string title,string path,(string,string)[] names){var fields=new StackPanel{Spacing=14};var boxes=new Dictionary<string,TextBox>();foreach(var (key,label) in names){var b=new TextBox{Header=label};boxes[key]=b;fields.Children.Add(b);}await Form(title,fields,async()=>{await api.Send(path,"POST",boxes.ToDictionary(x=>x.Key,x=>(object)x.Value.Text));});}
    static TextBox Input(string label,string value="")=>new(){Header=label,Text=value};
    static ComboBox Select(string label,IEnumerable<(string id,string name)> options,string selected="",bool empty=true){var b=new ComboBox{Header=label,HorizontalAlignment=HorizontalAlignment.Stretch};if(empty)b.Items.Add(new ComboBoxItem{Content="Не обрано",Tag=""});foreach(var (id,name) in options)b.Items.Add(new ComboBoxItem{Content=name,Tag=id});b.SelectedItem=b.Items.OfType<ComboBoxItem>().FirstOrDefault(x=>x.Tag?.ToString()==selected)??b.Items.FirstOrDefault();return b;}
    static string? Value(ComboBox b)=>string.IsNullOrEmpty((b.SelectedItem as ComboBoxItem)?.Tag?.ToString())?null:(b.SelectedItem as ComboBoxItem)?.Tag?.ToString();
    async Task PatientForm(JsonNode? value,bool readmit=false){
        var name=Input("ПІБ",S(value,"name"));var birth=Input("Дата народження (РРРР-ММ-ДД)",S(value,"birth_date").Split('T')[0]);var phone=Input("Телефон",S(value,"phone"));
        var staff=await api.Send("/staff");var rooms=await api.Send("/rooms");
        var doctor=Select("Лікар",Rows(staff).Where(x=>S(x,"role")=="DOCTOR").Select(x=>(S(x,"id"),S(x,"name"))),S(value,"doctor_id"));
        var beds=Rows(rooms).SelectMany(r=>Rows(r["beds"]).Where(b=>b["patient_id"]==null||S(b,"patient_id")==S(value,"id")).Select(b=>(S(b,"id"),"Палата "+S(r,"room_number")+" / "+S(b,"bed_number"))));
        var bed=Select("Ліжко",beds,S(value,"bed_id"));var fields=readmit?Stack(doctor,bed):Stack(name,birth,phone,doctor,bed);
        await Form(readmit?"Госпіталізація":value==null?"Новий пацієнт":"Редагування пацієнта",fields,async()=>{await api.Send(value==null?"/patients":"/patients/"+S(value,"id")+(readmit?"/readmit":""),value!=null&&!readmit?"PATCH":"POST",new{name=name.Text,birth_date=string.IsNullOrWhiteSpace(birth.Text)?null:birth.Text,phone=phone.Text,doctor_id=Value(doctor),bed_id=Value(bed)});});
    }
    async Task PatientCard(string id){var p=await api.Send("/patients/"+id);var body=Stack(Text(S(p,"name"),24),Text(S(p,"status")),Text("Госпіталізації",20));foreach(var a in Rows(p["admissions"]))body.Children.Add(Text(S(a,"admitted_at")+" → "+S(a,"discharged_at")+" · "+S(a,"doctor_name")));
        body.Children.Add(Text("Медичні записи",20));foreach(var n in Rows(p["notes"]))body.Children.Add(Card(Stack(Text(S(n,"author")+" · "+S(n,"created_at"),13),Text(S(n,"body")))));
        var d=new ContentDialog{XamlRoot=Root.XamlRoot,Title="Картка пацієнта",Content=new ScrollViewer{Content=body,MaxHeight=520},CloseButtonText="Закрити",RequestedTheme=ElementTheme.Dark};
        bool note=Can("notes.write")&&S(p,"status")=="ACTIVE",discharge=Can("patients.manage")&&S(p,"status")=="ACTIVE";
        if(note)d.PrimaryButtonText="Додати запис";if(discharge)d.SecondaryButtonText="Виписати";var result=await d.ShowAsync();
        if(result==ContentDialogResult.Primary&&note){var text=new TextBox{Header="Медичний запис",AcceptsReturn=true,TextWrapping=TextWrapping.Wrap,MinHeight=160};await Form("Новий медичний запис",Stack(text),async()=>{await api.Send("/patients/"+id+"/notes","POST",new{body=text.Text});});}
        if(result==ContentDialogResult.Secondary&&discharge&&await Confirm("Виписка пацієнта","Ліжко звільниться. Активні завдання та майбутні записи скасуються.")){await api.Send("/patients/"+id+"/discharge","POST");await Show(current);}
    }
    async Task CabinetForm(){var name=Input("Назва");var type=Select("Тип",new[]{("massage","Масаж"),("pool","Басейн"),("gym","ЛФК"),("physio","Фізіотерапія")},empty:false);await Form("Новий кабінет",Stack(name,type),async()=>{await api.Send("/cabinets","POST",new{name=name.Text,type=Value(type)});});}
    static string DateValue(TextBox box){if(!DateTimeOffset.TryParse(box.Text,out var date))throw new Exception("Вкажіть дату й час, наприклад 2026-09-09 10:00");return date.ToUniversalTime().ToString("O");}
    async Task AppointmentForm(){var patients=await api.Send("/patients");var cabinets=await api.Send("/cabinets");var p=Select("Пацієнт",Rows(patients).Select(x=>(S(x,"id"),S(x,"name"))));var c=Select("Кабінет",Rows(cabinets).Select(x=>(S(x,"id"),S(x,"name"))));var start=Input("Початок: РРРР-ММ-ДД ГГ:ХХ",DateTime.Now.AddHours(1).ToString("yyyy-MM-dd HH:mm"));var end=Input("Кінець: РРРР-ММ-ДД ГГ:ХХ",DateTime.Now.AddHours(2).ToString("yyyy-MM-dd HH:mm"));await Form("Новий запис",Stack(p,c,start,end),async()=>{await api.Send("/appointments","POST",new{patient_id=Value(p),cabinet_id=Value(c),starts_at=DateValue(start),ends_at=DateValue(end)});});}
    async Task TaskForm(){var patients=await api.Send("/patients");var cabinets=await api.Send("/cabinets");var p=Select("Пацієнт",Rows(patients).Select(x=>(S(x,"id"),S(x,"name"))));var c=Select("Кабінет",Rows(cabinets).Select(x=>(S(x,"id"),S(x,"name"))));var type=Input("Тип призначення");var description=new TextBox{Header="Що виконати",AcceptsReturn=true,TextWrapping=TextWrapping.Wrap,MinHeight=100};var at=Input("Час: РРРР-ММ-ДД ГГ:ХХ",DateTime.Now.AddHours(1).ToString("yyyy-MM-dd HH:mm"));await Form("Призначення",Stack(p,type,description,at,c,Text("Час кабінету резервує реєстратура окремим записом.",13)),async()=>{await api.Send("/tasks","POST",new{patient_id=Value(p),task_type=type.Text,description=description.Text,scheduled_at=DateValue(at),cabinet_id=Value(c)});});}
    async Task UserForm(JsonNode? u){
        var name=Input("ПІБ",S(u,"name"));var login=Input("Логін",S(u,"login"));var password=new PasswordBox{Header=u==null?"Пароль (12+ символів)":"Новий пароль (необов’язково)"};var role=Select("Роль",new[]{("ADMIN","Адміністратор"),("REGISTRAR","Реєстратура"),("DOCTOR","Лікар"),("NURSE","Медсестра")},u==null?"NURSE":S(u,"role"),false);var specialty=Input("Спеціальність",S(u,"specialty"));var active=new CheckBox{Content="Обліковий запис активний",IsChecked=u?["active"]?.GetValue<bool>()??true};var perms=await api.Send("/permissions");var permissionPanel=new StackPanel{Spacing=8};
        void PermissionList(){permissionPanel.Children.Clear();foreach(var permission in Rows(perms[Value(role)!])){string key=permission.ToString();permissionPanel.Children.Add(new CheckBox{Content=key,Tag=key,IsChecked=u?["permissions"]?[key]?.GetValue<bool>()!=false});}}
        role.SelectionChanged+=(_,_)=>PermissionList();PermissionList();
        await Form("Працівник і права",Stack(name,login,password,role,specialty,active,Text("Доступні права"),permissionPanel),async()=>{var body=new Dictionary<string,object?>{{"name",name.Text},{"login",login.Text},{"role",Value(role)},{"specialty",specialty.Text},{"active",active.IsChecked==true},{"permissions",permissionPanel.Children.OfType<CheckBox>().ToDictionary(x=>x.Tag.ToString()!,x=>x.IsChecked==true)}};if(password.Password.Length>0)body["password"]=password.Password;await api.Send(u==null?"/users":"/users/"+S(u,"id"),u==null?"POST":"PATCH",body);});
    }
    async Task PasswordForm(){var old=new PasswordBox{Header="Поточний пароль"};var next=new PasswordBox{Header="Новий пароль (12+ символів)"};await Form("Зміна пароля",Stack(old,next),async()=>{await api.Send("/auth/password","POST",new{currentPassword=old.Password,newPassword=next.Password});api.Token="";user=null;Login();});}
    async Task Qr(string uid){var svg=await api.Qr(uid);using var stream=new InMemoryRandomAccessStream();using(var writer=new DataWriter(stream.GetOutputStreamAt(0))){writer.WriteString(svg);await writer.StoreAsync();await writer.FlushAsync();}stream.Seek(0);var source=new SvgImageSource();await source.SetSourceAsync(stream);var image=new Image{Source=source,Width=260,Height=260};var d=new ContentDialog{XamlRoot=Root.XamlRoot,Title="QR-код ліжка",Content=Stack(image,Text("Відскануйте камерою телефону в мережі центру.")),PrimaryButtonText="Зберегти SVG",CloseButtonText="Закрити",RequestedTheme=ElementTheme.Dark};if(await d.ShowAsync()==ContentDialogResult.Primary){var picker=new FileSavePicker{SuggestedFileName="RehaFlow-bed"};picker.FileTypeChoices.Add("QR SVG",new List<string>{".svg"});WinRT.Interop.InitializeWithWindow.Initialize(picker,WinRT.Interop.WindowNative.GetWindowHandle(this));var file=await picker.PickSaveFileAsync();if(file!=null)await FileIO.WriteTextAsync(file,svg);}}
}
