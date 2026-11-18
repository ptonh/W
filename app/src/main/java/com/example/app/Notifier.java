package com.example.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 统一发系统通知（网页转来的、后台查询到的都走这里）。
 * 渠道写法：消息和来电各一个渠道，声音、振动都明确设置（声音用系统默认通知音 / 来电铃声）。
 */
final class Notifier {

    static final String CH_MSG = "chat_msg2";
    static final String CH_CALL = "chat_call";
    static final int CALL_NID = 1001;
    static final long CALL_RING_MS = 60 * 1000L; // 与聊天脚本的 RING_MS 一致

    /** 已经通知过的最大消息序号（网页和后台查询共用，防止同一条消息通知两次） */
    static volatile long lastSeq = 0;

    /** 网页最近一次向服务器同步的时间；网页还在同步说明没被冻住，后台服务就不重复查 */
    static volatile long pageSyncAt = 0;

    private static final Set<Integer> POSTED = Collections.synchronizedSet(new HashSet<Integer>());

    private Notifier() {
    }

    /** 建渠道（已存在的不会被改动）；顺便删掉更早版本留下的旧渠道 */
    static void init(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String[] old = {"chat_msg", "chat_notify"};
        for (int i = 0; i < old.length; i++) {
            try {
                nm.deleteNotificationChannel(old[i]);
            } catch (Throwable ignored) {
            }
        }

        NotificationChannel msg = new NotificationChannel(CH_MSG, "聊天消息",
                NotificationManager.IMPORTANCE_HIGH);
        msg.setDescription("新消息提醒（横幅、声音、振动）");
        AudioAttributes ma = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        msg.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), ma);
        msg.enableVibration(true);
        msg.setVibrationPattern(new long[]{0, 250, 150, 250});
        msg.enableLights(true);
        msg.setShowBadge(true);
        msg.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(msg);

        NotificationChannel call = new NotificationChannel(CH_CALL, "来电",
                NotificationManager.IMPORTANCE_HIGH);
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        call.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), aa);
        call.enableVibration(true);
        call.setVibrationPattern(new long[]{0, 600, 400, 600, 400, 600});
        call.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(call);
    }

    /** 已通知到的最大序号：内存里的和后台查询记下来的，取大的 */
    static long notifiedSeq(Context c) {
        long p = c.getSharedPreferences("bg_state", Context.MODE_PRIVATE).getLong("polled", 0);
        return Math.max(lastSeq, p);
    }

    private static PendingIntent open(Context c, boolean call) {
        Intent it = new Intent(c, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (call) it.putExtra("call", true);
        return PendingIntent.getActivity(c, call ? 1 : 0, it, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** 消息通知：每个聊天室一个位置（tag 形如 room-6），新消息先取消旧的再发，保证重新提醒 */
    static void showMsg(Context c, String title, String body, String tag) {
        init(c);
        Notification n = new NotificationCompat.Builder(c, CH_MSG)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentTitle(title.length() > 0 ? title : "新消息")
                .setContentText(body)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open(c, false))
                .build();
        int id = tag.length() > 0 ? tag.hashCode() : (int) System.currentTimeMillis();
        POSTED.add(id);
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        nm.cancel(id);
        nm.notify(id, n);
    }

    /** 来电通知：铃声重复响，直到接听 / 取消 / 超时 */
    static void showCall(Context c, String title, String body) {
        init(c);
        PendingIntent pi = open(c, true);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_CALL)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle(title.length() > 0 ? title : "来电")
                .setContentText(body)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setTimeoutAfter(CALL_RING_MS);
        if (Build.VERSION.SDK_INT < 26) {
            b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    android.media.AudioManager.STREAM_RING);
            b.setVibrate(new long[]{0, 600, 400, 600, 400, 600});
        }
        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT;
        NotificationManagerCompat.from(c).notify(CALL_NID, n);
    }

    static void cancelCall(Context c) {
        NotificationManagerCompat.from(c).cancel(CALL_NID);
    }

    /** 回到软件后，清掉所有消息通知 */
    static void cancelMsgs(Context c) {
        List<Integer> ids;
        synchronized (POSTED) {
            ids = new ArrayList<Integer>(POSTED);
            POSTED.clear();
        }
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        for (int i = 0; i < ids.size(); i++) nm.cancel(ids.get(i));
    }
}
