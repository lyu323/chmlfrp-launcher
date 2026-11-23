package net.lyu323.chmlfrp;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/**
 * 隐藏后台 / 隐私显示（v2.8）。
 *
 * 四项都可独立开关、随时恢复，默认全关：
 *  ① 最近任务隐藏：不在多任务卡片里露出本 App（ActivityManager.AppTask）
 *  ② 通知最小化：前台服务通知降到 PRIORITY_MIN，不染色、不展开大段内容
 *  ③ 锁屏隐藏内容：VISIBILITY_PRIVATE，锁屏不显示域名与隧道名
 *  ④ 通知内打码：把连接域名 / 隧道名替换成 •••，下拉展开可看的部分也打码
 *
 * 【如实说明】Android 8+ 前台服务必须有通知，系统不允许第三方完全隐藏，
 * 只能降到最低优先级/最小化；部分 ROM 会强制提升重要性。
 * 「隐藏后台」不等于保活，后台能否常驻仍取决于电池白名单与自启动设置。
 */
public final class Privacy {
    private Privacy() {}

    public static final String K_RECENTS = "hideRecents";
    public static final String K_SILENT = "silentNotify";
    public static final String K_LOCK = "lockPrivate";
    public static final String K_MASK = "maskEndpoint";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("chmlfrp", Context.MODE_PRIVATE);
    }

    public static boolean recents(Context c) { return sp(c).getBoolean(K_RECENTS, false); }
    public static boolean silent(Context c) { return sp(c).getBoolean(K_SILENT, false); }
    public static boolean lockPrivate(Context c) { return sp(c).getBoolean(K_LOCK, false); }
    public static boolean mask(Context c) { return sp(c).getBoolean(K_MASK, false); }

    public static void set(Context c, String key, boolean v) {
        sp(c).edit().putBoolean(key, v).commit();
    }

    public static boolean anyOn(Context c) {
        return recents(c) || silent(c) || lockPrivate(c) || mask(c);
    }

    /** 在最近任务列表里隐藏/恢复。返回是否设置成功。 */
    public static boolean applyRecents(Activity a) {
        if (android.os.Build.VERSION.SDK_INT < 21) return false;
        try {
            ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.AppTask> tasks = am.getAppTasks();
            boolean done = false;
            for (ActivityManager.AppTask t : tasks) {
                t.setExcludeFromRecents(recents(a));
                done = true;
            }
            return done;
        } catch (Exception e) { return false; }
    }

    /** 通知里显示的域名：打码时只保留长度提示，不泄露真实地址。 */
    public static String endpoint(Context c, String real) {
        if (!mask(c) || real == null || real.length() == 0) return real;
        return "••••••" + (real.length() > 12 ? ".•••" : "");
    }

    /** 通知里显示的隧道名。 */
    public static String tunnelName(Context c, String real) {
        if (!mask(c) || real == null || real.length() == 0) return real;
        return "隧道 ••" + (real.length() > 4 ? real.substring(real.length() - 2) : "");
    }

    public static int visibility(Context c) {
        // 由调用方与 Notification.VISIBILITY_* 比较，这里返回语义值
        return lockPrivate(c) ? 1 : 0;   // 1=PRIVATE 0=PUBLIC
    }

    public static String summary(Context c) {
        int n = 0;
        if (recents(c)) n++;
        if (silent(c)) n++;
        if (lockPrivate(c)) n++;
        if (mask(c)) n++;
        return n == 0 ? "全部关闭（默认）" : n + " 项已开启";
    }
}
