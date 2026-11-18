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
import android.provider.Settings;
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
import android.widget.CheckBox;
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

    // 内置的聊天网址：没在软件里设置过时就用它，首次打开不再强制弹窗要求输入。
    // 换成你自己的聊天网址（带 https://）；设为 "" 则恢复"首次必须输入"。
    private static final String DEFAULT_CHAT_URL = "https://p.gth.dpdns.org/";

    private static final int REQ_MEDIA = 1;
    private static final int REQ_STORAGE = 2;
    private static final int REQ_FILE = 3;

    // 下载时网页把文件转成 base64 文本传给软件，限制大小防止内存溢出（约 50MB 文件）
    private static final int MAX_DATA_CHARS = 70 * 1024 * 1024;


    // 记录 <a download="文件名"> 的文件名，下载时用
    private static final String HOOK_JS =
            "(function(){if(window.__hk)return;window.__hk=1;"
            + "document.addEventListener('click',function(e){var a=e.target;"
            + "while(a&&a.tagName!=='A')a=a.parentNode;"
            + "if(a&&a.hasAttribute('download'))window.__dlName=a.getAttribute('download')||'';},true);})();";

    // 把网页的 Notification / showNotification 转给软件；监听视频页的 connected / ended / pip-fail
    private static final String NOTIFY_JS =
              "(function(){if(window.__nt)return;window.__nt=1;\n"
            + "function send(t,o){o=o||{};try{HostBridge.notify(String(t||''),String(o.body||''),String(o.tag||''));}catch(e){}}\n"
            + "function N(t,o){send(t,o);this.close=function(){};this.onclick=null;}\n"
            + "N.permission='granted';\n"
            + "N.requestPermission=function(cb){if(cb)cb('granted');return Promise.resolve('granted');};\n"
            + "try{Object.defineProperty(window,'Notification',{value:N,configurable:true,writable:true});}catch(e){window.Notification=N;}\n"
            + "try{if(window.ServiceWorkerRegistration){ServiceWorkerRegistration.prototype.showNotification=function(t,o){send(t,o);return Promise.resolve();};}}catch(e){}\n"
            + "var recent={};\n"
            + "try{var onm=window.newMsgNotice;if(typeof onm==='function'&&!onm.__f){var nw=function(list){var rv=onm.apply(this,arguments);try{var m=list[list.length-1],r=rmOf(m);if(sysOk()&&rsNtf(r)){var un=0;try{un=rsum[r].un||0;}catch(z){}var n=un>0?un:list.length;var tg='room-'+r;recent[tg]=Date.now();var ms=0;for(var i=0;i<list.length;i++){if(list[i].seq>ms)ms=list[i].seq;}HostBridge.notifyMsg(String(roomTitle(r)),String((n>1?'['+n+'条] ':'')+(r===1?dispName(m.user)+'：':'')+msgTxt(m).slice(0,80)),tg,String(ms));}}catch(x){}return rv;};nw.__f=1;window.newMsgNotice=nw;}}catch(e){}\n"
            + "try{var oss=window.sysShow;if(typeof oss==='function'&&!oss.__s){var ns=function(t,b,tg){try{if(tg&&String(tg).indexOf('room-')===0&&recent[tg]&&Date.now()-recent[tg]<3000){return Promise.resolve();}}catch(x){}return oss.apply(this,arguments);};ns.__s=1;window.sysShow=ns;}}catch(e){}\n"
            + "window.__pipPrep=function(){try{var L=document.getElementById('vcL');if(!L)return;L.classList.remove('pip');if(L.classList.contains('mini')){if(window.vcMini)vcMini(false);else L.classList.remove('mini','bubble');}}catch(e){}};\n"
            + "window.addEventListener('message',function(e){var d=e.data;if(!d||d.source!=='vcall')return;var F=document.getElementById('vcF');if(!F||e.source!==F.contentWindow)return;if(d.type==='pip-fail'){e.stopImmediatePropagation();try{HostBridge.pip();}catch(x){}}},true);\n"
            + "var last=null;setInterval(function(){var on=false;try{on=!!(document.getElementById('vcF')&&vc&&vc.id&&vc.st==='act');}catch(x){}if(on!==last){last=on;try{HostBridge.call(on);}catch(x){}}},1000);\n"
            + "try{var oa=window.api;if(typeof oa==='function'&&!oa.__a){var na=function(p){try{var q=String(p);if(q.indexOf('/api/sync')===0){var mm=/after=(\\d+)/.exec(q);if(mm)HostBridge.seq(mm[1]);}}catch(x){}return oa.apply(this,arguments);};na.__a=1;window.api=na;}}catch(e){}\n"
            + "var lsA='',lsC='';setInterval(function(){try{var a=String(name)+'|'+String(token)+'|'+String(grant)+'|'+String(lgrant);if(a!==lsA){lsA=a;HostBridge.auth(String(name||''),String(token||''),String(grant||''),String(lgrant||''));}var c={on:!!sysOk(),rooms:{},nicks:{}};try{for(var i=0;i<rooms.length;i++){var rr=rooms[i];c.rooms[rr.id]={t:roomTitle(rr.id),n:rsNtf(rr.id)?1:0};}}catch(e1){}try{for(var u in prof){if(prof[u]&&prof[u].nick)c.nicks[u]=prof[u].nick;}}catch(e2){}var cs=JSON.stringify(c);if(cs!==lsC){lsC=cs;HostBridge.cfg(cs);}}catch(e){}},2000);\n"
            + "try{var ovf=window.vcFrame;if(typeof ovf==='function'&&!ovf.__v){var nvf=function(url){try{if(url){var u=new URL(url,location.href);if(window.__vurl){try{var o=new URL(window.__vurl);u.protocol=o.protocol;u.host=o.host;var au=o.searchParams.get('auth');if(au)u.searchParams.set('auth',au);}catch(x1){}}url=u.toString();var org=u.origin;try{if(vcFreeOn)vcFreeO=org;else if(vc&&vc.id)vc.origin=org;}catch(x2){}try{HostBridge.video(url);}catch(x3){}}}catch(x){}return ovf.call(this,url);};nvf.__v=1;window.vcFrame=nvf;}}catch(e){}\n"
            + "try{var ovi=window.vcShowIn;if(typeof ovi==='function'&&!ovi.__c){var nvi=function(id,room,voice){var fresh=(vcIn!==id);var rv=ovi.apply(this,arguments);try{if(fresh&&vcIn===id&&!document.hidden&&sysOk()){HostBridge.notify(String(roomTitle(room)),voice?'邀请你语音通话':'邀请你视频通话','vcall');}}catch(x){}return rv;};nvi.__c=1;window.vcShowIn=nvi;}}catch(e){}\n"
            + "try{var ovh=window.vcHideIn;if(typeof ovh==='function'&&!ovh.__c){var nvh=function(){var rv=ovh.apply(this,arguments);try{HostBridge.callEnd();}catch(x){}return rv;};nvh.__c=1;window.vcHideIn=nvh;}}catch(e){}\n"
            + "})();\n";
    private FrameLayout root;
    private WebView webView;
    private AppChrome chrome;
    private SharedPreferences prefs;

    private String chatUrl = "";
    private String chatHost = "";
    // 视频页网址：软件里填了就用它；没填就用聊天脚本里设置的默认网址
    private String videoUrl = "";
    // 摄像头 / 麦克风放行的视频页域名：由聊天页真正打开的视频网址自动得出，不用手动填
    private volatile String videoHost = "";

    private AlertDialog settingsDialog;

    private volatile boolean inCall = false;
    private volatile boolean resumed = false;
    /** 软件是否在前台可见（后台查询服务据此决定要不要查） */
    static volatile boolean visible = false;

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
        if (chatUrl.length() == 0) chatUrl = normalizeUrl(DEFAULT_CHAT_URL);
        videoUrl = prefs.getString("video_url", "");
        chatHost = hostOf(chatUrl);

        Notifier.init(this); // 安卓 13 会在这时弹出"允许通知"
        applyBg();
        applyCallFlags(getIntent());

        root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

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
    protected void onStart() {
        super.onStart();
        visible = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Notifier.cancelCall(this); // 回到软件，来电界面已在网页里，停掉通知铃声
        Notifier.cancelMsgs(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
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
        if (Build.VERSION.SDK_INT >= 26 && (customView != null || inCall)
                && !isInPictureInPictureMode()) {
            enterPip();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
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
        } catch (Exception e) {
        }
    }

    // ---------------------------------------------------------------- 通知设置入口

    private TextView linkBtn(String text, final String chId) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFF2D7FF9);
        b.setTextSize(15);
        b.setPadding(0, dp(8), dp(20), dp(8));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openChannelSettings(chId);
            }
        });
        return b;
    }

    /** 跳到系统里这个软件的某个通知类别设置页（声音、振动、横幅都在那里改） */
    private void openChannelSettings(String chId) {
        try {
            Intent it;
            if (Build.VERSION.SDK_INT >= 26) {
                it = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
                it.putExtra(Settings.EXTRA_CHANNEL_ID, chId);
                it.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                it = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
                it.putExtra("app_package", getPackageName());
                it.putExtra("app_uid", getApplicationInfo().uid);
            }
            startActivity(it);
        } catch (Throwable e) {
            try {
                Intent it = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                it.setData(Uri.parse("package:" + getPackageName()));
                startActivity(it);
            } catch (Throwable e2) {
                toast("打不开系统设置，请手动去 设置→应用→通知");
            }
        }
    }

    // ---------------------------------------------------------------- 系统通知

    /** 网页转来的通知：消息走统一渠道，来电用来电样式 */
    private void showSysNotification(String title, String body, String tag) {
        try {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return;
            if ("vcall".equals(tag)) {
                Notifier.showCall(this, title, body);
            } else {
                Notifier.showMsg(this, title, body, tag);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 按设置开启 / 关闭后台消息提醒（前台服务，通知栏会常驻一条小通知） */
    private void applyBg() {
        try {
            Intent sv = new Intent(this, PollService.class);
            if (prefs.getBoolean("bg_on", true)) {
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(sv);
                } else {
                    startService(sv);
                }
            } else {
                stopService(sv);
            }
        } catch (Throwable ignored) {
        }
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
        t1.setText("聊天网址（留空则使用内置地址）");
        box.addView(t1);
        final EditText e1 = new EditText(this);
        e1.setHint("https://chat.example.com");
        e1.setSingleLine(true);
        e1.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e1.setText(chatUrl);
        box.addView(e1);

        TextView t2 = new TextView(this);
        t2.setText("\n视频页网址（可留空）\n填写后，视频使用这里的网址；留空则使用聊天脚本里设置的默认网址。");
        box.addView(t2);
        final EditText e2 = new EditText(this);
        e2.setHint("https://video.example.com（可留空）");
        e2.setSingleLine(true);
        e2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e2.setText(videoUrl);
        box.addView(e2);

        final CheckBox cbPoster = new CheckBox(this);
        cbPoster.setText("视频未接通时显示播放图标（默认关闭：显示纯黑）");
        cbPoster.setChecked(prefs.getBoolean("show_poster", false));
        box.addView(cbPoster);

        TextView t3 = new TextView(this);
        t3.setText("\n通知提醒（声音、振动、横幅在系统设置页里改）");
        box.addView(t3);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(linkBtn("消息通知设置", Notifier.CH_MSG));
        row.addView(linkBtn("来电设置", Notifier.CH_CALL));
        box.addView(row);

        final CheckBox cbBg = new CheckBox(this);
        cbBg.setText("后台消息提醒（通知栏会常驻一条小通知）");
        cbBg.setChecked(prefs.getBoolean("bg_on", true));
        box.addView(cbBg);

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
                        if (u.length() == 0) u = normalizeUrl(DEFAULT_CHAT_URL);
                        if (u.length() == 0) {
                            toast("请输入正确的聊天网址");
                            return;
                        }
                        // 视频网址：填了就用填的；留空 = 使用聊天脚本里的默认网址
                        videoUrl = normalizeUrl(e2.getText());
                        chatUrl = u;
                        chatHost = hostOf(u);
                        prefs.edit().putString("chat_url", chatUrl).putString("video_url", videoUrl)
                                .putBoolean("show_poster", cbPoster.isChecked())
                                .putBoolean("bg_on", cbBg.isChecked()).apply();
                        applyBg();
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
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            runJs(HOOK_JS);
            runJs("window.__vurl=" + JSONObject.quote(videoUrl) + ";");
            runJs(NOTIFY_JS);
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

        // 视频还没画面时，WebView 默认会画一个播放图标；默认改成透明（显示页面自己的纯黑背景）
        @Override
        public Bitmap getDefaultVideoPoster() {
            if (prefs.getBoolean("show_poster", false)) return super.getDefaultVideoPoster();
            return Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888);
        }

        @TargetApi(21)
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            String host = request.getOrigin() == null ? "" : request.getOrigin().getHost();
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
        }

        // 网页的通知转成系统通知；tag 为 vcall 时是来电，用来电铃声
        @JavascriptInterface
        public void notify(final String title, final String body, final String tag) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showSysNotification(title == null ? "" : title,
                            body == null ? "" : body, tag == null ? "" : tag);
                }
            });
        }

        // 网页新消息提醒：带上这批消息的最大序号，已经通知过的不再重复通知
        @JavascriptInterface
        public void notifyMsg(final String title, final String body, final String tag, String seq) {
            long sq = 0;
            try {
                sq = Long.parseLong(seq);
            } catch (Exception ignored) {
            }
            if (sq > 0 && sq <= Notifier.notifiedSeq(MainActivity.this)) return;
            if (sq > Notifier.lastSeq) Notifier.lastSeq = sq;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showSysNotification(title == null ? "" : title,
                            body == null ? "" : body, tag == null ? "" : tag);
                }
            });
        }

        // 把登录信息交给后台查询服务
        @JavascriptInterface
        public void auth(String n, String t, String g, String l) {
            getSharedPreferences("bg_state", MODE_PRIVATE).edit()
                    .putString("name", n).putString("token", t)
                    .putString("grant", g).putString("lgrant", l).apply();
        }

        // 把聊天室名称、是否开通知、昵称交给后台查询服务
        @JavascriptInterface
        public void cfg(String json) {
            getSharedPreferences("bg_state", MODE_PRIVATE).edit().putString("cfg", json).apply();
        }

        // 网页当前同步到的消息序号
        @JavascriptInterface
        public void seq(String v) {
            Notifier.pageSyncAt = System.currentTimeMillis(); // 网页还在同步，说明没被冻住
            long sq = 0;
            try {
                sq = Long.parseLong(v);
            } catch (Exception ignored) {
            }
            SharedPreferences sp = getSharedPreferences("bg_state", MODE_PRIVATE);
            if (sq > sp.getLong("seq", 0)) sp.edit().putLong("seq", sq).apply();
        }

        // 来电界面关了（接听 / 拒绝 / 超时 / 对方取消）：取消来电通知，停掉铃声
        @JavascriptInterface
        public void callEnd() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Notifier.cancelCall(MainActivity.this);
                }
            });
        }

        // 聊天页要打开的视频网址：记下它的域名，用来放行摄像头 / 麦克风（只认聊天页自己设置的那个）
        @JavascriptInterface
        public void video(String url) {
            try {
                Uri u = Uri.parse(url);
                String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
                String h = hostOf(url);
                if ((sc.equals("https") || sc.equals("http")) && h.length() > 0) videoHost = h;
            } catch (Exception ignored) {
            }
        }

        // 视频页自己的画中画按钮（网页的画中画在 WebView 里不能用）→ 改用软件的画中画
        @JavascriptInterface
        public void pip() {
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
