package net.lyu323.chmlfrp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 前台服务：执行 APK 内 lib/&lt;abi&gt;/libfrpc.so。
 *
 * v2.1 关键修复：
 * 1. 不再传 --strict-config —— 真机上的 ChmlFrp-0.51.2_240715 不认这个 flag，
 *    旧版每次启动都立刻 Error: unknown flag 退出，守护又疯狂重连。
 * 2. 守护改成「单个线程循环 + 代次 epoch」，不再递归发 Intent，
 *    修掉日志里第 5/6 次又跳回第 1 次、超上限还重连的计数混乱。
 * 3. 参数/配置错误判定为 fatal，立刻停止重连并给出诊断。
 */
public class FrpService extends Service {
    public static final String ACTION_START = "net.lyu323.chmlfrp.START";
    public static final String ACTION_STOP = "net.lyu323.chmlfrp.STOP";
    public static final String ACTION_NETWORK = "net.lyu323.chmlfrp.NETWORK";
    public static final String ACTION_REFRESH = "net.lyu323.chmlfrp.REFRESH_NOTIFY";
    public static final String ACTION_EVENT = "net.lyu323.chmlfrp.NOTIFY_EVENT";
    public static final String EXTRA_EVENT_TEXT = "eventText";
    public static final String EXTRA_EVENT_TITLE = "eventTitle";
    public static final String KERNEL_NAME = "libfrpc.so";
    public static final String EXTRA_MODE = "mode";          // config | official
    public static final String EXTRA_FORCE_RESTART = "forceRestart";
    public static final String EXTRA_UTOKEN = "utoken";
    public static final String EXTRA_PID = "pid";

    static final String PREFS = "chmlfrp";
    static final int DEFAULT_MAX_RESTART = 5;

    static volatile Process proc;
    static volatile boolean running = false;      // 进程正在跑
    static volatile boolean waiting = false;      // 守护等待重连中
    static volatile boolean userStopped = false;
    static volatile long startedAt = 0;
    static volatile long epoch = 0;
    static volatile boolean starting = false;      // v3.0：启动中，防网络广播重复拉起
    static volatile long lastNetReconnectAt = 0L;  // v3.0：网络恢复去抖
    static volatile Thread worker;
    static volatile String lastFatal = "";

    private BroadcastReceiver netReceiver;

