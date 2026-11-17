package com.example.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;

/**
 * 常驻前台服务：只负责让软件进程在后台不被系统冻结，
 * 这样聊天页的轮询才能继续跑，来消息 / 来电时才能弹系统通知。
 */
public class KeepAliveService extends Service {

    private static final String CH_KEEP = "chat_keep";
    private static final int NID = 2001;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                NotificationChannel c = new NotificationChannel(CH_KEEP, "后台保持连接",
                        NotificationManager.IMPORTANCE_LOW);
                c.setShowBadge(false);
                nm.createNotificationChannel(c);
            }
            Intent it = new Intent(this, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new NotificationCompat.Builder(this, CH_KEEP)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("聊天保持连接中")
                    .setContentText("用于在后台接收消息和来电")
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(pi)
                    .build();
            startForeground(NID, n);
            MainActivity.slog("后台保持服务已运行（前台服务）");
        } catch (Throwable e) {
            MainActivity.slog("后台保持服务出错：" + e);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        MainActivity.slog("后台保持服务被停止");
        super.onDestroy();
    }
}
