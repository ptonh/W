package com.example.app;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.ScrollView;
import java.text.SimpleDateFormat;
import java.util.Date;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.MediaScannerConnection;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.text.InputType;
import android.util.Base64;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 聊天网页的外壳。
 * - 聊天网址可在软件里设置（首次打开弹窗；之后长按桌面图标选"设置地址"，或页面打不开时点"修改地址"）。
 * - 视频嵌在聊天页里（iframe），所以软件负责：权限、选文件、下载、全屏、外链、系统通知、来电铃声、画中画。
 */
public class MainActivity extends Activity {

    private static final String ACTION_SETTINGS = "com.example.app.SETTINGS";

    private static final int REQ_MEDIA = 1;
    private static final int REQ_STORAGE = 2;
    private static final int REQ_FILE = 3;

    // 下载时网页把文件转成 base64 文本传给软件，限制大小防止内存溢出（约 50MB 文件）
    private static final int MAX_DATA_CHARS = 70 * 1024 * 1024;

    // 视频域名默认值（用户不填写时使用）
    private static final String DEFAULT_VIDEO_HOST = "";

    // 通知渠道：消息（系统默认通知音）、来电（系统来电铃声，持续响）
    private static final String CH_MSG = "chat_msg";
    private static final String CH_CALL = "chat_call";
    private static final int CALL_NID = 1001;
    private static final long CALL_RING_MS = 60 * 1000L; // 与聊天脚本的 RING_MS 一致

    // 记录 <a download="文件名"> 的文件名，下载时用
    private static final String HOOK_JS =
            "(function(){if(window.__hk)return;window.__hk=1;"
            + "document.addEventListener('click',function(e){var a=e.target;"
            + "while(a&&a.tagName!=='A')a=a.parentNode;"
            + "if(a&&a.hasAttribute('download'))window.__dlName=a.getAttribute('download')||'';},true);})();";

