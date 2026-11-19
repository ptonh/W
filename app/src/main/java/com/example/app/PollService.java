package com.example.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台消息提醒：软件不在前台时，由这个前台服务每隔几秒直接问服务器有没有新消息 / 来电，
 * 有就发系统通知。不依赖网页脚本（网页在后台会被系统冻住）。
 * 软件在前台时不查，由网页自己同步。
 */
public class PollService extends Service {

    private static final long POLL_MS = 8000;   // 查询间隔（毫秒）。免费版 Worker 每天约 10 万次请求，别调太小
    private static final long PAGE_ALIVE_MS = 20000; // 网页 20 秒内同步过，就认为它没被冻住，不重复查
    // 常驻通知的渠道：创建时就是"已关闭"（IMPORTANCE_NONE），安装后通知栏里不会出现这条通知，服务照常运行。
    // 想让它显示出来时，在系统里把这个类别打开即可。如果发现关闭后软件更容易被系统杀掉，
    // 把 IMPORTANCE_NONE 改成 IMPORTANCE_MIN，并把渠道名 chat_keep3 换成新的（如 chat_keep4）。
    private static final String CH_KEEP = "chat_keep3";
    private static final int KEEP_IMPORTANCE = NotificationManager.IMPORTANCE_NONE;
    private static final int NID = 2001;

