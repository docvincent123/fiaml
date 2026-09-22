package com.quremed.rehaflow;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Visible, user-controlled LAN polling. Session credentials exist only in memory. */
public final class ShiftAlertsService extends Service {
    public static volatile boolean running=false;
    private static volatile boolean activityVisible=false;
    public static synchronized void setActivityVisible(Context context, boolean visible) {
        activityVisible=visible;
        if(visible) context.getSystemService(NotificationManager.class).cancel(TASK_ID);
    }
    public static volatile String state="Сповіщення не ввімкнено";
    private static final String STATUS="rehaflow_connection", EVENTS="rehaflow_updates_silent_v1", TASKS="rehaflow_tasks_v3";
    private static final int STATUS_ID=41, EVENT_ID=42, TASK_ID=43;
    private ScheduledExecutorService executor;
    private volatile String server="",bearer="",user="";
    private volatile int generation=0;
    private volatile boolean ended=false;
    @Override public IBinder onBind(Intent intent){return null;}
    static void channels(Context context){
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        NotificationChannel status=new NotificationChannel(STATUS,"Зв’язок із центром",NotificationManager.IMPORTANCE_LOW);
        status.setSound(null,null);status.setShowBadge(false);
        NotificationChannel events=new NotificationChannel(EVENTS,"Інформаційні повідомлення",NotificationManager.IMPORTANCE_LOW);
        events.setSound(null,null);events.enableVibration(false);
        NotificationChannel tasks=new NotificationChannel(TASKS,"Нові завдання медсестри та реабілітолога",NotificationManager.IMPORTANCE_HIGH);
        tasks.setSound(android.net.Uri.parse("android.resource://"+context.getPackageName()+"/raw/task_ringtone"),new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
        tasks.enableVibration(true);tasks.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(status);manager.createNotificationChannel(events);manager.createNotificationChannel(tasks);
    }
    static PendingIntent open(Context context,String path){
        Intent intent=new Intent(context,com.quremed.rehaflow.nativeui.NativeActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("destination",path);
        return PendingIntent.getActivity(context,path.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    public static void alert(Context context,String title,String text,String path){
        postAlert(context,title,text,path,false);
    }
    public static void testSound(Context context){
        postAlert(context,"RehaFlow · перевірка звуку","Тестове сповіщення завдань","/settings",true);
    }
    private static synchronized void postAlert(Context context,String title,String text,String path,boolean taskSound){
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        channels(context);
        Notification notification=new Notification.Builder(context,taskSound?TASKS:EVENTS).setSmallIcon(com.quremed.rehaflow.R.drawable.notification_icon)
            .setContentTitle(title).setContentText(text).setContentIntent(open(context,path)).setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_MESSAGE).build();
        if(taskSound && !activityVisible) notification.flags |= Notification.FLAG_INSISTENT;
        try{context.getSystemService(NotificationManager.class).notify(taskSound?TASK_ID:EVENT_ID,notification);}catch(SecurityException ignored){}
    }
    private Notification statusNotification(String text){
        return new Notification.Builder(this,STATUS).setSmallIcon(com.quremed.rehaflow.R.drawable.notification_icon)
            .setContentTitle("RehaFlow · сповіщення зміни").setContentText(text).setOngoing(true)
            .setContentIntent(open(this,"/settings")).setOnlyAlertOnce(true).build();
    }
    private void status(String text){
        state=text;
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        try{getSystemService(NotificationManager.class).notify(STATUS_ID,statusNotification(text));}catch(SecurityException ignored){}
    }
    @Override public void onCreate(){super.onCreate();channels(this);executor=Executors.newSingleThreadScheduledExecutor();}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        String next=intent.getStringExtra("server"),token=intent.getStringExtra("token"),id=intent.getStringExtra("user");
        try{next=MainActivity.normalize(next);}catch(Exception e){stopSelf();return START_NOT_STICKY;}
        if(token==null||token.length()>8192||id==null||!id.matches("[0-9a-fA-F-]{36}")){stopSelf();return START_NOT_STICKY;}
        generation++;server=next;bearer=token;user=id;ended=false;
        state="Перевіряємо зв’язок із центром…";
        if(Build.VERSION.SDK_INT>=29)startForeground(STATUS_ID,statusNotification(state),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(STATUS_ID,statusNotification(state));
        if(!running){running=true;executor.scheduleWithFixedDelay(this::poll,0,15,TimeUnit.SECONDS);}
        return START_NOT_STICKY;
    }
    private void poll(){
        if(ended)return;final int version=generation;final String currentServer=server,currentUser=user,currentToken=bearer;
        HttpURLConnection connection=null;
        try{
            connection=(HttpURLConnection)new URL(currentServer+"/api/care/notification-feed").openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(8000);connection.setReadTimeout(8000);
            connection.setRequestProperty("Authorization","Bearer "+currentToken);connection.setRequestProperty("Accept","application/json");
            int code=connection.getResponseCode();if(version!=generation||ended)return;
            if(code==401||code==403){alert(this,"Увійдіть у RehaFlow","Сесію завершено. Відкрийте програму для сповіщень.","/");stopSelf();return;}
            if(code!=200)throw new IOException();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream input=connection.getInputStream()){byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){bytes.write(buffer,0,count);if(bytes.size()>262144)throw new IOException();}}
            JSONObject data=new JSONObject(bytes.toString("UTF-8"));
            if(version!=generation||ended)return;
            if(!data.getBoolean("active")){if(data.optBoolean("pending",false)){status("Очікуємо підтвердження зміни адміністратором");return;}state="Зміна не активна";stopSelf();return;}
            JSONArray events=data.getJSONArray("events");
            String key="seen:"+currentServer+":"+currentUser;
            android.content.SharedPreferences preferences=getSharedPreferences("shift-alerts",MODE_PRIVATE);
            Set<String> seen=preferences.getStringSet(key,Collections.emptySet()),now=new HashSet<>();
            int fresh=0,tasks=0;String path="/";
            for(int i=0;i<events.length();i++){JSONObject event=events.getJSONObject(i);String eventId=event.getString("id");now.add(eventId);if(!seen.contains(eventId)){fresh++;if("task".equals(event.optString("kind")))tasks++;path=event.getString("path");}}
            if(version!=generation||ended)return;
            if(fresh>0)postAlert(this,tasks>0?"RehaFlow · нове завдання":"RehaFlow · нові події","Нових подій: "+fresh+". Відкрийте застосунок.",tasks>0?"/tasks":path,tasks>0 && data.optBoolean("taskAlerts",false));
            // Keep recently seen events even when they temporarily leave the server's feed.
            if(seen.size()+now.size()<=2000)now.addAll(seen);
            preferences.edit().putStringSet(key,now).apply();
            status("Зв’язок активний · перевірка кожні 15 секунд");
        }catch(Exception e){if(!ended&&version==generation)status("Немає зв’язку. Перевірте Wi-Fi, сервер і сертифікат.");}
        finally{if(connection!=null)connection.disconnect();}
    }
    @Override public void onTimeout(int startId,int foregroundServiceType){
        state="Android зупинив фонову синхронізацію. Відкрийте RehaFlow.";
        alert(this,"Відкрийте RehaFlow",state,"/settings");stopSelf();
    }
    @Override public void onDestroy(){getSystemService(NotificationManager.class).cancel(TASK_ID);ended=true;generation++;running=false;bearer="";server="";user="";if(executor!=null)executor.shutdownNow();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
