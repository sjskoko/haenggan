package io.haenggan.diary;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Keeps the local OAuth listener schedulable while the user is in the browser. */
public final class LoginService extends Service {
    static final String RECEIVER = "result";
    static final int READY = 1, EXPIRED = 2;
    private static final int NOTIFICATION = 23031;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ResultReceiver receiver;
    private final Runnable deadline = () -> expire();

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        receiver = intent.getParcelableExtra(RECEIVER, ResultReceiver.class);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("chatgpt-connect", "ChatGPT 계정 연결", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "chatgpt-connect")
            .setSmallIcon(io.haenggan.diary.R.drawable.ic_notification)
            .setContentTitle("ChatGPT 연결을 기다리고 있어요")
            .setContentText("승인을 마친 뒤 행간으로 돌아오세요")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
        else startForeground(NOTIFICATION, notification);
        handler.removeCallbacks(deadline);
        handler.postDelayed(deadline, 170000); // Stop before the platform's three-minute limit on every OS.
        if (receiver != null) receiver.send(READY, Bundle.EMPTY);
        return START_NOT_STICKY;
    }

    private void expire() {
        if (receiver != null) receiver.send(EXPIRED, Bundle.EMPTY);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public void onTimeout(int startId) { expire(); }
    @Override public void onTimeout(int startId, int foregroundServiceType) { expire(); }
    @Override public void onDestroy() { handler.removeCallbacks(deadline); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