    private volatile boolean running = false;
    private Thread th;
    private final Map<Integer, Integer> unread = new HashMap<Integer, Integer>();
    private String lastCallId = "";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                try {
                    nm.deleteNotificationChannel("chat_keep");
                    nm.deleteNotificationChannel("chat_keep2");
                } catch (Throwable ignored) {
                }
                NotificationChannel c = new NotificationChannel(CH_KEEP, "后台保持连接", KEEP_IMPORTANCE);
                c.setShowBadge(false);
                c.enableVibration(false);
                c.setSound(null, null);
                c.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
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
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pi)
                    .build();
            startForeground(NID, n);
        } catch (Throwable ignored) {
        }
        Notifier.init(this);
        if (!running) {
            running = true;
            th = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            });
            th.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (th != null) th.interrupt();
        super.onDestroy();
    }

    private void loop() {
        while (running) {
            try {
                if (MainActivity.visible) {
                    unread.clear();
                    lastCallId = "";
                } else if (System.currentTimeMillis() - Notifier.pageSyncAt < PAGE_ALIVE_MS) {
                    // 网页还在自己同步（没被系统冻住），由网页发通知，这里不重复查询
                } else {
                    pollOnce();
                }
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    private void pollOnce() throws Exception {
        SharedPreferences ap = getSharedPreferences("app_prefs", MODE_PRIVATE);
        SharedPreferences st = getSharedPreferences("bg_state", MODE_PRIVATE);
        String chat = ap.getString("chat_url", "");
        String name = st.getString("name", "");
        String token = st.getString("token", "");
        if (chat.length() == 0 || name.length() == 0 || token.length() == 0) return;

        Uri u = Uri.parse(chat);
        String base = u.getScheme() + "://" + u.getAuthority();
        long after = Math.max(Math.max(st.getLong("polled", 0), st.getLong("seq", 0)), Notifier.lastSeq);
        if (after <= 0) return; // 还不知道网页同步到哪了，不能乱查，否则会把历史消息全通知一遍

        JSONObject cfg;
        try {
            cfg = new JSONObject(st.getString("cfg", "{}"));
        } catch (Exception e) {
            cfg = new JSONObject();
        }
        if (!cfg.optBoolean("on", false)) return; // 网页设置里没开"系统通知"

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/api/sync?after=" + after).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("x-name", enc(name));
            c.setRequestProperty("x-token", token);
            c.setRequestProperty("x-grant", st.getString("grant", ""));
            c.setRequestProperty("x-lgrant", st.getString("lgrant", ""));
            if (c.getResponseCode() != 200) return;
            String callH = c.getHeaderField("x-call");
            String body = readAll(c.getInputStream());
            handleCall(callH == null ? "" : callH, cfg);
            handleMessages(new JSONArray(body), after, name, cfg, st);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
    }

    private static String roomTitle(JSONObject cfg, int room) {
        JSONObject rs = cfg.optJSONObject("rooms");
        JSONObject r = rs == null ? null : rs.optJSONObject(String.valueOf(room));
        return r == null ? "" : r.optString("t", "");
    }

    private static String nick(JSONObject cfg, String user) {
        JSONObject ns = cfg.optJSONObject("nicks");
        String n = ns == null ? "" : ns.optString(user, "");
        return n.length() > 0 ? n : user;
    }

    /** x-call 的格式：通话编号.状态.o/i.聊天室.是否连通.是否语音 */
    private void handleCall(String h, JSONObject cfg) {
        String[] p = h.split("\\.");
        boolean ring = p.length >= 4 && "ring".equals(p[1]) && "i".equals(p[2]);
        if (ring) {
            if (!p[0].equals(lastCallId)) {
                lastCallId = p[0];
                int room = 1;
                try {
                    room = Integer.parseInt(p[3]);
                } catch (Exception ignored) {
                }
                String title = roomTitle(cfg, room);
                boolean voice = p.length >= 6 && "1".equals(p[5]);
                Notifier.showCall(this, title.length() > 0 ? title : "来电",
                        voice ? "邀请你语音通话" : "邀请你视频通话");
            }
        } else if (lastCallId.length() > 0) {
            lastCallId = "";
            Notifier.cancelCall(this);
        }
    }

    private void handleMessages(JSONArray arr, long after, String me, JSONObject cfg,
                                SharedPreferences st) {
        long maxSeq = after;
        Map<Integer, Integer> add = new LinkedHashMap<Integer, Integer>();
        Map<Integer, String> lastText = new HashMap<Integer, String>();
        Map<Integer, String> lastUser = new HashMap<Integer, String>();
        JSONObject rs = cfg.optJSONObject("rooms");

        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            long seq = m.optLong("seq", 0);
            if (seq > maxSeq) maxSeq = seq;
            if (seq <= after || m.optInt("del", 0) == 1) continue;
            String user = m.optString("user", "");
            if (user.equals(me)) continue;
            int room = m.optInt("room", 1);
            if (room <= 0) room = 1;
            JSONObject rc = rs == null ? null : rs.optJSONObject(String.valueOf(room));
            if (rc != null && rc.optInt("n", 1) == 0) continue; // 这个聊天室关了通知

            String kind = m.optString("kind", "");
            String text;
            if ("image".equals(kind)) {
                text = "[图片]";
            } else if ("audio".equals(kind)) {
                text = "[语音]";
            } else if ("file".equals(kind)) {
                String fn = "";
                try {
                    fn = new JSONObject(m.optString("meta", "{}")).optString("n", "");
                } catch (Exception ignored) {
                }
                text = "[文件] " + fn;
            } else {
                text = m.optString("content", "").replaceAll("\\s+", " ").trim();
            }
            if (text.length() == 0) continue;
            if (text.length() > 80) text = text.substring(0, 80);

            Integer old = add.get(room);
            add.put(room, old == null ? 1 : old + 1);
            lastText.put(room, text);
            lastUser.put(room, user);
        }

        for (Map.Entry<Integer, Integer> e : add.entrySet()) {
            int room = e.getKey();
            Integer prev = unread.get(room);
            int n = (prev == null ? 0 : prev) + e.getValue();
            unread.put(room, n);
            String who = nick(cfg, lastUser.get(room));
            String title = roomTitle(cfg, room);
            if (title.length() == 0) title = room == 1 ? "公共聊天室" : who;
            String body = (n > 1 ? "[" + n + "条] " : "") + (room == 1 ? who + "：" : "") + lastText.get(room);
            Notifier.showMsg(this, title, body, "room-" + room);
        }

        if (maxSeq > after) {
            if (maxSeq > Notifier.lastSeq) Notifier.lastSeq = maxSeq;
            st.edit().putLong("polled", maxSeq).apply();
        }
    }
}