    @Override public void onCreate() {
        super.onCreate();
        netReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (!ConnectivityManager.CONNECTIVITY_ACTION.equals(i.getAction())) return;
                // v3.0：进程对象还在（正在收尾）或正在启动时，一律不重连。
                // 旧版这里只看 running/waiting，导致「网络恢复 → 拉起 → 已在运行中忽略
                // → 旧进程 SIGTERM(143) → 再拉起」的反复自杀循环。
                if (!prefs(c).getBoolean("watchdog", true) || !want(c)
                        || userStopped || running || waiting || starting
                        || proc != null) return;
                long now = System.currentTimeMillis();
                if (now - lastNetReconnectAt < 8000L) return;   // 8 秒去抖
                lastNetReconnectAt = now;
                try {
                    ConnectivityManager cm =
                            (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
                    NetworkInfo n = cm.getActiveNetworkInfo();
                    if (n == null || !n.isConnected()) return;
                } catch (Exception ignored) { return; }
                append(c, "[守护] 网络已恢复，请求重新连接");
                postEvent("网络已恢复", "正在重新连接 frp 节点…");
                Intent s = new Intent(c, FrpService.class).setAction(ACTION_START);
                try {
                    if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
                    else c.startService(s);
                } catch (Exception e) { append(c, "[守护] 重连失败：" + e); }
            }
        };
        try {
            registerReceiver(netReceiver,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            epoch++;
            userStopped = true;
            want(this, false);
            prefs(this).edit().putBoolean("serviceKilled", false).commit();
            stopTunnel();
            Traffic.finish(this);
            stopForeground(true);
            // v2.7：保留一条可点的「待机通知」，不打开 App 也能再次运行
            showIdleNotification();
            stopSelf();
            return START_NOT_STICKY;
        }

        // 独立的事件通知（掉线 / 重连成功 / 流量阈值），不覆盖常驻通知
        if (ACTION_EVENT.equals(action)) {
            String t = intent == null ? "" : intent.getStringExtra(EXTRA_EVENT_TITLE);
            String x = intent == null ? "" : intent.getStringExtra(EXTRA_EVENT_TEXT);
            postEvent(t == null ? "ChmlFrp 事件" : t, x == null ? "" : x);
            return START_NOT_STICKY;
        }

        // 设置页改了通知字段/主题/地址后调这里：只重建通知，不碰隧道进程
        if (ACTION_REFRESH.equals(action)) {
            try {
                if (!(running || waiting)) {
                    // 满足 startForegroundService 的前台要求后立即收掉，避免残留通知
                    startForeground(1, buildNotification());
                    stopForeground(true);
                    stopSelf();
                    return START_NOT_STICKY;
                }
                NotificationManager nm = (NotificationManager)
                        getSystemService(Context.NOTIFICATION_SERVICE);
                nm.notify(1, buildNotification());
            } catch (Exception ignored) {}
            return START_NOT_STICKY;
        }

        // 每次显式启动都开启新代次，旧守护线程的等待/重连立即失效
        // 通知栏「重启」：先收掉当前进程再走正常启动流程
        if (intent != null && intent.getBooleanExtra(EXTRA_FORCE_RESTART, false)
                && (running || waiting)) {
            epoch++;   // 先让旧守护代次失效，destroy 打断 readLine 时不会被判成故障
            stopTunnel();
            running = false;
            proc = null;
            append(this, "[启动器] 通知栏请求重启，已先停止旧进程");
        }
        epoch++;
        userStopped = false;
        lastFatal = "";
        want(this, true);
        try {
            NotificationManager nmx = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            nmx.cancel(ActionReceiver.IDLE_NOTIFY_ID);
        } catch (Exception ignored) {}
        if (intent != null && intent.hasExtra(EXTRA_MODE)) {
            prefs(this).edit()
                    .putString("startMode", intent.getStringExtra(EXTRA_MODE))
                    .putString("officialToken", intent.getStringExtra(EXTRA_UTOKEN))
                    .putString("officialId", intent.getStringExtra(EXTRA_PID))
                    .commit();
        }
        Traffic.begin(this);
        startForeground(1, buildNotification());

        // 进程正在跑 / 正在启动时忽略重复请求，避免把正常隧道杀掉重启
        if (running || starting) {
            append(this, "[启动器] " + (starting ? "正在启动中" : "已在运行中")
                    + "，忽略重复启动请求");
            return START_NOT_STICKY;
        }
        starting = true;
        // 旧守护可能正卡在重连等待里：先让它退出，再开新代次
        final Thread prev = worker;
        if (prev != null && prev.isAlive()) prev.interrupt();
        final long my = epoch;
        worker = new Thread(new Runnable() {
            @Override public void run() { loop(my, prev); }
        }, "frp-guard");
        worker.start();
        return START_NOT_STICKY;
    }

    /** 单循环守护：启动 → 等待退出 → 判断是否重连 → 再启动。 */
    private void loop(long my, Thread prev) {
        if (prev != null && prev != Thread.currentThread() && prev.isAlive()) {
            for (int i = 0; i < 10 && prev.isAlive(); i++) {
                try { Thread.sleep(100); } catch (Exception ignored) { break; }
            }
        }
        int attempt = 0;
        int max = maxRestart(this);

        // 通知栏每 3 秒刷新一次实时速度（开关可在设置页关掉）
        final Thread notifier = new Thread(new Runnable() {
            @Override public void run() {
                while (my == epoch && !userStopped) {
                    try { Thread.sleep(3000); } catch (Exception e) { break; }
                    if (my != epoch || userStopped) break;
                    try {
                        if (prefs(FrpService.this).getBoolean("notifySpeed", true)) {
                            NotificationManager nm = (NotificationManager)
                                    getSystemService(Context.NOTIFICATION_SERVICE);
                            nm.notify(1, buildNotification());
                        }
                    } catch (Exception ignored) {}
                }
            }
        }, "frp-notify");
        notifier.setDaemon(true);
        notifier.start();

        try {
            while (alive(my)) {
                Exit e = runOnce(my);
                if (!alive(my)) break;

                if (e.fatal) {
                    lastFatal = e.reason;
                    append(this, "[守护] 判定为不可重连错误，已停止：" + e.reason);
                    append(this, "[诊断] " + diagnose(e.output));
                    want(this, false);
                    break;
                }
                if (attempt >= max) {
                    append(this, "[守护] 已达重连上限 " + max + " 次，停止自动重试");
                    want(this, false);
                    break;
                }
                attempt++;
                long delay = 2000L + Math.min(attempt, 5) * 1500L;
                append(this, "[守护] " + (delay / 1000) + " 秒后第 " + attempt
                        + " / " + max + " 次重连…");
                waiting = true;
                if (!sleepInterruptible(delay, my)) break;
                waiting = false;
            }
        } finally {
            notifier.interrupt();
            // 被新代次取代时只清等待态，别碰 running / proc，那是新线程的
            waiting = false;
            if (my == epoch) {
                starting = false;
                Traffic.finish(this);
                running = false;
                proc = null;
                startedAt = 0;
                stopForeground(true);
                stopSelf();
            }
        }
    }

