package com.example.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
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
 * 只用一个最简单的渠道，写法和那个能直接响、直接弹横幅的软件一致：
 * 默认重要性，不指定声音、振动、灯光，让系统自己决定。
 */
final class Notifier {

    static final String CH = "chat_notify";
    static final int CALL_NID = 1001;
    static final long CALL_RING_MS = 60 * 1000L; // 与聊天脚本的 RING_MS 一致

    /** 已经通知过的最大消息序号（网页和后台查询共用，防止同一条消息通知两次） */
    static volatile long lastSeq = 0;

    private static final Set<Integer> POSTED = Collections.synchronizedSet(new HashSet<Integer>());

    private Notifier() {
    }

    /** 建渠道；顺便删掉以前版本留下的旧渠道 */
    static void init(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String[] old = {"chat_msg", "chat_msg2", "chat_call"};
        for (int i = 0; i < old.length; i++) {
            try {
                nm.deleteNotificationChannel(old[i]);
            } catch (Throwable ignored) {
            }
        }
        nm.createNotificationChannel(new NotificationChannel(CH, "聊天通知",
                NotificationManager.IMPORTANCE_DEFAULT));
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

    /** 消息通知：每个聊天室一个位置（tag 形如 room-6），新消息顶替旧的 */
    static void showMsg(Context c, String title, String body, String tag) {
        init(c);
        Notification n = new NotificationCompat.Builder(c, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title.length() > 0 ? title : "新消息")
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(open(c, false))
                .build();
        int id = tag.length() > 0 ? tag.hashCode() : (int) System.currentTimeMillis();
        POSTED.add(id);
        NotificationManagerCompat.from(c).notify(id, n);
    }

    /** 来电通知：声音重复响，直到接听 / 取消 / 超时 */
    static void showCall(Context c, String title, String body) {
        init(c);
        PendingIntent pi = open(c, true);
        Notification n = new NotificationCompat.Builder(c, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title.length() > 0 ? title : "来电")
                .setContentText(body)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setTimeoutAfter(CALL_RING_MS)
                .build();
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