    // 把网页的 Notification / showNotification 转给软件；监听视频页的 connected / ended / pip-fail
    private static final String NOTIFY_JS =
              "(function(){if(window.__nt)return;window.__nt=1;\n"
            + "function lg(m){try{HostBridge.log('[网页] '+m);}catch(e){}}\n"
            + "function send(t,o){o=o||{};lg('收到通知请求 标题='+t+' tag='+o.tag+' hidden='+document.hidden);try{HostBridge.notify(String(t||''),String(o.body||''),String(o.tag||''));}catch(e){lg('交给软件失败 '+e);}}\n"
            + "function N(t,o){send(t,o);this.close=function(){};this.onclick=null;}\n"
            + "N.permission='granted';\n"
            + "N.requestPermission=function(cb){lg('页面申请通知权限，已直接答复granted');if(cb)cb('granted');return Promise.resolve('granted');};\n"
            + "try{Object.defineProperty(window,'Notification',{value:N,configurable:true,writable:true});}catch(e){window.Notification=N;}\n"
            + "try{if(window.ServiceWorkerRegistration){ServiceWorkerRegistration.prototype.showNotification=function(t,o){lg('走了ServiceWorker的showNotification');send(t,o);return Promise.resolve();};}}catch(e){}\n"
            + "lg('注入完成 url='+location.href+' 有serviceWorker='+('serviceWorker' in navigator)+' 权限='+Notification.permission);\n"
            + "function wrap(n){try{var f=window[n];if(typeof f!=='function'||f.__w)return;var w=function(){var a=[];try{a.push('sysOk='+sysOk(),'hidden='+document.hidden,'focus='+document.hasFocus(),'ntf='+lsGet('ntf'));}catch(x){a.push('读状态失败 '+x);}lg(n+' 被调用 '+a.join(' '));return f.apply(this,arguments);};w.__w=1;window[n]=w;}catch(e){lg('包装失败 '+n+' '+e);}}\n"
            + "['sysNotice','backlogNotice','sysShow','newMsgNotice','vcShowIn'].forEach(wrap);\n"
            + "try{var onm=window.newMsgNotice;if(typeof onm==='function'&&!onm.__f){var nw=function(list){var rv=onm.apply(this,arguments);try{if(!document.hidden){var m=list[list.length-1],r=rmOf(m);if(sysOk()&&rsNtf(r)){send(roomTitle(r),{body:(r===1?dispName(m.user)+'：':'')+msgTxt(m).slice(0,80)+(list.length>1?'（共 '+list.length+' 条新消息）':''),tag:'room-'+r});}}}catch(x){lg('前台通知失败 '+x);}return rv;};nw.__f=1;nw.__w=1;window.newMsgNotice=nw;}}catch(e){lg('包装newMsgNotice失败 '+e);}\n"
            + "document.addEventListener('visibilitychange',function(){lg('visibilitychange hidden='+document.hidden);});\n"
            + "window.addEventListener('focus',function(){lg('页面获得焦点');});\n"
            + "window.addEventListener('blur',function(){lg('页面失去焦点');});\n"
            + "window.addEventListener('error',function(e){lg('页面错误 '+e.message+' 第'+(e.lineno||'?')+'行');});\n"
            + "window.addEventListener('unhandledrejection',function(e){lg('未处理的Promise错误 '+((e.reason&&e.reason.message)||e.reason));});\n"
            + "var lt=Date.now();setInterval(function(){var n=Date.now(),g=n-lt;lt=n;if(g>4000)lg('网页脚本被暂停了约 '+Math.round(g/1000)+' 秒 hidden='+document.hidden);},1000);\n"
            + "window.__pipPrep=function(){try{var L=document.getElementById('vcL');if(!L)return;L.classList.remove('pip');if(L.classList.contains('mini')){if(window.vcMini)vcMini(false);else L.classList.remove('mini','bubble');}}catch(e){}};\n"
            + "window.addEventListener('message',function(e){var d=e.data;if(!d||d.source!=='vcall')return;var F=document.getElementById('vcF');if(!F||e.source!==F.contentWindow)return;lg('视频页消息 type='+d.type+(d.reason?' reason='+d.reason:''));if(d.type==='pip-fail'){e.stopImmediatePropagation();try{HostBridge.pip();}catch(x){}}},true);\n"
            + "var last=null;setInterval(function(){var on=false;try{on=!!(document.getElementById('vcF')&&vc&&vc.id&&vc.st==='act');}catch(x){}if(on!==last){last=on;try{HostBridge.call(on);}catch(x){}}},1000);\n"
            + "})();\n";
    private FrameLayout root;
    private WebView webView;
    private AppChrome chrome;
    private SharedPreferences prefs;

    private String chatUrl = "";
    private String chatHost = "";
    private String videoHost = "";

    private AlertDialog settingsDialog;

    private volatile boolean inCall = false;
    private volatile boolean resumed = false;

    // 日志（内存里最多 600 行；点界面上的"日志"小按钮查看 / 一键复制）
    private static final List<String> LOGS = new ArrayList<String>();
    private static final SimpleDateFormat LOG_FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private TextView logBtn;
    private static String lastNtfKey = "";
    private static long lastNtfAt = 0;

    // 等待用户授权的摄像头/麦克风请求
    private PermissionRequest pendingMedia;
    private List<String> pendingMediaRes;

    // 等待用户授权存储权限的下载
    private String pendingName;
    private byte[] pendingBytes;

    private ValueCallback<Uri[]> fileCb;

    private View customView;
    private WebChromeClient.CustomViewCallback customCb;

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        chatUrl = prefs.getString("chat_url", "");
        videoHost = prefs.getString("video_host", DEFAULT_VIDEO_HOST);
        chatHost = hostOf(chatUrl);

        createChannels(); // 安卓 13 会在这时弹出"允许通知"
        startKeepAlive();
        applyCallFlags(getIntent());

