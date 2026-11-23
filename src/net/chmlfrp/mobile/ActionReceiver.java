package net.chmlfrp.mobile;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.Toast;

/**
 * 通知栏快捷动作（v2.7 扩展）：
 *  · COPY_ADDR      复制连接地址
 *  · START_TUNNEL  未运行通知上的「▶ 启动隧道」
 *  · RESTART_TUNNEL 运行通知上的「⟳ 重启」
 *
 * 从通知 PendingIntent 触发属于用户交互，通常可以启动前台服务；
 * 若系统仍拒绝（部分 ROM 的后台 FGS 限制），自动降级为拉起启动器并带请求。
 */
public class ActionReceiver extends BroadcastReceiver {
    public static final String ACTION_COPY_ADDR = "net.chmlfrp.mobile.COPY_ADDR";
    public static final String ACTION_START_TUNNEL = "net.chmlfrp.mobile.START_TUNNEL";
    public static final String ACTION_RESTART_TUNNEL = "net.chmlfrp.mobile.RESTART_TUNNEL";
    public static final String ACTION_OPEN_LOG = "net.chmlfrp.mobile.OPEN_LOG";
    public static final String ACTION_COPY_ALL = "net.chmlfrp.mobile.COPY_ALL";
    public static final String ACTION_TUNNELS = "net.chmlfrp.mobile.OPEN_TUNNELS";
    public static final String ACTION_TOGGLE_SILENT = "net.chmlfrp.mobile.TOGGLE_SILENT";
    public static final int IDLE_NOTIFY_ID = 2;
    public static final int EVENT_NOTIFY_ID = 3;

    @Override public void onReceive(Context c, Intent i) {
        if (i == null) return;
        String a = i.getAction();
        if (ACTION_COPY_ADDR.equals(a)) { copy(c); return; }
        if (ACTION_START_TUNNEL.equals(a)) { start(c, false); return; }
        if (ACTION_RESTART_TUNNEL.equals(a)) { start(c, true); return; }
        if (ACTION_OPEN_LOG.equals(a)) { openPage(c, "page", 2); return; }
        if (ACTION_TUNNELS.equals(a)) { openPage(c, "showTunnels", 0); return; }
        if (ACTION_COPY_ALL.equals(a)) { copyAll(c); return; }
        if (ACTION_TOGGLE_SILENT.equals(a)) { toggleSilent(c); return; }
    }

    private void openPage(Context c, String extra, int page) {
        Intent i = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("fromNotify", true)
                .putExtra("page", page);
        // 不要用 "page" 当布尔标记名：同名 key 先放 boolean 再放 int 会类型冲突
        if (!"page".equals(extra)) i.putExtra(extra, true);
        try { c.startActivity(i); }
        catch (Exception e) { Toast.makeText(c, "请打开启动器", Toast.LENGTH_SHORT).show(); }
    }

    /** 复制通知里的全部运行信息（不是只复制地址）。 */
    private void copyAll(Context c) {
        String ep = Config.resolveEndpoint(c);
        android.content.SharedPreferences p =
                c.getSharedPreferences("chmlfrp", Context.MODE_PRIVATE);
        StringBuilder b = new StringBuilder("ChmlFrp 运行信息\n");
        b.append("状态  ").append(FrpService.stateLabel()).append('\n')
         .append("隧道  ").append(p.getString("tunnelName", "—")).append('\n')
         .append("节点  ").append(p.getString("tunnelNode", "—")).append('\n')
         .append("本地  ").append(p.getString("tunnelLocalIp", "127.0.0.1"))
         .append(':').append(p.getString("tunnelLocalPort", "—")).append('\n')
         .append("地址  ").append(ep.length() > 0 ? ep : "—").append('\n')
         .append("条数  ").append(Config.proxyCount(Config.readCurrent(c))).append('\n');
        try {
            ClipboardManager cm = (ClipboardManager)
                    c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("ChmlFrp 运行信息", b.toString()));
            Toast.makeText(c, "已复制全部运行信息", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(c, "复制失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleSilent(Context c) {
        boolean now = !Privacy.silent(c);
        Privacy.set(c, Privacy.K_SILENT, now);
        Toast.makeText(c, now ? "已开启通知最小化" : "已恢复标准通知", Toast.LENGTH_SHORT).show();
        try {
            Intent r = new Intent(c, FrpService.class)
                    .setAction(FrpService.ACTION_REFRESH);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(r);
            else c.startService(r);
        } catch (Exception ignored) {}
    }

    private void copy(Context c) {
        String ep = Config.resolveEndpoint(c);
        if (ep.length() == 0) {
            Toast.makeText(c, "还没有连接地址", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("ChmlFrp 连接地址", Config.urlOf(ep)));
            Toast.makeText(c, "已复制 " + Config.displayHost(ep), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(c, "复制失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void start(Context c, boolean restart) {
        try {
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(IDLE_NOTIFY_ID);
        } catch (Exception ignored) {}
        Intent s = new Intent(c, FrpService.class)
                .setAction(FrpService.ACTION_START)
                .putExtra(FrpService.EXTRA_MODE, "config");
        if (restart) s.putExtra(FrpService.EXTRA_FORCE_RESTART, true);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
            Toast.makeText(c, restart ? "正在重启隧道…" : "正在启动隧道…",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Intent fallback = new Intent(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("tileRequest", restart ? "restart" : "start");
            try { c.startActivity(fallback); }
            catch (Exception e2) {
                Toast.makeText(c, "请打开启动器点启动", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
