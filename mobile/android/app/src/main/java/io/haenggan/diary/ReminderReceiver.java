package io.haenggan.diary;

import android.app.*;
import android.content.*;
import android.os.Build;
import java.time.*;

public final class ReminderReceiver extends BroadcastReceiver {
    static final String CHANNEL="reflection",ACTION="io.haenggan.diary.REMIND";
    static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("reminders",Context.MODE_PRIVATE);}
    static void schedule(Context c) {
        AlarmManager manager=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent intent=PendingIntent.getBroadcast(c,1,new Intent(c,ReminderReceiver.class).setAction(ACTION),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        manager.cancel(intent);if(!prefs(c).getBoolean("enabled",false))return;
        ZonedDateTime now=ZonedDateTime.now(DiaryStore.SEOUL),next=now.withHour(23).withMinute(30).withSecond(0).withNano(0);if(!next.isAfter(now))next=next.plusDays(1);
        if(Build.VERSION.SDK_INT<31||manager.canScheduleExactAlarms())manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next.toInstant().toEpochMilli(),intent);
        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next.toInstant().toEpochMilli(),intent);
    }
    static void show(Context c) {
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"하루 회고",NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("하루를 돌아볼 시간을 알려드려요");channel.enableVibration(true);channel.setVibrationPattern(new long[]{0,220,120,220});channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);manager.createNotificationChannel(channel);
        Intent open=new Intent(c,MainActivity.class).putExtra("write",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap=PendingIntent.getActivity(c,2,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("오늘을 돌아볼 시간")
            .setContentText("오늘 하루 어떤 순간이 마음에 남았나요?").setStyle(new Notification.BigTextStyle().bigText("오늘 하루 어떤 순간이 마음에 남았나요?\n눌러서 행간에 이야기를 남겨보세요."))
            .setContentIntent(tap).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_REMINDER)
            .addAction(new Notification.Action.Builder(null,"지금 회고하기",tap).build()).build();
        if(manager.areNotificationsEnabled())manager.notify(1,n);
    }
    @Override public void onReceive(Context c,Intent intent) {
        if(ACTION.equals(intent.getAction())&&prefs(c).getBoolean("enabled",false)&&!DiaryStore.today().equals(prefs(c).getString("lastSavedDay","")))show(c);
        schedule(c);
    }
}