        root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        addLogButton();
        addLog("==== 软件启动 ====");
        addLog(deviceInfo());

        setupWebView();

        if (chatUrl.length() > 0) {
            loadChat();
        }
        if (chatUrl.length() == 0 || ACTION_SETTINGS.equals(getIntent().getAction())) {
            showSettings();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyCallFlags(intent);
        if (intent != null && ACTION_SETTINGS.equals(intent.getAction())) {
            showSettings();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        addLog("onResume 回到前台");
        cancelCallNotification(); // 回到软件，来电界面已在网页里，停掉通知铃声
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        addLog("onPause 离开前台");
        if (Build.VERSION.SDK_INT >= 21) {
            android.webkit.CookieManager.getInstance().flush();
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            chrome.onHideCustomView();
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
        }
    }

    // 按 Home 键或切换应用时：正在全屏看视频 / 正在通话，则进入画中画
    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        addLog("按Home/离开 inCall=" + inCall + " 全屏视频=" + (customView != null) + " 系统=" + Build.VERSION.SDK_INT);
        if (Build.VERSION.SDK_INT >= 26 && (customView != null || inCall)
                && !isInPictureInPictureMode()) {
            enterPip();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        addLog("画中画状态变化 进入=" + isInPictureInPictureMode);
        if (logBtn != null) logBtn.setVisibility(isInPictureInPictureMode ? View.GONE : View.VISIBLE);
        if (isInPictureInPictureMode) {
            setImmersiveMode(true);
        } else if (customView == null) {
            setImmersiveMode(false);
        }
    }

    /** 进入画中画：通话时是竖屏小窗（9:16），全屏播放视频时是横屏（16:9） */
    @TargetApi(26)
    private void enterPip() {
        try {
            if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                toast("这台手机不支持画中画");
                return;
            }
            // 通话时先让聊天页把通话层恢复成全屏，小窗里才能看到视频
            runJs("window.__pipPrep&&window.__pipPrep()");
            Rational ratio = (inCall && customView == null) ? new Rational(9, 16) : new Rational(16, 9);
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(ratio)
                    .build();
            enterPictureInPictureMode(params);
            addLog("已请求进入画中画 比例=" + ratio);
        } catch (Exception e) {
            addLog("进入画中画失败：" + e);
        }
    }

    // ---------------------------------------------------------------- 后台保持

