package net.chmlfrp.mobile;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

/** 一键权限向导：集中检查启动器需要的各项权限状态。 */
public final class Permissions {
    private Permissions() {}

    public static final int REQ_ALL = 601;

    public static final class Item {
        public String title, detail;
        public boolean granted;
        public boolean fixableByRequest;   // 能通过 requestPermissions 直接申请
        Item(String t, String d, boolean g, boolean r) {
            title = t; detail = d; granted = g; fixableByRequest = r;
        }
    }

    public static Item[] check(Context c) {
        Item[] out = new Item[5];

        // 1 通知权限（Android 13+ 运行时）
        boolean notifRuntime;
        if (Build.VERSION.SDK_INT >= 33) {
            notifRuntime = c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            notifRuntime = true;
        }
        out[0] = new Item("通知权限",
                notifRuntime ? "已允许，前台服务与通知栏可正常显示" : "未允许，通知栏可能不显示",
                notifRuntime, Build.VERSION.SDK_INT >= 33 && !notifRuntime);

        // 2 系统通知总开关 / 渠道
        boolean notifEnabled = true;
        try {
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            notifEnabled = nm.areNotificationsEnabled();
            if (notifEnabled && Build.VERSION.SDK_INT >= 26) {
                NotificationManager finalNm = nm;
                android.app.NotificationChannel ch = finalNm.getNotificationChannel("frp");
                if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                    notifEnabled = false;
                }
            }
        } catch (Exception ignored) {}
        out[1] = new Item("通知开关",
                notifEnabled ? "已开启" : "被系统关闭，需要到通知设置里打开",
                notifEnabled, false);

        // 3 电池优化白名单
        boolean battery = true;
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                battery = pm.isIgnoringBatteryOptimizations(c.getPackageName());
            } catch (Exception ignored) {}
        }
        out[2] = new Item("忽略电池优化",
                battery ? "已加入白名单，后台不易被杀" : "未加入，锁屏后隧道容易被系统冻结",
                battery, false);

        // 4 网络状态（普通权限，安装即授予，仅展示）
        boolean net = c.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE)
                == PackageManager.PERMISSION_GRANTED;
        out[3] = new Item("网络状态", net ? "已授予" : "异常", net, false);

        // 5 自启动 / 后台运行：无标准 API 检测，只能引导
        out[4] = new Item("自启动与后台运行",
                "国产 ROM 需手动允许；无法由系统 API 检测，请点下方按钮跳转设置",
                false, false);
        return out;
    }

    /** 需要运行时申请的权限数组（可能为空）。 */
    public static String[] runtimePermissions(Context c) {
        if (Build.VERSION.SDK_INT < 33) return new String[0];
        if (c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return new String[0];
        return new String[]{Manifest.permission.POST_NOTIFICATIONS};
    }

    public static int grantedCount(Context c) {
        int n = 0;
        for (Item i : check(c)) if (i.granted) n++;
        return n;
    }
}
