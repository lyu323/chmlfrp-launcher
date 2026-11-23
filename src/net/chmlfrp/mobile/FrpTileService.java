package net.chmlfrp.mobile;

import android.annotation.TargetApi;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 下拉快捷设置磁贴：不打开 App 也能一键启停隧道（v2.6 新增）。
 *
 * Android 12+ 对后台启动前台服务有限制，磁贴点击若被系统拒绝，
 * 会自动降级为打开启动器并带上「请求启停」标记，不会出现点了没反应的情况。
 */
@TargetApi(24)
public class FrpTileService extends TileService {

    @Override public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override public void onTileAdded() {
        super.onTileAdded();
        refresh();
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = FrpService.running || FrpService.waiting;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(on ? "隧道运行中" : "启动隧道");
        t.setContentDescription(on ? "ChmlFrp 隧道运行中，点击停止" : "点击启动 ChmlFrp 隧道");
        try {
            t.setIcon(Icon.createWithResource(this, on
                    ? android.R.drawable.ic_media_pause
                    : android.R.drawable.ic_media_play));
        } catch (Exception ignored) {}
        t.updateTile();
    }

    @Override public void onClick() {
        super.onClick();
        boolean on = FrpService.running || FrpService.waiting;
        try {
            Intent i;
            if (on) {
                FrpService.want(this, false);
                i = new Intent(this, FrpService.class)
                        .setAction(FrpService.ACTION_STOP);
                startService(i);
            } else {
                FrpService.want(this, true);
                i = new Intent(this, FrpService.class)
                        .setAction(FrpService.ACTION_START)
                        .putExtra(FrpService.EXTRA_MODE, "config");
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            }
        } catch (Exception e) {
            // 后台启动被系统拒绝：打开 App 并带上请求，让用户点一下即可
            Intent fallback = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("tileRequest", on ? "stop" : "start");
            try { startActivityAndCollapse(fallback); }
            catch (Exception e2) { startActivity(fallback); }
        }
        refresh();
    }
}
