package net.chmlfrp.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        SharedPreferences sp = c.getSharedPreferences("chmlfrp", Context.MODE_PRIVATE);
        if (!sp.getBoolean("autostart", false)) return;
        if (!Config.current(c).exists() || Config.current(c).length() == 0) return;
        Intent s = new Intent(c, FrpService.class)
                .setAction(FrpService.ACTION_START)
                .putExtra(FrpService.EXTRA_MODE, "config")
                .putExtra(FrpService.EXTRA_UTOKEN, "")
                .putExtra(FrpService.EXTRA_PID, "");
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
        else c.startService(s);
    }
}