    /** 启动前台服务（通知栏会有一条常驻小通知），防止系统在后台冻结整个软件 */
    private void startKeepAlive() {
        try {
            Intent sv = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(sv);
            } else {
                startService(sv);
            }
            addLog("已请求启动后台保持服务");
        } catch (Throwable e) {
            addLog("启动后台保持服务失败：" + e);
        }
    }

    /** 给服务等其他类写日志用 */
    static void slog(String m) {
        synchronized (LOGS) {
            LOGS.add(LOG_FMT.format(new Date()) + " " + m);
            while (LOGS.size() > 600) LOGS.remove(0);
        }
    }

    // ---------------------------------------------------------------- 日志

    private void addLog(String m) {
        synchronized (LOGS) {
            LOGS.add(LOG_FMT.format(new Date()) + " " + m);
            while (LOGS.size() > 600) LOGS.remove(0);
        }
    }

    private String allLogs() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOGS) {
            for (String l : LOGS) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    private String deviceInfo() {
        String wv = "";
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
                if (pi != null) wv = " WebView=" + pi.versionName;
            }
        } catch (Throwable ignored) {
        }
        return "设备=" + Build.MANUFACTURER + " " + Build.MODEL + " 安卓=" + Build.VERSION.RELEASE
                + "(API" + Build.VERSION.SDK_INT + ")" + wv;
    }

    private void addLogButton() {
        logBtn = new TextView(this);
        logBtn.setText("日志");
        logBtn.setTextColor(0xFFFFFFFF);
        logBtn.setTextSize(12);
        logBtn.setGravity(Gravity.CENTER);
        logBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(dp(14));
        logBtn.setBackgroundDrawable(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.leftMargin = dp(6);
        lp.bottomMargin = dp(90);
        root.addView(logBtn, lp);
        final float[] st = new float[4];
        final boolean[] moved = new boolean[1];
        logBtn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        st[0] = e.getRawX();
                        st[1] = e.getRawY();
                        st[2] = v.getTranslationX();
                        st[3] = v.getTranslationY();
                        moved[0] = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - st[0], dy = e.getRawY() - st[1];
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved[0] = true;
                        if (moved[0]) {
                            v.setTranslationX(st[2] + dx);
                            v.setTranslationY(st[3] + dy);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved[0]) showLogs();
                        return true;
                }
                return false;
            }
        });
    }

    private void showLogs() {
        final String all = allLogs();
        String shown = all;
        if (shown.length() > 12000) shown = "…（只显示最后一部分，复制的是全部）\n" + shown.substring(shown.length() - 12000);
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setText(shown.length() == 0 ? "（还没有日志）" : shown);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("日志（" + LOGS.size() + " 行）")
                .setView(sv)
                .setPositiveButton("复制全部", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("log", all));
                            toast("日志已复制，可以去粘贴了");
                        }
                    }
                })
                .setNeutralButton("清空", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        synchronized (LOGS) {
                            LOGS.clear();
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        addLog("onStop 软件已不可见");
    }

    // ---------------------------------------------------------------- 系统通知

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CH_MSG, "聊天消息",
                NotificationManager.IMPORTANCE_HIGH));

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

    private void showSysNotification(String title, String body, String tag) {
        addLog("软件收到通知请求 标题=" + title + " 内容=" + body + " tag=" + tag
                + " 软件在前台=" + resumed);
        if (resumed && "vcall".equals(tag)) {
            addLog("→ 不弹：来电时软件在前台，网页里已有来电界面");
            return;
        }
        long now = System.currentTimeMillis();
        String key = tag + "|" + title + "|" + body;
        synchronized (LOGS) {
            if (key.equals(lastNtfKey) && now - lastNtfAt < 3000) {
                addLog("→ 不弹：3 秒内重复的同一条通知");
                return;
            }
            lastNtfKey = key;
            lastNtfAt = now;
        }
        try {
            boolean on = NotificationManagerCompat.from(this).areNotificationsEnabled();
            addLog("系统通知总开关=" + on);
            if (!on) {
                addLog("→ 不弹：系统里这个软件的通知被关了（设置→应用→通知）");
                return;
            }
            doShowNotification(title, body, tag);
        } catch (Throwable e) {
            addLog("→ 发通知出错：" + e);
        }
    }

    private void doShowNotification(String title, String body, String tag) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            addLog("→ 拿不到 NotificationManager");
            return;
        }
        createChannels();
        boolean isCall = "vcall".equals(tag);
        String chId = isCall ? CH_CALL : CH_MSG;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = nm.getNotificationChannel(chId);
            addLog("渠道 " + chId + " 重要性=" + (ch == null ? "不存在" : String.valueOf(ch.getImportance()))
                    + "（0=已关闭 3=默认 4=高）");
            if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                addLog("→ 不弹：这个通知类别被你关了（设置→应用→通知→类别）");
                return;
            }
        }
        Intent it = new Intent(this, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (isCall) it.putExtra("call", true);
        PendingIntent pi = PendingIntent.getActivity(this, isCall ? 1 : 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT);
        int id;
        Notification n;
        if (isCall) {
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_CALL)
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
            n = b.build();
            n.flags |= Notification.FLAG_INSISTENT; // 铃声重复响，直到接听 / 取消 / 超时
            id = CALL_NID;
        } else {
            n = new NotificationCompat.Builder(this, CH_MSG)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(title.length() > 0 ? title : "新消息")
                    .setContentText(body)
                    .setAutoCancel(true)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pi)
                    .build();
            id = tag.length() > 0 ? tag.hashCode() : (int) System.currentTimeMillis();
        }
        nm.notify(id, n);
        String act = "";
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                act = " 通知栏里现有 " + nm.getActiveNotifications().length + " 条";
            } catch (Throwable ignored) {
            }
        }
        addLog("→ 已调用系统发通知 id=" + id + act);
    }

    private void cancelCallNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(CALL_NID);
    }

    /** 点来电通知进来时，锁屏上也能亮屏显示 */
    private void applyCallFlags(Intent it) {
        if (it != null && it.getBooleanExtra("call", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }

    // ---------------------------------------------------------------- WebView 设置

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        if (Build.VERSION.SDK_INT >= 17) {
            s.setMediaPlaybackRequiresUserGesture(false);
        }

        webView.addJavascriptInterface(new HostBridge(), "HostBridge");
        if (Build.VERSION.SDK_INT >= 26) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false); // 进后台后网页不被降级
        }

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                fetchAndSave(url, contentDisposition, mimetype);
            }
        });

        chrome = new AppChrome();
        webView.setWebViewClient(new AppClient());
        webView.setWebChromeClient(chrome);
    }

    private void loadChat() {
        if (chatUrl.length() > 0) {
            webView.loadUrl(chatUrl);
        }
    }

    private void runJs(String js) {
        if (Build.VERSION.SDK_INT >= 19) {
            webView.evaluateJavascript(js, null);
        } else {
            webView.loadUrl("javascript:" + js);
        }
    }

    // ---------------------------------------------------------------- 网址判断

    private static String hostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /** 补全 https://，并确认能解析出域名；不合格返回空字符串 */
    private static String normalizeUrl(CharSequence in) {
        String s = in == null ? "" : in.toString().trim();
        if (s.length() == 0) return "";
        String low = s.toLowerCase(Locale.ROOT);
        if (!low.startsWith("http://") && !low.startsWith("https://")) {
            s = "https://" + s;
        }
        return hostOf(s).length() == 0 ? "" : s;
    }

    private boolean hostAllowed(String host) {
        if (host == null || host.length() == 0) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals(chatHost)) return true;
        return videoHost.length() > 0 && host.equals(videoHost);
    }

    private void openExternal(String url) {
        try {
            Uri u = Uri.parse(url);
            String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            // 不允许 intent:、file:、content: 这类可能被滥用的地址
            if (sc.equals("intent") || sc.equals("file") || sc.equals("content") || sc.length() == 0) {
                return;
            }
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception e) {
            toast("没有可以打开这个链接的应用");
        }
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------------------------------------------------------- 设置弹窗

    private void showSettings() {
        if (settingsDialog != null && settingsDialog.isShowing()) return;
        final boolean first = chatUrl.length() == 0;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(8), p, 0);

        TextView t1 = new TextView(this);
        t1.setText("聊天网址（必填）");
        box.addView(t1);
        final EditText e1 = new EditText(this);
        e1.setHint("https://chat.example.com");
        e1.setSingleLine(true);
        e1.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e1.setText(chatUrl);
        box.addView(e1);

        TextView t2 = new TextView(this);
        t2.setText("\n视频页域名或网址（不填则使用默认，视频和聊天不在同一个域名时建议填写）");
        box.addView(t2);
        final EditText e2 = new EditText(this);
        e2.setHint("video.example.com（可留空）");
        e2.setSingleLine(true);
        e2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e2.setText(videoHost);
        box.addView(e2);

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(box)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .setCancelable(!first)
                .create();
        settingsDialog = d;

        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String u = normalizeUrl(e1.getText());
                        if (u.length() == 0) {
                            toast("请输入正确的聊天网址");
                            return;
                        }
                        // 有输入就用输入的，没输入就用默认值
                        String vh = hostOf(normalizeUrl(e2.getText()));
                        if (vh.length() == 0) {
                            vh = DEFAULT_VIDEO_HOST;
                        }
                        chatUrl = u;
                        chatHost = hostOf(u);
                        videoHost = vh;
                        prefs.edit().putString("chat_url", chatUrl).putString("video_host", videoHost).apply();
                        d.dismiss();
                        loadChat();
                    }
                });
                d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (first) {
                            finish();
                        } else {
                            d.dismiss();
                        }
                    }
                });
            }
        });
        d.show();
    }

    // ---------------------------------------------------------------- 打不开时的页面

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void showError(String failingUrl, String desc) {
        addLog("页面打不开 " + failingUrl + " " + desc);
        String btn = "display:inline-block;margin:8px;padding:10px 22px;border-radius:8px;"
                + "background:#2d7ff9;color:#fff;text-decoration:none;font-size:16px";
        String html = "<html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='font-family:sans-serif;padding:40px 24px;text-align:center'>"
                + "<h3>无法打开页面</h3>"
                + "<p style='color:#666;word-break:break-all'>" + esc(failingUrl) + "</p>"
                + "<p style='color:#999'>" + esc(desc) + "</p>"
                + "<p><a href='native://retry' style='" + btn + "'>重试</a>"
                + "<a href='native://settings' style='" + btn + "'>修改地址</a></p>"
                + "</body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    // ---------------------------------------------------------------- WebViewClient

    private boolean handleNav(String url, boolean isMain) {
        if (url == null) return false;
        if (url.startsWith("native://")) {
            if (isMain) {
                if (url.equals("native://settings")) showSettings();
                else if (url.equals("native://retry")) loadChat();
            }
            return true;
        }
        if (!isMain) return false; // 页面内嵌的视频等子页面，不拦截

        Uri u = Uri.parse(url);
        String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (sc.equals("http") || sc.equals("https")) {
            if (hostAllowed(u.getHost())) return false;
            openExternal(url);
            return true;
        }
        if (sc.equals("about") || sc.equals("data") || sc.equals("blob") || sc.equals("javascript")) {
            return false;
        }
        openExternal(url); // mailto:、tel: 等交给系统
        return true;
    }

    private class AppClient extends WebViewClient {
        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNav(url, true);
        }

        @TargetApi(24)
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNav(request.getUrl().toString(), request.isForMainFrame());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            webView.setKeepScreenOn(false);
            inCall = false;
            addLog("页面开始加载 " + url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            runJs(HOOK_JS);
            runJs(NOTIFY_JS);
            addLog("页面加载完成，已注入脚本 " + url);
        }

        @TargetApi(23)
        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                showError(request.getUrl().toString(), String.valueOf(error.getDescription()));
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (Build.VERSION.SDK_INT < 23) {
                showError(failingUrl, description);
            }
        }
    }

    // ---------------------------------------------------------------- WebChromeClient

    private class AppChrome extends WebChromeClient {

        @TargetApi(21)
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            String host = request.getOrigin() == null ? "" : request.getOrigin().getHost();
            addLog("网页请求权限 host=" + host + " " + java.util.Arrays.toString(request.getResources()));
            if (!hostAllowed(host)) {
                request.deny();
                return;
            }
            if (pendingMedia != null) {
                pendingMedia.deny();
                pendingMedia = null;
            }
            List<String> res = new ArrayList<String>();
            List<String> missing = new ArrayList<String>();
            for (String r : request.getResources()) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                    res.add(r);
                    if (!has(Manifest.permission.CAMERA)) missing.add(Manifest.permission.CAMERA);
                } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                    res.add(r);
                    if (!has(Manifest.permission.RECORD_AUDIO)) missing.add(Manifest.permission.RECORD_AUDIO);
                }
            }
            if (res.isEmpty()) {
                request.deny();
                return;
            }
            if (missing.isEmpty()) {
                finishGrant(request, res);
            } else {
                pendingMedia = request;
                pendingMediaRes = res;
                ActivityCompat.requestPermissions(MainActivity.this,
                        missing.toArray(new String[0]), REQ_MEDIA);
            }
        }

        @TargetApi(21)
        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (pendingMedia == request) {
                pendingMedia = null;
                pendingMediaRes = null;
            }
        }

        @TargetApi(21)
        @Override
        public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCb != null) {
                fileCb.onReceiveValue(null);
            }
            fileCb = cb;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
            } catch (Exception e) {
                fileCb = null;
                cb.onReceiveValue(null);
                toast("无法打开文件选择器");
            }
            return true;
        }

        // 网页里的新窗口（target=_blank / window.open）一律交给系统浏览器，不替换聊天页
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            WebView temp = new WebView(MainActivity.this);
            temp.setWebViewClient(new WebViewClient() {
                private void go(WebView v, String url) {
                    if (url != null && !url.equals("about:blank")) {
                        openExternal(url);
                        v.stopLoading();
                        v.destroy();
                    }
                }

                @SuppressWarnings("deprecation")
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    go(v, url);
                    return true;
                }

                @Override
                public void onPageStarted(WebView v, String url, Bitmap favicon) {
                    go(v, url);
                }
            });
            WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
            t.setWebView(temp);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onShowCustomView(View v, CustomViewCallback cb) {
            if (customView != null) {
                cb.onCustomViewHidden();
                return;
            }
            customView = v;
            customCb = cb;
            webView.setVisibility(View.GONE);
            root.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            setImmersiveMode(true);
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            root.removeView(customView);
            customView = null;
            webView.setVisibility(View.VISIBLE);
            if (customCb != null) {
                customCb.onCustomViewHidden();
                customCb = null;
            }
            setImmersiveMode(false);
        }
    }

    private void setImmersiveMode(boolean on) {
        if (Build.VERSION.SDK_INT >= 19) {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(on ? flags : View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    // ---------------------------------------------------------------- 摄像头和麦克风

    private boolean has(String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    @TargetApi(21)
    private void finishGrant(PermissionRequest request, List<String> res) {
        request.grant(res.toArray(new String[0]));
        if (res.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
            webView.setKeepScreenOn(true); // 视频通话时屏幕常亮，重新加载页面后恢复
        }
    }

    @TargetApi(21)
    private void onMediaPermissionResult() {
        PermissionRequest req = pendingMedia;
        List<String> want = pendingMediaRes;
        pendingMedia = null;
        pendingMediaRes = null;
        if (req == null || want == null) return;

        List<String> ok = new ArrayList<String>();
        for (String r : want) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && has(Manifest.permission.CAMERA)) ok.add(r);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && has(Manifest.permission.RECORD_AUDIO)) ok.add(r);
        }
        if (ok.isEmpty()) {
            req.deny();
            toast("没有授权摄像头或麦克风，请在系统设置里允许");
        } else {
            finishGrant(req, ok);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MEDIA) {
            if (Build.VERSION.SDK_INT >= 21) onMediaPermissionResult();
        } else if (requestCode == REQ_STORAGE) {
            final String name = pendingName;
            final byte[] bytes = pendingBytes;
            pendingName = null;
            pendingBytes = null;
            if (name != null && bytes != null) {
                final boolean granted = grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        writeDownload(name, bytes, granted);
                    }
                }).start();
            }
        }
    }

    // ---------------------------------------------------------------- 选择文件

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCb != null) {
            Uri[] result = null;
            if (Build.VERSION.SDK_INT >= 21) {
                result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
            fileCb.onReceiveValue(result);
            fileCb = null;
        }
    }

    // ---------------------------------------------------------------- 下载

    /** 让网页自己把文件取出来（带着登录状态），转成文本交给 HostBridge.save */
    private void fetchAndSave(String url, String contentDisposition, String mimetype) {
        String guess = URLUtil.guessFileName(url, contentDisposition, mimetype);
        String js = "(function(){var u=" + JSONObject.quote(url)
                + ",n=window.__dlName||" + JSONObject.quote(guess) + ";window.__dlName='';"
                + "fetch(u).then(function(r){return r.blob()}).then(function(b){"
                + "var f=new FileReader();"
                + "f.onloadend=function(){HostBridge.save(n,b.type||'',String(f.result))};"
                + "f.onerror=function(){HostBridge.fail(u)};"
                + "f.readAsDataURL(b)}).catch(function(){HostBridge.fail(u)})})()";
        runJs(js);
    }

    public class HostBridge {
        @JavascriptInterface
        public void save(String name, String mime, String dataUrl) {
            try {
                if (dataUrl == null || dataUrl.length() > MAX_DATA_CHARS) {
                    toast("文件太大，无法保存");
                    return;
                }
                int i = dataUrl.indexOf(',');
                String b64 = i >= 0 ? dataUrl.substring(i + 1) : dataUrl;
                final byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                final String safe = safeName(name);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        startSave(safe, bytes);
                    }
                });
            } catch (Exception e) {
                toast("保存失败");
            }
        }

        // 视频页发来 connected / ended：记录"是否正在通话"，用于按 Home 键进画中画
        @JavascriptInterface
        public void call(boolean on) {
            inCall = on;
            addLog("通话状态变化 inCall=" + on);
        }

        // 网页的通知转成系统通知；tag 为 vcall 时是来电，用来电铃声
        @JavascriptInterface
        public void notify(final String title, final String body, final String tag) {
            addLog("桥接收到 notify tag=" + tag);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showSysNotification(title == null ? "" : title,
                            body == null ? "" : body, tag == null ? "" : tag);
                }
            });
        }

        // 视频页自己的画中画按钮（网页的画中画在 WebView 里不能用）→ 改用软件的画中画
        @JavascriptInterface
        public void pip() {
            addLog("桥接收到 pip（视频页的画中画按钮）");
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (Build.VERSION.SDK_INT >= 26) {
                        if (!isInPictureInPictureMode()) enterPip();
                    } else {
                        toast("画中画需要安卓 8.0 及以上");
                    }
                }
            });
        }

        @JavascriptInterface
        public void log(String m) {
            addLog(m);
        }

        @JavascriptInterface
        public void fail(final String url) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    openExternal(url);
                }
            });
        }
    }

    private static String safeName(String n) {
        if (n == null) n = "";
        n = n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        if (n.length() > 120) n = n.substring(n.length() - 120);
        if (n.length() == 0 || n.equals(".") || n.equals("..")) {
            n = "file_" + System.currentTimeMillis();
        }
        return n;
    }

    private void startSave(final String name, final byte[] bytes) {
        if (Build.VERSION.SDK_INT < 23 || has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    writeDownload(name, bytes, true);
                }
            }).start();
        } else {
            pendingName = name;
            pendingBytes = bytes;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, System.currentTimeMillis() + "_" + name);
    }

    /** 优先存到系统"下载"目录；没权限或失败时存到软件自己的目录 */
    private void writeDownload(String name, byte[] bytes, boolean usePublic) {
        File[] dirs = new File[]{
                usePublic ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) : null,
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        };
        File saved = null;
        boolean inPublic = false;
        for (int k = 0; k < dirs.length && saved == null; k++) {
            File d = dirs[k];
            if (d == null) continue;
            FileOutputStream out = null;
            try {
                d.mkdirs();
                File f = uniqueFile(d, name);
                out = new FileOutputStream(f);
                out.write(bytes);
                out.flush();
                saved = f;
                inPublic = (k == 0);
            } catch (IOException e) {
                // 换下一个目录再试
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        if (saved == null) {
            toast("保存失败");
            return;
        }
        MediaScannerConnection.scanFile(this, new String[]{saved.getAbsolutePath()}, null, null);
        toast(inPublic ? "已保存到 下载/" + saved.getName() : "已保存：" + saved.getAbsolutePath());
    }
}