    private boolean alive(long my) {
        return my == epoch && !userStopped && want(this);
    }

    private boolean sleepInterruptible(long ms, long my) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (!alive(my)) return false;
            try { Thread.sleep(200); } catch (Exception ignored) { return false; }
        }
        return alive(my);
    }

    private static final class Exit {
        boolean fatal;
        String reason = "";
        String output = "";
        int code;
    }

    /** 跑一次 frpc，收集输出并判断错误类型。 */
    private Exit runOnce(long my) {
        Exit e = new Exit();
        StringBuilder all = new StringBuilder();
        Process mine = null;   // v3.0：只清理属于本代次的进程引用
        long t0 = System.currentTimeMillis();
        try {
            File bin = kernel(this);
            if (!bin.exists() || !bin.canExecute()) {
                e.fatal = true;
                e.reason = "内核不可执行或缺失";
                e.output = bin.getAbsolutePath();
                append(this, "[异常] 内核不可执行：" + bin.getAbsolutePath());
                return e;
            }

            Kernel.Info ki = Kernel.probe(this);
            append(this, "[内核] " + ki.version + " · " + ki.typeLabel()
                    + " · INI=" + (ki.ini ? "支持" : "否")
                    + " TOML=" + (ki.toml ? "支持" : "不支持")
                    + " --strict-config=" + (ki.strictConfig ? "支持" : "不支持"));

            String mode = prefs(this).getString("startMode", "config");
            List<String> cmd = new ArrayList<String>();
            cmd.add(bin.getAbsolutePath());
            if ("official".equals(mode) && ki.idToken) {
                String ut = prefs(this).getString("officialToken", "").trim();
                String pid = prefs(this).getString("officialId", "").trim();
                if (ut.length() == 0 || pid.length() == 0) {
                    e.fatal = true;
                    e.reason = "官方参数启动缺少用户令牌或隧道 ID";
                    append(this, "[异常] " + e.reason);
                    return e;
                }
                cmd.add("-u"); cmd.add(ut);
                cmd.add("-p"); cmd.add(pid);
                append(this, "[启动器] 官方参数模式：-u <令牌> -p " + pid);
            } else {
                File cfg = Config.current(this);
                if (!cfg.exists() || cfg.length() == 0) {
                    e.fatal = true;
                    e.reason = "配置为空";
                    append(this, "[错误] 配置为空，请先拉取或填写配置");
                    return e;
                }
                cmd.addAll(Arrays.asList(Kernel.command(cfg.getAbsolutePath())));
                append(this, "[启动器] 配置: " + cfg.getAbsolutePath()
                        + " · " + Config.format(readFile(cfg)));
                if (!ki.configCompatible(readFile(cfg))) {
                    e.fatal = true;
                    e.reason = "当前内核不支持 TOML 格式配置";
                    e.output = "TOML";
                    append(this, "[错误] 当前内核（" + ki.version
                            + "）只支持 INI，请把配置换回面板生成的 INI");
                    append(this, "[诊断] 配置页「INI/TOML」提示会标红；不要点格式转换");
                    return e;
                }
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(getFilesDir());
            pb.redirectErrorStream(true);
            pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            proc = pb.start();
            mine = proc;
            running = true;
            starting = false;   // 真正拉起后才放开去重
            startedAt = System.currentTimeMillis();
            append(this, "[启动器] 进程已拉起 · 参数 " + safeCmd(cmd));

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                String s = stripAnsi(line);
                all.append(s).append('\n');
                append(this, s);
                if (!alive(my)) { proc.destroy(); break; }
            }
            e.code = proc.waitFor();
            if (e.code == 143 && !userStopped) {
                append(this, "[启动器] 进程收到停止信号（code=143 SIGTERM），"
                        + "交由守护判断是否重连");
            } else {
                append(this, "[启动器] 进程退出，code=" + e.code);
            }
        } catch (Exception ex) {
            // 主动停止时 readLine() 被 close 打断是正常收尾，不是故障；
            // 旧版每次停止都打一条红色 [异常]，把用户吓一跳。
            boolean normalClose = (ex instanceof java.io.InterruptedIOException
                    || (ex.getMessage() != null
                        && (ex.getMessage().contains("read interrupted")
                            || ex.getMessage().contains("InterruptedIOException"))))
                    && (userStopped || my != epoch || !want(this) || proc == null);
            if (normalClose) {
                e.fatal = false;
                e.reason = "用户主动停止";
                append(this, "[启动器] 进程已正常关闭（读取线程中断，非故障）");
            } else {
                e.fatal = true;
                e.reason = ex.toString();
                append(this, "[异常] " + ex);
            }
        } finally {
            // v3.0 关键修复：旧代次收尾时，新代次可能已经把 proc 指向自己的新进程。
            // 这里绝不能无条件清掉，否则会「拉起 → 忽略重复启动 → code=143」循环。
            if (mine == null || proc == mine) {
                running = false;
                proc = null;
                startedAt = 0;
            }
            if (my == epoch) starting = false;
        }
        e.output = all.toString();
        long dur = System.currentTimeMillis() - t0;
        String o = e.output;
        // 只有参数/命令不兼容这类"永远不可能成功"的错误才不重连
        if (!e.fatal && (o.contains("unknown flag") || o.contains("unknown command")
                || o.contains("unknown shorthand flag")
                || o.contains("flag provided but not defined")) && dur < 8000) {
            e.fatal = true;
            e.reason = "内核不支持传入的启动参数";
        }
        return e;
    }

    private String safeCmd(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < cmd.size(); i++) {
            String s = cmd.get(i);
            if (i > 1 && ("-u".equals(cmd.get(i - 1)) || "--token".equals(cmd.get(i - 1)))) {
                s = "***";
            }
            sb.append(s).append(' ');
        }
        return sb.toString().trim();
    }

    private String diagnose(String o) {
        if (o.contains("unknown flag")) {
            int i = o.indexOf("unknown flag");
            String flag = o.substring(i, Math.min(o.length(), i + 60)).split("\n")[0];
            return "启动参数不兼容（" + flag.trim()
                    + "）。v2.1 已改为只传 -c，不再附加任何可选 flag。";
        }
        if (o.contains("TOML") || o.contains("[[proxies]]")) {
            return "当前是 ChmlFrp 魔改内核，只认 INI；请在配置页换回面板生成的 INI 配置。";
        }
        if (o.contains("login to the server failed") || o.contains("EOF")) {
            return "能连到节点但登录被拒：确认配置里的 user / token 与面板一致，或改用官方参数启动。";
        }
        if (o.contains("dial tcp") || o.contains("connect: ")) {
            return "节点连不上：用「测试节点连通」确认手机网络，或换节点。";
        }
        return "请看上方内核/配置日志，或复制日志反馈。";
    }

    private void stopTunnel() {
        Process p = proc;
        if (p != null) {
            p.destroy();
            append(this, "[启动器] 已发送停止信号");
        }
        running = false;
        waiting = false;
        starting = false;
        proc = null;
        startedAt = 0;
        Thread w = worker;
        if (w != null && w.isAlive()) w.interrupt();
    }

    @Override public void onDestroy() {
        // 用户想让它常驻、又不是主动停止 → 大概率被系统回收，写标记给适配度/权限页用
        if (want(this) && !userStopped) {
            prefs(this).edit().putBoolean("serviceKilled", true).commit();
        }
        if (netReceiver != null) {
            try { unregisterReceiver(netReceiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ 公共工具

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean want(Context c) { return prefs(c).getBoolean("wantRunning", false); }

    static void want(Context c, boolean v) {
        prefs(c).edit().putBoolean("wantRunning", v).commit();
    }

    static int maxRestart(Context c) {
        int n = prefs(c).getInt("maxRestart", DEFAULT_MAX_RESTART);
        return n < 0 ? 0 : n;
    }

    public static String stateLabel() {
        if (running) return "● 运行中";
        if (waiting) return "◌ 重连等待中";
        return "○ 未运行";
    }

    public static String uptime() {
        if (!running || startedAt <= 0) return "";
        long s = (System.currentTimeMillis() - startedAt) / 1000;
        long hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;
        return hh > 0 ? String.format("%d:%02d:%02d", hh, mm, ss)
                : String.format("%02d:%02d", mm, ss);
    }

    static File kernel(Context c) {
        ApplicationInfo ai = c.getApplicationInfo();
        return new File(ai.nativeLibraryDir, KERNEL_NAME);
    }

    static String abi() {
        String[] a = Build.SUPPORTED_ABIS;
        return a == null || a.length == 0 ? "unknown" : a[0];
    }

    /** 执行内核短命令（-v / --help / verify），返回合并输出。 */
    static String exec(Context c, String... args) throws Exception {
        File bin = kernel(c);
        List<String> cmd = new ArrayList<String>();
        cmd.add(bin.getAbsolutePath());
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(c.getFilesDir());
        pb.redirectErrorStream(true);
        pb.environment().put("TMPDIR", c.getCacheDir().getAbsolutePath());
        Process p = pb.start();
        BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(stripAnsi(line)).append('\n');
        p.waitFor();
        return sb.toString();
    }

    static String version(Context c) {
        try {
            String s = exec(c, "-v").trim();
            int i = s.indexOf('\n');
            return i > 0 ? s.substring(0, i) : s;
        } catch (Exception e) { return "未知"; }
    }

    static String verify(Context c) throws Exception {
        return exec(c, "verify", "-c", Config.current(c).getAbsolutePath());
    }

    static String[] probe(String addr, String port) {
        if (addr == null || addr.length() == 0) {
            return new String[]{"false", "未从配置中解析到 server_addr / serverAddr"};
        }
        Socket s = new Socket();
        try {
            int p = Integer.parseInt(port);
            long t = System.currentTimeMillis();
            s.connect(new InetSocketAddress(addr, p), 3000);
            long ms = System.currentTimeMillis() - t;
            return new String[]{"true", addr + ":" + p + " 可达 · " + ms + " ms"};
        } catch (Exception e) {
            return new String[]{"false", addr + ":" + port + " 不可达 · "
                    + e.getClass().getSimpleName()};
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static String readFile(File f) throws Exception {
        java.io.FileInputStream is = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*[A-Za-z]", "");
    }

    static void append(Context c, String s) {
        try {
            FileOutputStream fos = new FileOutputStream(
                    new File(c.getFilesDir(), "runtime.log"), true);
            fos.write((s + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {}
    }

    /** 当前活跃网络类型，供通知栏「网络」字段使用。 */
    private String netType() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo n = cm.getActiveNetworkInfo();
            if (n == null || !n.isConnected()) return "未连接";
            if (n.getType() == ConnectivityManager.TYPE_WIFI) return "WiFi";
            if (n.getType() == ConnectivityManager.TYPE_MOBILE) return "蜂窝数据";
            return "其他（" + n.getTypeName() + "）";
        } catch (Exception e) { return "未知"; }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    "frp", "隧道服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("隧道状态、连接地址与实时流量");
            nm.createNotificationChannel(ch);
        }

        SharedPreferences sp = prefs(this);
        // 服务可能由开机广播单独拉起，此时 Activity 没运行，静态色板需自行初始化
        Ui.apply(Theme.resolve(this, sp.getInt("themeId", 0)));
        final boolean themeColor = sp.getBoolean("notifyThemeColor", true);
        final boolean binary = sp.getBoolean("binaryUnits", true);
        final boolean full = sp.getBoolean("notifyFull", true);
        // 全量/紧凑开关只作为预设，具体行由字段掩码决定
        final long fields = full ? NotifyFields.read(sp) : NotifyFields.compact();
        final String collapsed = sp.getString("notifyCollapsed", "speed");
        Kernel.Info ki = Kernel.cached();
        String netType = netType();
        String themeName = Theme.of(sp.getInt("themeId", 0)).name;

        String cfg = Config.readCurrent(this);
        String ep = Config.resolveEndpoint(this);
        String host = ep.length() > 0 ? Config.displayHost(ep) : "未设置";
        String name = Config.summary(this);
        // 隐私模式：通知里不露出真实域名与隧道名
        host = Privacy.endpoint(this, host);
        name = Privacy.tunnelName(this, name);
        String id = sp.getString("tunnelId", "").trim();
        String node = sp.getString("tunnelNode", "").trim();
        String type = Config.proxyType(cfg);
        if (type.length() == 0) type = sp.getString("tunnelType", "").trim();
        String[] la = Config.localAddr(cfg);
        String local = la[0] + (la[1].length() > 0 ? ":" + la[1] : "");
        String conn = sp.getString("tunnelConn", "").trim();
        String pstate = sp.getString("tunnelState", "").trim();
        String kernel = "未知".equals(ki.version) ? "检测中" : ki.version;
        String state = userStopped ? "■ 停止中" : running ? "● 运行中"
                : waiting ? "◌ 重连中" : "○ 未运行";
        String up = Units.speed(Traffic.txSpeed, binary);
        String down = Units.speed(Traffic.rxSpeed, binary);
        long[] tot = Traffic.grandTotal();
        String uptime = running ? FrpService.uptime() : "—";
        String watchdog = sp.getBoolean("watchdog", true)
                ? "开启 · 上限 " + maxRestart(this) + " 次" : "关闭";
        int proxyCount = Config.proxyCount(cfg);
        final int density = sp.getInt("notifyDensity", 0);  // 0紧凑 1标准 2全量
        final boolean silentMode = Privacy.silent(this);

        // PendingIntent：返回 App / 停止 / 打开地址 / 复制地址
        Intent it = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, FrpService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent startTunnel = new Intent(this, ActionReceiver.class)
                .setAction(ActionReceiver.ACTION_START_TUNNEL);
        PendingIntent startPi = PendingIntent.getBroadcast(this, 4, startTunnel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent restartTunnel = new Intent(this, ActionReceiver.class)
                .setAction(ActionReceiver.ACTION_RESTART_TUNNEL);
        PendingIntent restartPi = PendingIntent.getBroadcast(this, 5, restartTunnel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent openLog = new Intent(this, ActionReceiver.class)
                .setAction(ActionReceiver.ACTION_OPEN_LOG);
        PendingIntent logPi = PendingIntent.getBroadcast(this, 6, openLog,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent copyAll = new Intent(this, ActionReceiver.class)
                .setAction(ActionReceiver.ACTION_COPY_ALL);
        PendingIntent copyAllPi = PendingIntent.getBroadcast(this, 7, copyAll,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent tunnels = new Intent(this, ActionReceiver.class)
                .setAction(ActionReceiver.ACTION_TUNNELS);
        PendingIntent tunnelsPi = PendingIntent.getBroadcast(this, 8, tunnels,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent silentToggle = new Intent(this, ActionReceiver.class)
                .setAction(ActionReceiver.ACTION_TOGGLE_SILENT);
        PendingIntent silentPi = PendingIntent.getBroadcast(this, 9, silentToggle,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent copy = new Intent(this, ActionReceiver.class)
                .setAction(ActionReceiver.ACTION_COPY_ADDR);
        PendingIntent copyPi = PendingIntent.getBroadcast(this, 2, copy,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent view;
        if (ep.length() > 0) {
            view = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Config.urlOf(ep)));
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            view = new Intent(this, MainActivity.class);
        }
        PendingIntent openPi = PendingIntent.getActivity(this, 3, view,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String title = "ChmlFrp · " + state
                + (proxyCount > 1 ? " · " + proxyCount + " 条隧道" : "");
        String line;
        if ("addr".equals(collapsed)) {
            line = host.length() > 0 ? host : "↑ " + up + "  ↓ " + down;
        } else if ("tunnel".equals(collapsed)) {
            line = (name.length() > 0 ? name : "隧道") + " · " + up + " / " + down;
        } else if ("node".equals(collapsed)) {
            line = (node.length() > 0 ? node : "—") + " · " + up + " / " + down;
        } else {
            line = (name.length() > 0 ? name + " · " : "")
                    + "↑ " + up + "  ↓ " + down;
        }

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, "frp");
        else b = new Notification.Builder(this);
        android.graphics.drawable.Icon icon = NotifyIcon.create(this);
        if (icon != null) b.setSmallIcon(icon);
        else b.setSmallIcon(android.R.drawable.stat_sys_upload);
        if (themeColor) {
            b.setColor(Ui.BLUE);
            // 深色 / 玻璃主题下 colorized 会把整条通知染成蓝紫，
            // 系统据此给 Action 文字配了低对比色（截图里几乎看不清）。
            // 只在浅色主题启用；暗色一律交给系统保证对比度。
            if (Build.VERSION.SDK_INT >= 26 && !Ui.DARK && !Ui.GLASS
                    && !Privacy.silent(this)) b.setColorized(true);
        }
        if (Build.VERSION.SDK_INT < 26 && Privacy.silent(this))
            b.setPriority(Notification.PRIORITY_MIN);
        b.setVisibility(Privacy.lockPrivate(this)
                ? Notification.VISIBILITY_PRIVATE : Notification.VISIBILITY_PUBLIC);
        b.setContentTitle(title)
                .setContentText(line)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (running && startedAt > 0) {
            b.setWhen(startedAt);
            b.setShowWhen(true);
        }
        if (node.length() > 0) b.setSubText(node);

        // 展开内容按「密度」生成：OriginOS 等 ROM 对 BigTextStyle 行数有硬上限
        // （截图里只显示到「守护 关闭」就被截断了），默认紧凑只给 5 行关键信息。
        StringBuilder big = new StringBuilder();
        if (density == 0 || silentMode) {
            big.append(state).append(" · ").append(uptime);
            if (proxyCount > 1) big.append(" · ").append(proxyCount).append(" 条隧道");
            big.append('\n');
            big.append("隧道  ").append(name.length() > 0 ? name : "—").append('\n');
            big.append("节点  ").append(node.length() > 0 ? node : "—")
               .append(" · ").append(type.length() > 0 ? type : "—").append('\n');
            big.append("地址  ").append(host.length() > 0 ? host : "—").append('\n');
            big.append("实时  ↑ ").append(up).append("   ↓ ").append(down);
            if (proxyCount > 1) {
                String[] pn = Config.proxyNames(cfg);
                int show = Math.min(3, pn.length);
                big.append('\n').append("多隧道  ");
                for (int q = 0; q < show; q++)
                    big.append(q > 0 ? " / " : "").append(pn[q]);
                if (pn.length > show) big.append("  +").append(pn.length - show);
            }
        } else {
            boolean f = density == 2;
            if (NotifyFields.has(fields, "state"))
                big.append(state).append(" · 已运行 ").append(uptime).append('\n');
            if (proxyCount > 1)
                big.append("条数  ").append(proxyCount).append(" 条隧道同时运行\n");
            if (NotifyFields.has(fields, "tunnel"))
                big.append("隧道  ").append(name.length() > 0 ? name : "—").append('\n');
            if (NotifyFields.has(fields, "node"))
                big.append("节点  ").append(node.length() > 0 ? node : "—")
                   .append(" · ").append(type.length() > 0 ? type : "—").append('\n');
            if (NotifyFields.has(fields, "local"))
                big.append("本地  ").append(local).append('\n');
            if (NotifyFields.has(fields, "addr"))
                big.append("地址  ").append(host.length() > 0 ? host : "—").append('\n');
            if (f && NotifyFields.has(fields, "kernel"))
                big.append("内核  ").append(kernel).append('\n');
            if (f && NotifyFields.has(fields, "guard"))
                big.append("守护  ").append(watchdog).append('\n');
            if (f && NotifyFields.has(fields, "net"))
                big.append("网络  ").append(netType).append('\n');
            if (NotifyFields.has(fields, "realtime"))
                big.append("实时  ↑ ").append(up).append("   ↓ ").append(down).append('\n');
            if (f && NotifyFields.has(fields, "session"))
                big.append("本次  ↑ ").append(Units.bytes(Traffic.sessionTx, binary))
                   .append("   ↓ ").append(Units.bytes(Traffic.sessionRx, binary)).append('\n');
            if (f && NotifyFields.has(fields, "total"))
                big.append("累计  ↑ ").append(Units.bytes(tot[1], binary))
                   .append("   ↓ ").append(Units.bytes(tot[0], binary)).append('\n');
        }
        if (!Traffic.available) big.append("\n设备不支持 UID 流量统计");
        big.append("\n\n点此返回启动器 · 下方按钮可直接启停 / 复制 / 看日志");

        b.setStyle(new Notification.BigTextStyle()
                .setBigContentTitle(title)
                .setSummaryText(host)
                .bigText(big.toString().trim()));

        if (Build.VERSION.SDK_INT >= 20) {
            boolean on = running || waiting;
            int set = sp.getInt("notifyActionSet", 0);
            // 第一颗固定是启停
            b.addAction(new Notification.Action.Builder(
                    on ? android.R.drawable.ic_media_pause
                       : android.R.drawable.ic_media_play,
                    on ? "停止" : "▶ 启动隧道",
                    on ? stopPi : startPi).build());
            if (set == 1) {          // 地址向
                b.addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_view, "打开地址", openPi).build());
                b.addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_save, "复制地址", copyPi).build());
            } else if (set == 2) {  // 排障向
                b.addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_agenda, "看日志", logPi).build());
                b.addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_sort_alphabetically,
                        "隧道列表", tunnelsPi).build());
            } else if (set == 3) {  // 隐私向
                b.addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_save, "复制全部", copyAllPi).build());
                b.addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_lock_silent_mode, "静默",
                        silentPi).build());
            } else if (set == 4) {  // 多隧道
                b.addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_sort_alphabetically,
                        "隧道列表", tunnelsPi).build());
                b.addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_save, "复制全部", copyAllPi).build());
            } else {                // 默认：重启（未运行时给隧道列表）+ 复制全部
                b.addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_ff,
                        on ? "⟳ 重启" : "隧道列表",
                        on ? restartPi : tunnelsPi).build());
                b.addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_save, "复制全部", copyAllPi).build());
            }
        }
        return b.build();
    }

    /** 独立事件通知：掉线 / 重连 / 流量阈值，不覆盖常驻前台服务通知。 */
    private void postEvent(String title, String text) {
        if (!prefs(this).getBoolean("notifyEvents", true)) return;
        try {
            Ui.apply(Theme.resolve(this, prefs(this).getInt("themeId", 0)));
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager)
                        getSystemService(Context.NOTIFICATION_SERVICE);
                NotificationChannel ch = new NotificationChannel(
                        "frp_event", "隧道事件", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("掉线重连、流量阈值等可清除提醒");
                nm.createNotificationChannel(ch);
                b = new Notification.Builder(this, "frp_event");
            } else b = new Notification.Builder(this);
            android.graphics.drawable.Icon icon = NotifyIcon.create(this);
            b.setSmallIcon(icon != null ? icon
                    : android.graphics.drawable.Icon.createWithResource(this,
                        android.R.drawable.stat_sys_upload));
            Intent back = new Intent(this, MainActivity.class)
                    .putExtra("fromNotify", true).putExtra("page", 2);
            PendingIntent pi = PendingIntent.getActivity(this, 10, back,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.setContentTitle(title).setContentText(text)
                    .setContentIntent(pi).setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE);
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(ActionReceiver.EVENT_NOTIFY_ID, b.build());
        } catch (Exception ignored) {}
    }

    /** 停止后保留的待机通知：不需要打开 App，点一下就重新运行。 */
    private void showIdleNotification() {
        if (!prefs(this).getBoolean("notifyIdle", true)) return;
        try {
            Ui.apply(Theme.resolve(this, prefs(this).getInt("themeId", 0)));
            Intent startTunnel = new Intent(this, ActionReceiver.class)
                    .setAction(ActionReceiver.ACTION_START_TUNNEL);
            PendingIntent startPi = PendingIntent.getBroadcast(this, 4, startTunnel,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Intent back = new Intent(this, MainActivity.class);
            PendingIntent backPi = PendingIntent.getActivity(this, 0, back,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, "frp");
            else b = new Notification.Builder(this);
            android.graphics.drawable.Icon icon = NotifyIcon.create(this);
            b.setSmallIcon(icon != null ? icon
                    : android.graphics.drawable.Icon.createWithResource(this,
                        android.R.drawable.stat_sys_upload));
            b.setContentTitle("ChmlFrp · ○ 未运行")
                    .setContentText("点「启动隧道」即可连上（不用打开 App）")
                    .setContentIntent(backPi)
                    .setOngoing(false)
                    .setAutoCancel(false)
                    .setOnlyAlertOnce(true)
                    .setVisibility(Privacy.lockPrivate(this)
                            ? Notification.VISIBILITY_PRIVATE
                            : Notification.VISIBILITY_PUBLIC);
            if (prefs(this).getBoolean("notifyThemeColor", true)) b.setColor(Ui.BLUE);
            if (Build.VERSION.SDK_INT >= 20) {
                b.addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_play, "▶ 启动隧道", startPi).build());
            }
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(ActionReceiver.IDLE_NOTIFY_ID, b.build());
        } catch (Exception ignored) {}
    }
}
