package net.chmlfrp.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {
    static final String PREFS = "chmlfrp";
    static final int REQ_IMPORT = 77;

    EditText etToken, etNode, etTunnel, etConfig;
    TextView tvPill, tvLog, tvFormat, tvKernel, tvLink, tvPreview, tvUptime;
    TextView tvGuard, tvCompat, tvKernelBadge;
    TextView tvTunnelName, tvTunnelMeta, tvDomain, tvDomainSub, tvOnlineBadge;
    TextView tvSpeedUp, tvSpeedDown, tvTrafficSession, tvTrafficTotal;
    TextView tvTrafficPeak, tvConnCount, tvEndpoints, tvTunnelHint, tvSpeedMini;
    TextView tvSchemeBadge, tvChartCap;
    CheckBox cbAuto, cbFollow, cbWatchdog, cbOnlyErrors;
    CheckBox cbNotifySpeed, cbNotifyFull, cbAutoRefresh, cbBinaryUnits, cbGlassMotion;
    CheckBox cbNotifyIdle, cbFoldRepeat;
    CheckBox cbHideRecents, cbSilentNotify, cbLockPrivate, cbMaskEndpoint, cbNotifyEvents, cbLogRedact;
    String logFilter = "";
    private String lastErrText = "", lastLogLine = "";
    private long lastErrAt = 0L;
    private int errMerged = 0;
    CheckBox cbNotifyTheme, cbSignRemind;
    TextView tvLoginState, tvFitBadge, tvFitSummary, tvDeviceBody, tvSignTitle, tvSignBody, tvSignTrend;
    LinearLayout fitBar;
    View fitFill;
    final View[] tabInd = new View[4];
    Spinner spConfigs;
    ScrollView svLog;
    Button btnStart, btnStop, btnExpand, btnTheme;
    SpeedBar speedBar;
    Sparkline sparkline;
    LinearLayout editorWrap;
    ScrollView[] pages = new ScrollView[4];
    Button[] tabs = new Button[4];

    SharedPreferences sp;
    final Handler h = new Handler(Looper.getMainLooper());
    long logPos = 0;
    boolean spinnerReady = false;
    String curConfig = Config.DEFAULT;
    int themeId = 0;
    int page = 0;
    int tickCount = 0;
    Kernel.Info ki = new Kernel.Info();

    interface Job {
        String work() throws Exception;
        void done(String result);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        // 兼容 v2.2 的 dark 布尔偏好
        if (!sp.contains("themeId") && sp.contains("dark")) {
            sp.edit().putInt("themeId", sp.getBoolean("dark", false) ? 1 : 0).commit();
        }
        themeId = sp.getInt("themeId", 0);
        Ui.apply(Theme.resolve(this, themeId));
        Config.migrate(this);
        buildUi();
        loadPrefs();
        refreshSpinner();
        persistEditor(null);
        refreshPreview();
        refreshKernelInfo();
        switchPage(sp.getInt("page", 0));
        h.post(ticker);
        if (!new File(getFilesDir(), "runtime.log").exists()) {
            log("[提示] v3.1.0：新增「检查更新」，连接本项目 GitHub Releases，手动点击才联网");
            log("[提示] v3.1.0：设置页 / 关于页加入项目地址、Star、更新日志、开源许可与另一项目入口");
            log("[提示] v3.0.1：联系作者内置 QQ 官方落地页，零联网直达加好友页，不再只打开主界面");
            log("[提示] v3.0.1：不再自动复制链接，只有点「复制链接」才写剪贴板");
            log("[提示] v3.0：修复装了 QQ 却跳浏览器（包可见性 + 强制交给 QQ）");
            log("[提示] v3.0：修复守护竞态，网络恢复后不再反复 code=143 自杀重连");
            log("[提示] v3.0：停止导致的读取中断统一记为正常收尾，不再报红色 [异常]");
            log("[提示] v3.0：令牌校验结果收敛，状态不变或 5 分钟内不重复刷日志");
            log("[提示] v3.0：签到中心默认折叠、标题单行、玻璃弹窗遮罩加深");
            log("[提示] v3.0：快捷操作新增「一键自检」与「v3.0 更新」");
            log("[提示] 流程：面板建隧道 → OAuth 登录 → 选节点 → 拉取配置 → 启动");
            log("[提示] 后台易断请允许后台运行、关闭电池优化，国产 ROM 建议锁定后台");
        }
        silentRestoreSession();
        if (Sign.shouldRemind(this) && sp.getString("token", "").length() > 0) {
            Sign.markReminded(this);
            toast("今天记得去面板签到领积分哦 · 签到中心可一键跳转");
            log("[签到] 每日签到提醒（本地记录，不涉及隐私数据）");
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                   != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        handleCallback(getIntent());
        handleTileRequest(getIntent());
        handleNotifyIntent(getIntent());
        updateLoginBadge();
        renderDevice();
        Privacy.applyRecents(this);
        if (!Disclaimer.agreed(this)) showDisclaimer(true);
    }

    /** 通知栏动作「看日志 / 隧道列表」拉起对应页面。 */
    private void handleNotifyIntent(Intent i) {
        if (i == null) return;
        if (i.getBooleanExtra("fromNotify", false)) {
            int p = i.getIntExtra("page", 0);
            if (p >= 0 && p <= 3) switchPage(p);
        }
        if (i.getBooleanExtra("showTunnels", false)) {
            h.postDelayed(new Runnable() {
                @Override public void run() { allTunnelsDialog(); }
            }, 350);
        }
    }

    /** 作者 QQ 官方加好友跳转页（实际跳转统一交给 QqHelper）。 */
    public static final String QQ_URL = QqHelper.QQ_URL;

    /** v3.0.1：用内置官方落地页直达 QQ 加好友页，零联网、零剪贴板写入。 */
    private void openQq() {
        final String uin = sp.getString("qqUin", "").trim();
        final String landing = sp.getString("qqLanding", QqHelper.QQ_LANDING_DEFAULT);
        final String rawUin = sp.getString("qqRawUin", QqHelper.QQ_RAWUIN_DEFAULT);
        final String cardType = sp.getString("qqCardType", QqHelper.QQ_CARDTYPE_DEFAULT);
        QqHelper.Result r = QqHelper.launch(this, landing, rawUin, cardType, uin);
        toast(r.message);
        log("[作者] " + r.detail + " · " + r.message + " · 本机直达（无需联网）");
    }

    /** 可选：联网向 QQ 官方刷新落地页，失败也不影响内置直达。 */
    private void refreshQqLanding() {
        toast("正在向 QQ 官方解析加好友页…");
        bg(new Job() {
            @Override public String work() { return QqHelper.resolveNetwork().encode(); }
            @Override public void done(String r) {
                QqHelper.Resolve res = QqHelper.Resolve.decode(r);
                if ("offline".equals(res.source)) {
                    toast("解析失败，已保留内置加好友页，仍可直达");
                    log("[作者] 加好友页刷新失败，继续使用内置落地页");
                    return;
                }
                sp.edit().putString("qqLanding", res.landing)
                        .putString("qqRawUin", res.rawUin)
                        .putString("qqCardType", res.cardType)
                        .putString("qqResolveSource", res.source).commit();
                toast("已刷新 QQ 官方加好友页");
                log("[作者] 已刷新官方落地页 · 来源=" + res.source);
            }
        });
    }

    /** 弹窗里独立的「用浏览器打开」，不再和 QQ 跳转混在一个按钮里。 */
    private void openQqInBrowser() {
        QqHelper.Result r = QqHelper.openBrowser(this);
        toast(r.message);
        log("[作者] " + r.detail + " · " + r.message);
    }

    /** 联系作者 v3.0：显示检测结果、可填 QQ 号、三个动作彼此不再混淆。 */
    private void authorDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 4);
        box.setPadding(pad, pad, pad, pad);
        box.addView(Ui.osTitle(this, "联系作者", "v3.1.0"), Ui.gap(this, 8));
        box.addView(Ui.osTip(this,
                "已修复「只跳到 QQ 主界面」：现在走 QQ 官方落地页的 Android 通道"
                + "（mqqopensdkapi://bizAgent/qm/qr）直达加好友页，短链解析一次就本机缓存。"
                + "也不会再自动复制链接 —— 只有点「复制链接」才写剪贴板。", 2),
                Ui.gap(this, 10));

        box.addView(Ui.section(this, "本机检测"));
        box.addView(Ui.osRow(this, QqHelper.status(this), QqHelper.diagnose(this),
                "", true), Ui.match());
        String qs = sp.getString("qqResolveSource", "");
        box.addView(Ui.osRow(this, "加好友页解析",
                "内置官方落地页（带 k）· 点击直达，无需联网"
                        + (qs.length() > 0 ? " · 已刷新（" + qs + "）" : ""),
                "", false), Ui.match());
        box.addView(Ui.button(this, "↻ 刷新加好友页（联网解析一次）", 2,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { refreshQqLanding(); }
                }), Ui.gap(this, 6));

        box.addView(Ui.section(this, "作者 QQ 号（可选，直达指定资料卡）"), Ui.gap(this, 8));
        final EditText etUin = Ui.edit(this,
                "填纯数字 QQ 号，留空则走官方加好友页", 1, false, false);
        etUin.setText(sp.getString("qqUin", ""));
        box.addView(etUin, Ui.gap(this, 4));
        TextView uinTip = new TextView(this);
        uinTip.setText("留空即可：会用官方短链直达；填了号码则优先打开该 QQ 的资料卡。");
        uinTip.setTextSize(10);
        uinTip.setTextColor(Ui.MUTED);
        box.addView(uinTip);

        box.addView(Ui.section(this, "QQ 加好友链接"), Ui.gap(this, 8));
        TextView url = new TextView(this);
        url.setText(QQ_URL);
        url.setTextSize(12);
        url.setTextColor(Ui.BLUE);
        url.setTextIsSelectable(true);
        url.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        box.addView(url);
        box.addView(Ui.osRow(this, "用途", "问题反馈 / 功能建议 / 隧道排障", "", true), Ui.match());
        box.addView(Ui.osRow(this, "隐私",
                "点击只跳转 QQ / 浏览器，本 App 不收集、不上传任何设备或账号信息", "", false),
                Ui.match());
        // v3.0.1：全 App 唯一写剪贴板的地方，且 3 秒内重复点不重复写
        box.addView(Ui.button(this, "复制链接", 2, new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean wrote = QqHelper.copyLink(MainActivity.this);
                toast(wrote ? "已复制加好友链接" : "链接已在剪贴板，无需重复复制");
            }
        }), Ui.gap(this, 6));

        new GlassDialog.Builder(this)
                .setTitle("✉ 联系作者")
                .setSubtitle("直达 QQ 加好友页 · 不再自动复制")
                .setView(box)
                .setPositiveButton("打开 QQ 加好友", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sp.edit().putString("qqUin", etUin.getText().toString().trim()).commit();
                        openQq();
                    }
                })
                .setNeutralButton("用浏览器打开", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { openQqInBrowser(); }
                })
                .setNegativeButton("关闭", null).show();
    }

    /** 首次启动必须同意；设置页可随时重看。 */
    private void showDisclaimer(final boolean first) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        box.addView(Ui.osTip(this,
                "一句话：本 App 只在你手机本地跑，不提供任何服务器；"
                + "唯一的外部连接是 ChmlFrp 官方 API 与官方节点。", 2), Ui.gap(this, 10));
        TextView t = new TextView(this);
        t.setText(Disclaimer.SHORT);
        t.setTextSize(12);
        t.setTextColor(Ui.TEXT);
        t.setLineSpacing(Ui.dp(this, 4), 1f);
        box.addView(t);
        TextView hosts = new TextView(this);
        StringBuilder hb = new StringBuilder("\n本版本会访问的域名：");
        for (String hh : Disclaimer.hosts()) hb.append("\n  · ").append(hh);
        hosts.setText(hb.toString());
        hosts.setTextSize(10);
        hosts.setTextColor(Ui.MUTED);
        hosts.setLineSpacing(Ui.dp(this, 2), 1f);
        box.addView(hosts, Ui.gap(this, 10));

        GlassDialog.Builder b = new GlassDialog.Builder(this)
                .setTitle("免责声明 · 使用前请阅读")
                .setSubtitle("第三方本地启动器 · 非官方客户端")
                .setView(box)
                .setPositiveButton("我已阅读并同意", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Disclaimer.agree(MainActivity.this);
                        if (first) { toast("感谢确认 · 可到设置页随时重看"); log("[声明] 已阅读并同意免责声明"); }
                    }
                });
        b.setNeutralButton("查看完整条款", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                d.dismiss();
                fullDisclaimer();
            }
        });
        if (first) b.setCancelable(false);
        else b.setNegativeButton("关闭", null);
        b.show();
    }

    /** 完整条款 + 域名清单。 */
    private void fullDisclaimer() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        TextView t = new TextView(this);
        t.setText(Disclaimer.full());
        t.setTextSize(12);
        t.setTextColor(Ui.TEXT);
        t.setLineSpacing(Ui.dp(this, 4), 1f);
        box.addView(t);
        new GlassDialog.Builder(this)
                .setTitle("完整免责声明")
                .setTip("可在设置 → 网络与隐私里查看实际访问的域名清单", 2)
                .setView(box)
                .setPositiveButton("我已阅读并同意", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Disclaimer.agree(MainActivity.this);
                        toast("已记录");
                    }
                })
                .setNeutralButton("复制条款", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        copy(Disclaimer.full());
                    }
                })
                .setNegativeButton("关闭", null).show();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleCallback(i);
    }

    @Override protected void onResume() {
        super.onResume();
        // 从系统设置页回来后立刻重算权限与适配分数，不用手动点「重新评估」
        renderDevice();
        updateLoginBadge();
    }

    @Override public void onRequestPermissionsResult(
            int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == Perm.REQ_NOTIFY) {
            boolean ok = grants != null && grants.length > 0
                    && grants[0] == PackageManager.PERMISSION_GRANTED;
            toast(ok ? "通知权限已授予" : "通知权限未授予，可到系统设置里打开");
            log("[权限] 通知权限结果：" + (ok ? "已授予" : "未授予"));
            renderDevice();
        }
    }

    /** 磁贴后台启动被系统拒绝时降级：打开 App 后自动完成那一次启停。 */
    private void handleTileRequest(Intent i) {
        if (i == null) return;
        String r = i.getStringExtra("tileRequest");
        if (r == null || r.length() == 0) return;
        i.removeExtra("tileRequest");
        if ("stop".equals(r)) {
            FrpService.want(this, false);
            startService(new Intent(this, FrpService.class)
                    .setAction(FrpService.ACTION_STOP));
            toast("已按磁贴请求停止隧道");
        } else if ("start".equals(r)) {
            startTunnel("config", "", "");
            toast("已按磁贴请求启动隧道");
        }
    }

    @Override protected void onPause() { savePrefs(); super.onPause(); }
    @Override protected void onDestroy() { h.removeCallbacks(ticker); super.onDestroy(); }

    // ================================================================ UI

    private void buildUi() {
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().setStatusBarColor(Ui.HEADER_START);
            // 各主题头部都是深色调，状态栏图标统一用浅色
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        // 玻璃主题：光斑铺在最底层，半透明卡片叠在上面
        FrameLayout outer = new FrameLayout(this);
        if (Ui.GLASS) {
            outer.addView(new GlassBackground(this), new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            outer.setBackgroundColor(Ui.BG);
        }

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);   // 玻璃主题下 BG 为透明
        root.setFitsSystemWindows(true);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    v.setPadding(0, insets.getSystemWindowInsetTop(),
                            0, insets.getSystemWindowInsetBottom());
                    return insets;
                }
            });
        }

        root.addView(header());

        for (int i = 0; i < 4; i++) {
            ScrollView sv = new ScrollView(this);
            sv.setFillViewport(true);
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            int p = Ui.dp(this, 12);
            content.setPadding(p, p, p, p);
            sv.addView(content);
            pages[i] = sv;
            root.addView(sv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        LinearLayout c0 = (LinearLayout) pages[0].getChildAt(0);
        c0.addView(statusCard(), Ui.gap(this, 10));
        c0.addView(Ui.section(this, "隧道与流量"));
        c0.addView(tunnelCard(), Ui.gap(this, 10));
        c0.addView(signWrapper(), Ui.gap(this, 10));
        c0.addView(Ui.section(this, "账号与隧道"));
        c0.addView(tunnelListCard(), Ui.gap(this, 10));
        c0.addView(accountCard(), Ui.gap(this, 10));
        c0.addView(Ui.section(this, "快捷操作"));
        c0.addView(quickCard(), Ui.gap(this, 8));

        LinearLayout c1 = (LinearLayout) pages[1].getChildAt(0);
        c1.addView(Ui.section(this, "配置管理"));
        c1.addView(configCard(), Ui.gap(this, 8));

        LinearLayout c2 = (LinearLayout) pages[2].getChildAt(0);
        c2.addView(Ui.section(this, "运行日志"));
        c2.addView(logCard(), Ui.gap(this, 8));

        LinearLayout c3 = (LinearLayout) pages[3].getChildAt(0);
        c3.addView(Ui.section(this, "设备与适配"));
        c3.addView(deviceCard(), Ui.gap(this, 10));
        c3.addView(Ui.section(this, "运行与系统"));
        c3.addView(systemCard(), Ui.gap(this, 8));

        root.addView(tabBar());
        root.addView(bottomBar());
        outer.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(outer);
    }

    private View header() {
        LinearLayout hd = new LinearLayout(this);
        hd.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Ui.HEADER_START, Ui.HEADER_END});
        hd.setBackground(g);
        int p = Ui.dp(this, 16);
        hd.setPadding(p, Ui.dp(this, 16), p, Ui.dp(this, 16));
        hd.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        // 标题行：标题 + 右侧「联系作者」（v2.9）
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText("ChmlFrp 启动器");
        t.setTextSize(19);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFFFFFFFF);
        titleRow.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView author = new TextView(this);
        author.setText("✉ 联系作者");
        author.setTextSize(10);
        author.setTypeface(Typeface.DEFAULT_BOLD);
        author.setTextColor(0xFFFFFFFF);
        author.setBackground(Ui.round(0x33FFFFFF, 14, this));
        int ap = Ui.dp(this, 9);
        author.setPadding(ap, Ui.dp(this, 5), ap, Ui.dp(this, 5));
        author.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { authorDialog(); }
        });
        titleRow.addView(author);
        texts.addView(titleRow);

        TextView sub = new TextView(this);
        sub.setText("安卓端 frpc · v2 API · 免 Root · v3.1.0");
        sub.setTextSize(11);
        sub.setTextColor(0xFFD6E4FF);
        sub.setPadding(0, Ui.dp(this, 3), 0, 0);
        texts.addView(sub);
        // v3.0：把「功能标签」和「QQ 链接」合并成一行，头部少一行、不再挤压内容
        TextView qq = new TextView(this);
        qq.setText("多隧道 · 隐藏后台  ·  🔗 点击加作者 QQ 好友");
        qq.setTextSize(10);
        qq.setSingleLine(true);
        qq.setEllipsize(android.text.TextUtils.TruncateAt.END);
        qq.setTextColor(0xFFBBDEFB);
        qq.setPaintFlags(qq.getPaintFlags()
                | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        qq.setPadding(0, Ui.dp(this, 2), 0, 0);
        qq.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openQq(); }
        });
        texts.addView(qq);

        tvUptime = new TextView(this);
        tvUptime.setText("未运行");
        tvUptime.setTextSize(11);
        tvUptime.setTextColor(0xFFBBDEFB);
        tvUptime.setPadding(0, Ui.dp(this, 2), 0, 0);
        texts.addView(tvUptime);
        hd.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnTheme = new Button(this);
        btnTheme.setText("◐");
        btnTheme.setTextSize(18);
        btnTheme.setAllCaps(false);
        btnTheme.setBackground(Ui.round(0x33FFFFFF, 20, this));
        btnTheme.setTextColor(0xFFFFFFFF);
        btnTheme.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { themeDialog(); }
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                Ui.dp(this, 38), Ui.dp(this, 38));
        tp.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        hd.addView(btnTheme, tp);

        tvPill = new TextView(this);
        tvPill.setText("○ 未运行");
        tvPill.setTextSize(12);
        tvPill.setTypeface(Typeface.DEFAULT_BOLD);
        tvPill.setTextColor(0xFFFFFFFF);
        tvPill.setBackground(Ui.round(0x33FFFFFF, 16, this));
        int pp = Ui.dp(this, 9);
        tvPill.setPadding(pp, Ui.dp(this, 6), pp, Ui.dp(this, 6));
        hd.addView(tvPill);
        return hd;
    }

    private View tabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable line = Ui.round(Ui.BAR_BG, 0, this);
        line.setStroke(1, Ui.LINE);
        bar.setBackground(line);
        int p = Ui.dp(this, 6);
        bar.setPadding(p, p, p, p);

        final String[] icons = {"⌂", "⚙", "▤", "⋯"};
        final String[] names = {"控制台", "配置", "日志", "设置"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            View ind = new View(this);
            ind.setBackground(Ui.round(i == 0 ? Ui.BLUE : 0x00000000, 2, this));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 3));
            ilp.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 3));
            wrap.addView(ind, ilp);
            tabInd[i] = ind;
            tabs[i] = Ui.tab(this, icons[i], names[i], i == 0,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { switchPage(idx); }
                    });
            wrap.addView(tabs[i], new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            bar.addView(wrap, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return bar;
    }

    private void switchPage(int i) {
        page = i < 0 || i > 3 ? 0 : i;
        for (int k = 0; k < 4; k++) {
            pages[k].setVisibility(k == page ? View.VISIBLE : View.GONE);
            tabs[k].setBackground(Ui.round(k == page ? Ui.BLUE_LIGHT : 0x00000000, 10, this));
            tabs[k].setTextColor(k == page ? Ui.BLUE : Ui.MUTED);
            if (tabInd[k] != null) tabInd[k].setBackground(
                    Ui.round(k == page ? Ui.BLUE : 0x00000000, 2, this));
        }
    }

    private void applyTheme(int id) {
        themeId = Theme.clamp(id);
        sp.edit().putInt("themeId", themeId).commit();
        Ui.apply(Theme.resolve(this, themeId));
        refreshNotify();
        recreate();
    }

    /** 主题选择器：21 套主题按分组，带色板预览，点击即时生效。 */
    private void themeDialog() {
        final android.app.Dialog[] holder = new android.app.Dialog[1];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 14);
        box.setPadding(pad, pad, pad, pad);

        // 不再自己套 ScrollView：交给 GlassDialog 的唯一滚动容器（修「不能下滑」）
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);

        String lastGroup = "";
        for (int i = 0; i < Theme.count(); i++) {
            final int idx = i;
            final Theme.Def d = Theme.of(i);
            final boolean selected = (i == themeId);
            if (!d.group.equals(lastGroup)) {
                lastGroup = d.group;
                LinearLayout gh = new LinearLayout(this);
                gh.setOrientation(LinearLayout.HORIZONTAL);
                gh.setGravity(android.view.Gravity.CENTER_VERTICAL);
                View ln1 = new View(this);
                ln1.setBackgroundColor(Ui.alpha(Ui.BLUE, 0x70));
                gh.addView(ln1, new LinearLayout.LayoutParams(Ui.dp(this, 18),
                        Math.max(1, Ui.dp(this, 1))));
                TextView gt = new TextView(this);
                gt.setText("  " + lastGroup + "  ");
                gt.setTextSize(11);
                gt.setTypeface(Typeface.DEFAULT_BOLD);
                gt.setTextColor(Ui.BLUE);
                gh.addView(gt);
                View ln2 = new View(this);
                ln2.setBackgroundColor(Ui.alpha(Ui.BLUE, 0x35));
                gh.addView(ln2, new LinearLayout.LayoutParams(0,
                        Math.max(1, Ui.dp(this, 1)), 1f));
                gh.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 6));
                grid.addView(gh);
            }

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(android.view.Gravity.CENTER_VERTICAL);
            item.setBackground(Ui.roundStroke(
                    selected ? Ui.BLUE_LIGHT : Ui.CARD,
                    selected ? Ui.BLUE : Ui.LINE, selected ? 2 : 1, 12, this));
            int ip = Ui.dp(this, 12);
            item.setPadding(ip, ip, ip, ip);

            // 三个色块：顶栏 / 卡片 / 强调色
            LinearLayout sw = new LinearLayout(this);
            sw.setOrientation(LinearLayout.HORIZONTAL);
            int[] cols = {d.headerStart, d.card == 0 ? d.spotBg : d.card, d.blue};
            for (int k = 0; k < 3; k++) {
                View v = new View(this);
                v.setBackground(Ui.swatch(this, cols[k], 6));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        Ui.dp(this, 22), Ui.dp(this, 22));
                lp.setMargins(0, 0, Ui.dp(this, 4), 0);
                sw.addView(v, lp);
            }
            item.addView(sw);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView n = new TextView(this);
            n.setText((selected ? "✓  " : "   ") + d.name
                    + (d.glass ? "  ◈ 玻璃" : d.dark ? "  ☾ 深色" : "  ☀ 浅色"));
            n.setTextSize(14);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            n.setTextColor(selected ? Ui.BLUE : Ui.TEXT);
            texts.addView(n);
            TextView ds = new TextView(this);
            ds.setText(d.desc);
            ds.setTextSize(11);
            ds.setTextColor(Ui.MUTED);
            texts.addView(ds);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tlp.setMargins(Ui.dp(this, 10), 0, 0, 0);
            item.addView(texts, tlp);

            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (holder[0] != null) holder[0].dismiss();
                    applyTheme(idx);
                }
            });
            grid.addView(item, Ui.gap(this, 8));
        }
        box.addView(grid);

        holder[0] = new GlassDialog.Builder(this)
                .setTitle("选择主题（" + Theme.count() + " 套 · 5 套液态玻璃）")
                .setSubtitle("当前：" + Theme.of(themeId).name + " · 点击即时切换")
                .setTip("21 套主题全部套用动态液态半透明玻璃弹窗；5 套玻璃主题带流动光斑", 0)
                .setView(box)
                .setNeutralButton("惊喜一下", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int id = new java.util.Random().nextInt(Theme.count());
                        if (holder[0] != null) holder[0].dismiss();
                        applyTheme(id);
                        toast("已换成「" + Theme.of(id).name + "」");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private View statusCard() {
        LinearLayout c = Ui.card(this);

        LinearLayout line1 = new LinearLayout(this);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tvKernel = new TextView(this);
        tvKernel.setText("内核：检测中…");
        tvKernel.setTextSize(13);
        tvKernel.setTypeface(Typeface.DEFAULT_BOLD);
        tvKernel.setTextColor(Ui.TEXT);
        line1.addView(tvKernel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvKernelBadge = Ui.badge(this, "检测中", Ui.MUTED);
        line1.addView(tvKernelBadge);
        c.addView(line1, Ui.gap(this, 8));

        tvCompat = new TextView(this);
        tvCompat.setText("正在检测内核能力…");
        tvCompat.setTextSize(12);
        tvCompat.setTextColor(Ui.MUTED);
        tvCompat.setBackground(Ui.round(Ui.PREVIEW_BG, 8, this));
        int pp = Ui.dp(this, 10);
        tvCompat.setPadding(pp, pp, pp, pp);
        c.addView(tvCompat, Ui.gap(this, 8));

        tvSpeedMini = new TextView(this);
        tvSpeedMini.setText("↑  0 B/s     ↓  0 B/s");
        tvSpeedMini.setTextSize(15);
        tvSpeedMini.setTypeface(Typeface.DEFAULT_BOLD);
        tvSpeedMini.setTextColor(Ui.TEXT);
        tvSpeedMini.setPadding(0, 0, 0, Ui.dp(this, 6));
        c.addView(tvSpeedMini);

        tvGuard = new TextView(this);
        tvGuard.setText("守护：检测中");
        tvGuard.setTextSize(12);
        tvGuard.setTextColor(Ui.MUTED);
        c.addView(tvGuard, Ui.gap(this, 8));

        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        tvLoginState = Ui.badge(this, "未登录", Ui.MUTED);
        badges.addView(tvLoginState);
        tvFitBadge = Ui.badge(this, "适配度 —", Ui.BLUE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        badges.addView(tvFitBadge, blp);
        tvFitBadge.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { deviceDetailDialog(); }
        });
        tvLoginState.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { oneKeyPermissions(); }
        });
        c.addView(badges, Ui.gap(this, 6));
        TextView nt = Ui.osTip(this,
                "通知栏可直接「▶ 启动隧道 / ■ 停止 / ⟳ 重启」；停止后保留一条待机通知，不打开 App 也能再运行。", 2);
        c.addView(nt, Ui.gap(this, 8));

        c.addView(Ui.row(this,
                Ui.button(this, "启动预检", 1, new View.OnClickListener() {
                    @Override public void onClick(View v) { preflight(); }
                }),
                Ui.button(this, "测试节点", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { probeNode(); }
                })));
        return c;
    }

    private View tunnelCard() {
        LinearLayout c = Ui.card(this);

        // 标题行：隧道名 / ID + 在线徽章
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tvTunnelName = new TextView(this);
        tvTunnelName.setText("未选择隧道");
        tvTunnelName.setTextSize(15);
        tvTunnelName.setTypeface(Typeface.DEFAULT_BOLD);
        tvTunnelName.setTextColor(Ui.TEXT);
        top.addView(tvTunnelName, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvOnlineBadge = Ui.badge(this, "○ 未运行", Ui.MUTED);
        top.addView(tvOnlineBadge);
        c.addView(top, Ui.gap(this, 6));

        tvTunnelMeta = new TextView(this);
        tvTunnelMeta.setText("节点 — · 协议 — · 本地 —");
        tvTunnelMeta.setTextSize(12);
        tvTunnelMeta.setTextColor(Ui.MUTED);
        c.addView(tvTunnelMeta, Ui.gap(this, 10));

        // 连接域名块（点击打开浏览器，长按复制）
        LinearLayout dom = new LinearLayout(this);
        dom.setOrientation(LinearLayout.VERTICAL);
        dom.setBackground(Ui.round(Ui.PREVIEW_BG, 10, this));
        int dp = Ui.dp(this, 12);
        dom.setPadding(dp, dp, dp, dp);
        TextView cap = new TextView(this);
        cap.setText("连接地址 / 域名");
        cap.setTextSize(11);
        cap.setTextColor(Ui.MUTED);
        dom.addView(cap);
        tvDomain = new TextView(this);
        tvDomain.setText("拉取配置后自动显示");
        tvDomain.setTextSize(18);
        tvDomain.setTypeface(Typeface.DEFAULT_BOLD);
        tvDomain.setTextColor(Ui.BLUE);
        tvDomain.setPadding(0, Ui.dp(this, 4), 0, 0);
        dom.addView(tvDomain);
        tvDomainSub = new TextView(this);
        tvDomainSub.setText("点击打开 · 长按复制");
        tvDomainSub.setTextSize(11);
        tvDomainSub.setTextColor(Ui.MUTED);
        tvDomainSub.setPadding(0, Ui.dp(this, 2), 0, 0);
        dom.addView(tvDomainSub);
        c.addView(dom, Ui.gap(this, 8));

        View.OnClickListener openLink = new View.OnClickListener() {
            @Override public void onClick(View v) { openEndpoint(); }
        };
        tvDomain.setOnClickListener(openLink);
        tvDomain.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                copy(currentEndpoint());
                return true;
            }
        });
        dom.setOnClickListener(openLink);

        LinearLayout schemeRow = new LinearLayout(this);
        schemeRow.setOrientation(LinearLayout.HORIZONTAL);
        schemeRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tvSchemeBadge = Ui.badge(this, "协议  自动", Ui.BLUE);
        schemeRow.addView(tvSchemeBadge);
        TextView schemeTip = new TextView(this);
        schemeTip.setText("  ← 点这里切换 HTTP / HTTPS / 自动");
        schemeTip.setTextSize(11);
        schemeTip.setTextColor(Ui.MUTED);
        schemeRow.addView(schemeTip);
        View.OnClickListener schemeClick = new View.OnClickListener() {
            @Override public void onClick(View v) { schemeDialog(); }
        };
        tvSchemeBadge.setOnClickListener(schemeClick);
        schemeTip.setOnClickListener(schemeClick);
        c.addView(schemeRow, Ui.gap(this, 8));

        tvConnCount = new TextView(this);
        tvConnCount.setText("连接数 — · 面板状态 —");
        tvConnCount.setTextSize(11);
        tvConnCount.setTextColor(Ui.MUTED);
        tvConnCount.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 8));
        c.addView(tvConnCount);

        // 分隔线
        View sep = new View(this);
        sep.setBackgroundColor(Ui.LINE);
        c.addView(sep, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
        TextView spCap = new TextView(this);
        spCap.setText("实时速度");
        spCap.setTextSize(11);
        spCap.setTypeface(Typeface.DEFAULT_BOLD);
        spCap.setTextColor(Ui.MUTED);
        spCap.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        c.addView(spCap);

        LinearLayout speeds = new LinearLayout(this);
        speeds.setOrientation(LinearLayout.HORIZONTAL);
        tvSpeedUp = new TextView(this);
        tvSpeedUp.setText("↑  0 B/s");
        tvSpeedUp.setTextSize(16);
        tvSpeedUp.setTypeface(Typeface.DEFAULT_BOLD);
        tvSpeedUp.setTextColor(Ui.BLUE);
        speeds.addView(tvSpeedUp, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvSpeedDown = new TextView(this);
        tvSpeedDown.setText("↓  0 B/s");
        tvSpeedDown.setTextSize(16);
        tvSpeedDown.setTypeface(Typeface.DEFAULT_BOLD);
        tvSpeedDown.setTextColor(Ui.GREEN);
        speeds.addView(tvSpeedDown, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(speeds, Ui.gap(this, 6));

        speedBar = new SpeedBar(this);
        c.addView(speedBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 26)));

        tvChartCap = new TextView(this);
        tvChartCap.setText("最近 60 秒 · 蓝=上行 绿=下行");
        tvChartCap.setTextSize(10);
        tvChartCap.setTextColor(Ui.MUTED);
        tvChartCap.setPadding(0, Ui.dp(this, 6), 0, 0);
        c.addView(tvChartCap);
        sparkline = new Sparkline(this);
        c.addView(sparkline, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 62)));

        tvTrafficSession = new TextView(this);
        tvTrafficSession.setText("本次 ↑ 0 B  ↓ 0 B");
        tvTrafficSession.setTextSize(11);
        tvTrafficSession.setTextColor(Ui.TEXT);
        tvTrafficSession.setPadding(0, Ui.dp(this, 8), 0, 0);
        c.addView(tvTrafficSession);
        tvTrafficTotal = new TextView(this);
        tvTrafficTotal.setText("累计 ↑ 0 B  ↓ 0 B");
        tvTrafficTotal.setTextSize(11);
        tvTrafficTotal.setTextColor(Ui.MUTED);
        c.addView(tvTrafficTotal);
        tvTrafficPeak = new TextView(this);
        tvTrafficPeak.setText("峰值 ↑ 0 B/s  ↓ 0 B/s");
        tvTrafficPeak.setTextSize(11);
        tvTrafficPeak.setTextColor(Ui.MUTED);
        c.addView(tvTrafficPeak, Ui.gap(this, 8));

        c.addView(Ui.row(this,
                Ui.button(this, "打开地址", 1, new View.OnClickListener() {
                    @Override public void onClick(View v) { openEndpoint(); }
                }),
                Ui.button(this, "复制", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { copy(currentEndpoint()); }
                }),
                Ui.button(this, "分享", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { shareEndpoint(); }
                })));
        c.addView(Ui.row(this,
                Ui.button(this, "刷新面板状态", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { refreshTunnelStatus(); }
                }),
                Ui.button(this, "清零统计", 3, new View.OnClickListener() {
                    @Override public void onClick(View v) { resetTraffic(); }
                })));
        c.addView(Ui.row(this,
                Ui.button(this, "连接诊断", 1, new View.OnClickListener() {
                    @Override public void onClick(View v) { connectionDiagnose(); }
                }),
                Ui.button(this, "用 HTTP 打开", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { openHttp(); }
                }),
                Ui.button(this, "协议切换", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { schemeDialog(); }
                })));
        c.addView(Ui.button(this, "复制全部信息", 2, new View.OnClickListener() {
            @Override public void onClick(View v) { copyAllInfo(); }
        }));

        tvTunnelHint = new TextView(this);
        tvTunnelHint.setText("速度按本应用 UID 采样（含 frpc 全部流量），"
                + "与面板服务端统计口径可能略有差异。");
        tvTunnelHint.setTextSize(10);
        tvTunnelHint.setTextColor(Ui.MUTED);
        tvTunnelHint.setPadding(0, Ui.dp(this, 8), 0, 0);
        c.addView(tvTunnelHint);
        return c;
    }

    /** 控制台首页直接显示所有隧道概览，不用点进去才看得到。 */
    private View tunnelListCard() {
        LinearLayout c = Ui.card(this);
        final java.util.List<TunnelStore.T> all = TunnelStore.load(this);
        int inCfg = Config.proxyCount(etConfig != null
                ? etConfig.getText().toString() : Config.readCurrent(this));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText("所有隧道");
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Ui.TEXT);
        head.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.badge(this, all.size() + " 条", Ui.BLUE));
        c.addView(head, Ui.gap(this, 6));

        if (inCfg > 1) {
            c.addView(Ui.osTip(this,
                    "当前配置含 " + inCfg + " 个代理段 —— 一个 frpc 进程正在同时运行 "
                    + inCfg + " 条隧道。", 0), Ui.gap(this, 8));
        }
        if (all.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("还没有缓存列表 · 登录后点「刷新 / 选隧道」即可显示全部");
            e.setTextSize(11);
            e.setTextColor(Ui.MUTED);
            c.addView(e, Ui.gap(this, 8));
        } else {
            int show = Math.min(3, all.size());
            for (int i = 0; i < show; i++) {
                TunnelStore.T x = all.get(i);
                TextView r = new TextView(this);
                r.setText((x.onlineLooksUp() ? "●  " : "○  ") + x.title()
                        + "   " + (x.node.length() > 0 ? x.node : ""));
                r.setTextSize(12);
                r.setTextColor(x.onlineLooksUp() ? Ui.GREEN : Ui.TEXT);
                r.setPadding(Ui.dp(this, 4), Ui.dp(this, 3), 0, Ui.dp(this, 3));
                c.addView(r);
            }
            if (all.size() > show) {
                TextView more = new TextView(this);
                more.setText("… 还有 " + (all.size() - show) + " 条，点下方查看全部");
                more.setTextSize(11);
                more.setTextColor(Ui.MUTED);
                c.addView(more, Ui.gap(this, 4));
            }
        }
        c.addView(Ui.row(this,
                Ui.button(this, "查看全部 / 多选启动", 1, new View.OnClickListener() {
                    @Override public void onClick(View v) { allTunnelsDialog(); }
                }),
                Ui.button(this, "刷新列表", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { tunnelsClick.onClick(null); }
                })));
        return c;
    }

    private View accountCard() {
        LinearLayout c = Ui.card(this);

        etToken = Ui.edit(this, "Access Token（OAuth 登录后自动填写）", 1, false, true);
        c.addView(etToken, Ui.gap(this, 6));

        c.addView(Ui.row(this,
                Ui.button(this, "显示 / 隐藏", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { toggleToken(); }
                }),
                Ui.button(this, "OAuth 登录", 1, loginClick)));

        etNode = Ui.edit(this, "节点名称，如：英国伦敦", 1, false, false);
        c.addView(etNode, Ui.gap(this, 8));
        etTunnel = Ui.edit(this, "隧道名（多个用逗号分隔，留空 = 该节点全部）", 1, false, false);
        c.addView(etTunnel, Ui.gap(this, 8));

        c.addView(Ui.row(this,
                Ui.button(this, "刷新 / 选隧道", 1, tunnelsClick),
                Ui.button(this, "拉取配置", 2, configClick)));
        c.addView(Ui.row(this,
                Ui.button(this, "📋 所有隧道", 1, new View.OnClickListener() {
                    @Override public void onClick(View v) { allTunnelsDialog(); }
                }),
                Ui.button(this, "🔑 登录方式", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { loginMethodsDialog(); }
                })), Ui.gap(this, 6));
        c.addView(Ui.button(this, "⚡ 一键拉取并启动", 0, oneClickClick));

        tvLink = new TextView(this);
        tvLink.setText("连接地址：选择隧道后显示");
        tvLink.setTextSize(12);
        tvLink.setTextColor(Ui.BLUE);
        tvLink.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, 0);
        tvLink.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                copy(tvLink.getText().toString().replace("连接地址：", ""));
                return true;
            }
        });
        c.addView(tvLink);
        return c;
    }

    private View quickCard() {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.row(this,
                Ui.button(this, "打开连接地址", 1, new View.OnClickListener() {
                    @Override public void onClick(View v) { openEndpoint(); }
                }),
                Ui.button(this, "看运行日志", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { switchPage(2); }
                })));
        c.addView(Ui.row(this,
                Ui.button(this, "启动预检", 1, new View.OnClickListener() {
                    @Override public void onClick(View v) { preflight(); }
                }),
                Ui.button(this, "日志诊断", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { diagnoseLog(); }
                })));
        c.addView(Ui.row(this,
                Ui.button(this, "⟳ 重启隧道", 1, new View.OnClickListener() {
                    @Override public void onClick(View v) { restartTunnel(); }
                }),
                Ui.button(this, "架构说明", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { architectureDialog(); }
                })));
        c.addView(Ui.button(this, "🔑 官方参数启动（-u / -p，实验）", 2,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { officialStartDialog(); }
                }));
        c.addView(Ui.row(this,
                Ui.button(this, "🔎 一键自检", 1, new View.OnClickListener() {
                    @Override public void onClick(View v) { fullSelfCheck(); }
                }),
                Ui.button(this, "v3.0 更新", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { about(); }
                })), Ui.gap(this, 6));
        TextView tip = new TextView(this);
        tip.setText("启动 / 停止 / 重启都能在通知栏完成 · 停止后仍可从待机通知一键运行");
        tip.setTextSize(11);
        tip.setTextColor(Ui.MUTED);
        tip.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), 0, 0);
        c.addView(tip);
        return c;
    }

    private View configCard() {
        LinearLayout c = Ui.card(this);

        spConfigs = new Spinner(this);
        c.addView(spConfigs, Ui.gap(this, 8));
        c.addView(Ui.row(this,
                Ui.button(this, "＋ 新建", 1, new View.OnClickListener() {
                    @Override public void onClick(View v) { newConfigDialog(); }
                }),
                Ui.button(this, "删除", 3, new View.OnClickListener() {
                    @Override public void onClick(View v) { deleteConfigDialog(); }
                }),
                Ui.button(this, "粘贴", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { pasteConfig(); }
                })));

        c.addView(Ui.row(this,
                Ui.button(this, "配置模板", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { templateDialog(); }
                }),
                Ui.button(this, "导入文件", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { importConfig(); }
                }),
                Ui.button(this, "导出 / 分享", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { exportConfig(); }
                })));

        c.addView(Ui.row(this,
                Ui.button(this, "INI ⇄ TOML", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { convertDialog(); }
                }),
                Ui.button(this, "配置自检", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { selfCheck(); }
                }),
                Ui.button(this, "清空编辑器", 3, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        applyConfig("", "[配置] 编辑器已清空");
                    }
                })));

        tvFormat = new TextView(this);
        tvFormat.setText("格式：空");
        tvFormat.setTextSize(12);
        tvFormat.setTypeface(Typeface.DEFAULT_BOLD);
        tvFormat.setTextColor(Ui.MUTED);
        tvFormat.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, Ui.dp(this, 4));
        c.addView(tvFormat);

        tvEndpoints = new TextView(this);
        tvEndpoints.setText("连接地址：解析中…");
        tvEndpoints.setTextSize(11);
        tvEndpoints.setTextColor(Ui.BLUE);
        tvEndpoints.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), 0, Ui.dp(this, 6));
        tvEndpoints.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copy(currentEndpoint()); }
        });
        tvEndpoints.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                String[] eps = Config.endpoints(etConfig.getText().toString());
                StringBuilder sb = new StringBuilder();
                for (String e : eps) sb.append(e).append('\n');
                copy(sb.toString().trim());
                return true;
            }
        });
        c.addView(tvEndpoints);

        tvPreview = new TextView(this);
        tvPreview.setText("（暂无配置）");
        tvPreview.setTextSize(11);
        tvPreview.setTypeface(Typeface.MONOSPACE);
        tvPreview.setTextColor(Ui.MUTED);
        tvPreview.setBackground(Ui.round(Ui.PREVIEW_BG, 8, this));
        int pp = Ui.dp(this, 10);
        tvPreview.setPadding(pp, pp, pp, pp);
        c.addView(tvPreview, Ui.gap(this, 8));

        btnExpand = Ui.button(this, "展开 / 收起配置编辑器", 2,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { toggleEditor(); }
                });
        c.addView(btnExpand);

        editorWrap = new LinearLayout(this);
        editorWrap.setOrientation(LinearLayout.VERTICAL);
        editorWrap.setVisibility(View.GONE);
        etConfig = Ui.edit(this, "在此粘贴或编辑配置（格式以状态卡为准）",
                10, true, false);
        editorWrap.addView(etConfig, Ui.gap(this, 6));
        c.addView(editorWrap);

        c.addView(Ui.button(this, "保存配置", 0, saveClick));
        return c;
    }

    private View systemCard() {
        LinearLayout c = Ui.card(this);
        cardHost = c;

        cbWatchdog = new CheckBox(this);
        cbWatchdog.setText("隧道掉线自动重连");
        cbWatchdog.setTextSize(13);
        cbWatchdog.setTextColor(Ui.TEXT);
        cbWatchdog.setChecked(true);
        c.addView(cbWatchdog, Ui.gap(this, 4));

        c.addView(Ui.button(this, "重连次数上限：5 次", 2,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { chooseRestartCount(); }
                }), Ui.gap(this, 6));

        cbAuto = new CheckBox(this);
        cbAuto.setText("开机自动启动隧道");
        cbAuto.setTextSize(13);
        cbAuto.setTextColor(Ui.TEXT);
        c.addView(cbAuto, Ui.gap(this, 4));

        cbNotifySpeed = new CheckBox(this);
        cbNotifySpeed.setText("通知栏实时刷新（3 秒一次）");
        cbNotifySpeed.setTextSize(13);
        cbNotifySpeed.setTextColor(Ui.TEXT);
        c.addView(cbNotifySpeed, Ui.gap(this, 4));

        cbNotifyIdle = new CheckBox(this);
        cbNotifyIdle.setText("停止后保留「▶ 启动隧道」待机通知（通知栏可直接运行）");
        cbNotifyIdle.setTextSize(13);
        cbNotifyIdle.setTextColor(Ui.TEXT);
        cbNotifyIdle.setChecked(true);
        c.addView(cbNotifyIdle, Ui.gap(this, 4));

        cbFoldRepeat = new CheckBox(this);
        cbFoldRepeat.setText("日志折叠连续重复行 / 合并同类错误（不再刷屏）");
        cbFoldRepeat.setTextSize(13);
        cbFoldRepeat.setTextColor(Ui.TEXT);
        cbFoldRepeat.setChecked(true);
        cbFoldRepeat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { rebuildLog(); }
        });
        c.addView(cbFoldRepeat, Ui.gap(this, 4));

        cbNotifyFull = new CheckBox(this);
        cbNotifyFull.setText("通知栏展开显示隧道 / 域名 / 流量全量信息");

        cbNotifyTheme = new CheckBox(this);
        cbNotifyTheme.setText("通知栏图标与强调色跟随主题");
        cbNotifyTheme.setTextSize(13);
        cbNotifyTheme.setTextColor(Ui.TEXT);
        c.addView(cbNotifyTheme, Ui.gap(this, 4));
        cbNotifyFull.setTextSize(13);
        cbNotifyFull.setTextColor(Ui.TEXT);
        c.addView(cbNotifyFull, Ui.gap(this, 4));

        c.addView(Ui.section(this, "通知栏内容"));
        c.addView(Ui.row(this,
                Ui.button(this, "选择显示字段（" + NotifyFields.count() + " 项）", 1,
                        new View.OnClickListener() {
                            @Override public void onClick(View v) { notifyFieldsDialog(); }
                        }),
                Ui.button(this, "折叠行显示什么", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { notifyCollapsedDialog(); }
                })), Ui.gap(this, 6));
        c.addView(Ui.button(this, "恢复通知栏全量信息", 2, new View.OnClickListener() {
            @Override public void onClick(View v) {
                sp.edit().putBoolean("notifyFull", true)
                        .putLong("notifyFields", NotifyFields.all())
                        .putString("notifyCollapsed", "speed").commit();
                cbNotifyFull.setChecked(true);
                refreshNotify();
                toast("已恢复全量信息");
            }
        }), Ui.gap(this, 8));

        cbAutoRefresh = new CheckBox(this);
        cbAutoRefresh.setText("每 30 秒刷新面板隧道状态");
        cbAutoRefresh.setTextSize(13);
        cbAutoRefresh.setTextColor(Ui.TEXT);
        c.addView(cbAutoRefresh, Ui.gap(this, 4));

        cbBinaryUnits = new CheckBox(this);
        cbBinaryUnits.setText("按 1024 进制换算（关闭则按 1000）");
        cbBinaryUnits.setTextSize(13);
        cbBinaryUnits.setTextColor(Ui.TEXT);
        c.addView(cbBinaryUnits, Ui.gap(this, 8));

        // ---------------- v2.8：通知栏高级 ----------------
        c.addView(Ui.section(this, "通知栏高级"));
        cbNotifyEvents = new CheckBox(this);
        cbNotifyEvents.setText("掉线 / 重连 / 流量事件单独提醒（不覆盖常驻通知）");
        cbNotifyEvents.setTextSize(13);
        cbNotifyEvents.setTextColor(Ui.TEXT);
        cbNotifyEvents.setChecked(sp.getBoolean("notifyEvents", true));
        cbNotifyEvents.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sp.edit().putBoolean("notifyEvents", cbNotifyEvents.isChecked()).commit();
            }
        });
        c.addView(cbNotifyEvents, Ui.gap(this, 4));
        c.addView(Ui.row(this,
                Ui.button(this, "通知栏密度（紧凑 / 标准 / 全量）", 1,
                        new View.OnClickListener() {
                            @Override public void onClick(View v) { notifyDensityDialog(); }
                        }),
                Ui.button(this, "玻璃强度", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { glassLevelDialog(); }
                })), Ui.gap(this, 6));
        c.addView(Ui.button(this, "通知栏动作组合（停止 / 重启 / 复制 / 日志 / 列表…）", 2,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { notifyActionsDialog(); }
                }), Ui.gap(this, 6));
        TextView nt = Ui.osTip(this,
                "OriginOS 等 ROM 对展开通知的行数有硬上限，默认「紧凑」只显示 5 行关键信息，"
                + "避免「网络 / 流量」被系统截掉。动作按钮文字对比度也已修正。", 2);
        c.addView(nt, Ui.gap(this, 10));

        // ---------------- v2.8：隐藏后台 / 隐私显示 ----------------
        c.addView(Ui.section(this, "隐藏后台 / 隐私显示"));
        cbHideRecents = privCheck("不在最近任务里显示本 App（可随时恢复）", Privacy.K_RECENTS);
        cbSilentNotify = privCheck("通知最小化（降优先级、不染色、不展开长内容）", Privacy.K_SILENT);
        cbLockPrivate = privCheck("锁屏隐藏通知内容（不露出域名与隧道名）", Privacy.K_LOCK);
        cbMaskEndpoint = privCheck("通知里打码连接域名 / 隧道名（显示 ••••••）", Privacy.K_MASK);
        c.addView(Ui.osTip(this,
                "Android 8+ 要求前台服务必须有通知，系统不允许完全隐藏，"
                + "只能降到最低优先级 / 最小化；部分 ROM 会强制提升。"
                + "「隐藏后台」不等于保活，后台常驻仍取决于电池白名单与自启动。", 1),
                Ui.gap(this, 8));
        c.addView(Ui.row(this,
                Ui.button(this, "全部恢复默认", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Privacy.set(MainActivity.this, Privacy.K_RECENTS, false);
                        Privacy.set(MainActivity.this, Privacy.K_SILENT, false);
                        Privacy.set(MainActivity.this, Privacy.K_LOCK, false);
                        Privacy.set(MainActivity.this, Privacy.K_MASK, false);
                        syncPrivacyChecks();
                        Privacy.applyRecents(MainActivity.this);
                        refreshNotify();
                        toast("已恢复默认显示");
                    }
                }),
                Ui.button(this, "网络与隐私", 1, new View.OnClickListener() {
                    @Override public void onClick(View v) { hostsDialog(); }
                })), Ui.gap(this, 10));

        c.addView(Ui.section(this, "权限体检"));
        c.addView(Ui.button(this, "🔑 一键获取权限 / 查看权限状态", 1,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { oneKeyPermissions(); }
                }), Ui.gap(this, 10));

        c.addView(Ui.section(this, "外观与地址"));
        cbGlassMotion = new CheckBox(this);
        cbGlassMotion.setText("液态玻璃动态光斑 · 5 套玻璃主题通用（关闭可省电）");
        cbGlassMotion.setTextSize(13);
        cbGlassMotion.setTextColor(Ui.TEXT);
        c.addView(cbGlassMotion, Ui.gap(this, 4));
        TextView perfTip = new TextView(this);
        perfTip.setText("动态光斑会持续重绘全屏并叠加半透明层；\n"
                + "关闭后玻璃主题改为静态光斑，观感接近但几乎不耗额外电量。");
        perfTip.setTextSize(11);
        perfTip.setTextColor(Ui.MUTED);
        perfTip.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 8));
        c.addView(perfTip);
        c.addView(Ui.row(this,
                Ui.button(this, "◐ 选择主题（" + Theme.count() + " 套）", 1,
                        new View.OnClickListener() {
                            @Override public void onClick(View v) { themeDialog(); }
                        }),
                Ui.button(this, "自定义连接地址", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { customEndpointDialog(); }
                })), Ui.gap(this, 6));
        c.addView(Ui.button(this, "连接协议：自动 / HTTP / HTTPS", 2,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { schemeDialog(); }
                }), Ui.gap(this, 8));

        cbLogRedact = new CheckBox(this);
        cbLogRedact.setText("分享日志时自动脱敏（打码令牌 / 域名 / 用户名）");
        cbLogRedact.setTextSize(13);
        cbLogRedact.setTextColor(Ui.TEXT);
        cbLogRedact.setChecked(sp.getBoolean("logRedact", true));
        cbLogRedact.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sp.edit().putBoolean("logRedact", cbLogRedact.isChecked()).commit();
            }
        });
        c.addView(cbLogRedact, Ui.gap(this, 4));
        c.addView(Ui.row(this,
                Ui.button(this, "搜索日志", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { logSearchDialog(); }
                }),
                Ui.button(this, "免责声明", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { showDisclaimer(false); }
                })), Ui.gap(this, 6));
        c.addView(Ui.button(this, "✉ 联系作者 / 加 QQ 好友", 1, new View.OnClickListener() {
            @Override public void onClick(View v) { authorDialog(); }
        }), Ui.gap(this, 6));
        c.addView(Ui.row(this,
                Ui.button(this, "复制运行诊断", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { copyDiagnostics(); }
                }),
                Ui.button(this, "清空隧道缓存", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { clearTunnelCache(); }
                })), Ui.gap(this, 8));
        TextView dt = new TextView(this);
        dt.setText("运行诊断只含设备 / 内核 / 隧道条数，已脱敏，可直接发给作者排障。");
        dt.setTextSize(10);
        dt.setTextColor(Ui.MUTED);
        c.addView(dt, Ui.gap(this, 6));

        c.addView(Ui.button(this, "清零流量统计（本次 + 历史累计）", 3,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { resetTraffic(); }
                }), Ui.gap(this, 8));

        TextView note = new TextView(this);
        note.setText("参数/配置错误不会重连；网络恢复会自动重连；主动停止不会再拉起。");
        note.setTextSize(11);
        note.setTextColor(Ui.MUTED);
        note.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 10));
        c.addView(note);

        c.addView(Ui.row(this,
                Ui.button(this, "电池优化", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                             "请在本页把本应用设为「不优化」");
                    }
                }),
                Ui.button(this, "自启动 / 后台", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        open(appDetails(), "请在应用详情里允许自启动与后台运行");
                    }
                }),
                Ui.button(this, "通知设置", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { openNotificationSettings(); }
                })));

        c.addView(Ui.section(this, "版本更新"), Ui.gap(this, 8));
        LinearLayout upd = Ui.card(this);
        upd.addView(Ui.osTip(this,
                "检查更新连接本项目 GitHub 仓库的 Releases（api.github.com 公开接口），"
                + "只在点按钮时联网、只读取版本信息，不上传任何数据；发现新版只给下载入口，不自动安装。", 2),
                Ui.gap(this, 8));
        upd.addView(Ui.row(this,
                Ui.button(this, "检查更新", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { checkUpdate(); }
                }),
                Ui.button(this, "项目地址", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { openGithub(UpdateChecker.REPO_URL); }
                })), Ui.gap(this, 6));
        upd.addView(Ui.row(this,
                Ui.button(this, "在 GitHub 点个 Star", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { openGithub(UpdateChecker.STAR_URL); }
                }),
                Ui.button(this, "更新日志", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { openGithub(UpdateChecker.CHANGELOG_URL); }
                })), Ui.gap(this, 6));
        TextView uv = new TextView(this);
        uv.setText("当前版本 " + UpdateChecker.APP_VERSION
                + " · 数据源：github.com/" + UpdateChecker.REPO + "/releases（不点不联网）");
        uv.setTextSize(10);
        uv.setTextColor(Ui.MUTED);
        upd.addView(uv, Ui.gap(this, 6));
        c.addView(upd, Ui.gap(this, 10));

        c.addView(Ui.row(this,
                Ui.button(this, "清除 Token", 3, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        etToken.setText("");
                        savePrefs();
                        toast("已清除本机 Token");
                    }
                }),
                Ui.button(this, "使用帮助 / 关于", 1, new View.OnClickListener() {
                    @Override public void onClick(View v) { about(); }
                })));

        TextView end = new TextView(this);
        end.setText("主题、配置、日志与隧道信息均保存在本机；卸载即清除。");
        end.setTextSize(11);
        end.setTextColor(Ui.MUTED);
        end.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, 0);
        c.addView(end);
        return c;
    }

    private View logCard() {
        LinearLayout c = Ui.card(this);
        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.HORIZONTAL);

        cbFollow = new CheckBox(this);
        cbFollow.setText("自动滚动");
        cbFollow.setChecked(true);
        cbFollow.setTextSize(12);
        cbFollow.setTextColor(Ui.MUTED);
        opts.addView(cbFollow, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        cbOnlyErrors = new CheckBox(this);
        cbOnlyErrors.setText("只看异常");
        cbOnlyErrors.setTextSize(12);
        cbOnlyErrors.setTextColor(Ui.MUTED);
        cbOnlyErrors.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { rebuildLog(); }
        });
        opts.addView(cbOnlyErrors, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(opts);

        c.addView(Ui.row(this,
                Ui.button(this, "诊断", 1, new View.OnClickListener() {
                    @Override public void onClick(View v) { diagnoseLog(); }
                }),
                Ui.button(this, "分享日志", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { shareLog(); }
                }),
                Ui.button(this, "清空", 3, clearClick)));
        c.addView(Ui.button(this, "复制全部日志（含折叠标记）", 2,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        copy(foldRepeats(readLog()));
                        toast("已复制全部日志");
                    }
                }), Ui.gap(this, 6));

        svLog = new ScrollView(this);
        svLog.setBackground(Ui.round(Ui.LOG_BG, 9, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 340));
        lp.setMargins(0, Ui.dp(this, 8), 0, 0);
        tvLog = new TextView(this);
        tvLog.setText("");
        tvLog.setTextSize(11);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextColor(Ui.LOG_FG);
        int pp = Ui.dp(this, 10);
        tvLog.setPadding(pp, pp, pp, pp);
        svLog.addView(tvLog);
        c.addView(svLog, lp);
        return c;
    }

    private View bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int p = Ui.dp(this, 10);
        bar.setPadding(p, p, p, p);
        GradientDrawable line = Ui.round(Ui.BAR_BG, 0, this);
        line.setStroke(1, Ui.LINE);
        bar.setBackground(line);

        btnStart = Ui.button(this, "▶  启动隧道", 0, startClick);
        btnStop = Ui.button(this, "■  停止", 3, stopClick);
        bar.addView(btnStart, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sp2.setMargins(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(btnStop, sp2);
        return bar;
    }

    // ============================================================ 点击事件

    private final View.OnClickListener loginClick = new View.OnClickListener() {
        @Override public void onClick(View v) {
            try {
                String[] pk = Net.pkce();
                final String state = UUID.randomUUID().toString().replace("-", "");
                sp.edit().putString("verifier", pk[0]).putString("state", state).commit();
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(Net.authorizeUrl(pk[1], state))));
                toast("请在浏览器完成授权后返回");
                log("[登录] 已打开授权页");
            } catch (Exception e) { toast("无法打开授权页：" + e.getMessage()); }
        }
    };

    private final View.OnClickListener tunnelsClick = new View.OnClickListener() {
        @Override public void onClick(View v) {
            final String token = etToken.getText().toString().trim();
            if (token.length() == 0) { toast("请先登录或填写 Token"); return; }
            bg(new Job() {
                @Override public String work() throws Exception {
                    return Net.request(Net.API + "tunnel", token, null);
                }
                @Override public void done(String r) {
                    TunnelStore.save(MainActivity.this, r);
                    showTunnels(r);
                }
            });
        }
    };

    private final View.OnClickListener configClick = new View.OnClickListener() {
        @Override public void onClick(View v) { pullConfig(false); }
    };

    private final View.OnClickListener oneClickClick = new View.OnClickListener() {
        @Override public void onClick(View v) { pullConfig(true); }
    };

    private void pullConfig(final boolean autoStart) {
        pullConfigFor(etNode.getText().toString().trim(),
                etTunnel.getText().toString(), autoStart);
    }

    /** 一次拉取多条隧道的合并配置（官方 tunnel_names 参数原生支持）。 */
    private void pullConfigFor(final String node, final String tunnels,
                               final boolean autoStart) {
        final String token = etToken.getText().toString().trim();
        if (token.length() == 0) { toast("请先 OAuth 登录或填写 Token"); return; }
        if (node.length() == 0) { toast("请填写节点名称，或先刷新隧道选择"); return; }
        bg(new Job() {
            @Override public String work() throws Exception {
                String body = Net.request(Net.configUrl(node, tunnels), token, null);
                JSONObject o = new JSONObject(body);
                if (o.optInt("code", -1) != 200) {
                    throw new IllegalStateException("API: " + o.optString("msg", body));
                }
                return o.optString("data", "");
            }
            @Override public void done(String r) {
                if (r.length() == 0) { toast("接口返回配置为空"); return; }
                int n = Config.proxyCount(r);
                applyConfig(r, n > 1 ? "[配置] 已拉取 " + n + " 条隧道的合并配置"
                                     : "[配置] 已拉取并保存");
                if (n > 1) {
                    log("[多隧道] 配置含 " + n + " 个代理段，一个 frpc 进程同时启动");
                    toast("已拉取 " + n + " 条隧道，点启动即可全部运行");
                }
                if (autoStart) {
                    startTunnel("config", "", "");
                    switchPage(2);
                }
            }
        });
    }

    private final View.OnClickListener saveClick = new View.OnClickListener() {
        @Override public void onClick(View v) { persistEditor("[配置] 已保存"); toast("已保存"); }
    };

    private final View.OnClickListener startClick = new View.OnClickListener() {
        @Override public void onClick(View v) {
            persistEditor(null);
            File cfg = Config.current(MainActivity.this);
            if (!cfg.exists() || cfg.length() == 0) { toast("请先拉取或填写配置"); return; }
            if (ki.probed && !ki.configCompatible(etConfig.getText().toString())) {
                toast("配置格式与当前内核不兼容，请先看状态卡提示");
                switchPage(0);
                return;
            }
            startTunnel("config", "", "");
        }
    };

    private void startTunnel(String mode, String utoken, String pid) {
        FrpService.want(MainActivity.this, true);
        Intent s = new Intent(MainActivity.this, FrpService.class)
                .setAction(FrpService.ACTION_START)
                .putExtra(FrpService.EXTRA_MODE, mode)
                .putExtra(FrpService.EXTRA_UTOKEN, utoken)
                .putExtra(FrpService.EXTRA_PID, pid);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s);
        else startService(s);
        log("[启动器] 已请求启动（模式：" + mode + "）");
    }

    private final View.OnClickListener stopClick = new View.OnClickListener() {
        @Override public void onClick(View v) {
            FrpService.want(MainActivity.this, false);
            startService(new Intent(MainActivity.this, FrpService.class)
                    .setAction(FrpService.ACTION_STOP));
            log("[启动器] 已请求停止（不会自动重连）");
        }
    };

    private final View.OnClickListener clearClick = new View.OnClickListener() {
        @Override public void onClick(View v) {
            new File(getFilesDir(), "runtime.log").delete();
            logPos = 0;
            tvLog.setText("");
            toast("日志已清空");
        }
    };

    // =============================================================== OAuth

    private void handleCallback(Intent i) {
        if (i == null || i.getData() == null) return;
        Uri u = i.getData();
        if (!"chmlerp".equals(u.getScheme())) return;
        final String code = u.getQueryParameter("code");
        final String state = u.getQueryParameter("state");
        if (code == null) { log("[登录] 授权未完成"); return; }
        if (state == null || !state.equals(sp.getString("state", ""))) {
            log("[登录] state 校验失败，已忽略回调"); return;
        }
        final String verifier = sp.getString("verifier", "");
        bg(new Job() {
            @Override public String work() throws Exception {
                return Net.exchangeCode(code, verifier);
            }
            @Override public void done(String r) {
                try {
                    JSONObject o = new JSONObject(r);
                    String at = o.optString("access_token", "");
                    if (at.length() == 0) { log("[登录] 未拿到 token：" + r); return; }
                    etToken.setText(at);
                    long in = o.optLong("expires_in", 0);
                    String rt = o.optString("refresh_token", "");
                    sp.edit().putString("token", at)
                            .putString("refreshToken", rt)
                            .putLong("tokenExpires", in > 0
                                    ? System.currentTimeMillis() + in * 1000L : 0)
                            .putString("signUserId", "").commit();
                    savePrefs();
                    updateLoginBadge();
                    log("[登录] OAuth 授权成功，已保存令牌" +
                            (rt.length() > 0 ? "与续期凭证（下次免登录）" : "（本机保持登录）"));
                    toast("登录成功，下次打开无需再授权");
                    refreshSign(true);
                } catch (Exception e) { log("[登录] 解析失败：" + e); }
            }
        });
    }

    // ============================================================ 配置逻辑

    private void toggleEditor() {
        boolean show = editorWrap.getVisibility() != View.VISIBLE;
        editorWrap.setVisibility(show ? View.VISIBLE : View.GONE);
        btnExpand.setText(show ? "收起配置编辑器" : "展开 / 收起配置编辑器");
    }

    private void toggleToken() {
        boolean secret = (etToken.getInputType() &
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
        if (secret) etToken.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        else etToken.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etToken.setSelection(etToken.length());
    }

    private void refreshSpinner() {
        final List<String> names = Config.names(this);
        spinnerReady = false;
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, names) {
            @Override public View getView(int pos, View cv, android.view.ViewGroup parent) {
                View v = super.getView(pos, cv, parent);
                ((TextView) v).setTextColor(Ui.TEXT);
                return v;
            }
            @Override public View getDropDownView(int pos, View cv,
                                                  android.view.ViewGroup parent) {
                View v = super.getDropDownView(pos, cv, parent);
                ((TextView) v).setTextColor(Ui.TEXT);
                v.setBackgroundColor(Ui.CARD);
                return v;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spConfigs.setAdapter(ad);
        int idx = names.indexOf(curConfig);
        if (idx < 0) { curConfig = names.get(0); idx = 0; }
        spConfigs.setSelection(idx, false);
        spConfigs.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                    View view, int pos, long id) {
                String name = names.get(pos);
                if (!spinnerReady || name.equals(curConfig)) return;
                curConfig = name;
                String text = Config.read(MainActivity.this, name);
                etConfig.setText(text);
                try { Config.write(MainActivity.this, name, text); } catch (Exception ignored) {}
                refreshPreview();
                refreshCompat();
                savePrefs();
                log("[配置] 已切换到「" + name + "」");
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        spinnerReady = true;
    }

    private void applyConfig(String text, String logMsg) {
        etConfig.setText(text);
        try {
            Config.write(this, curConfig, text);
            refreshPreview();
            refreshCompat();
            log(logMsg + "（" + text.length() + " 字符 · " + Config.format(text) + "）");
            toast("配置已就绪，可点启动");
        } catch (Exception e) { toast("保存失败：" + e.getMessage()); }
    }

    private void persistEditor(String logMsg) {
        try {
            Config.write(this, curConfig, etConfig.getText().toString());
            refreshPreview();
            refreshCompat();
            if (logMsg != null) log(logMsg + " · " + Config.format(etConfig.getText().toString()));
        } catch (Exception e) { toast("保存失败：" + e.getMessage()); }
    }

    private void refreshPreview() {
        String s = etConfig.getText().toString();
        String f = Config.format(s);
        String hint = ki.formatHint(f);
        tvFormat.setText("格式：" + hint);
        boolean bad = ki.probed && !ki.configCompatible(s) && s.trim().length() > 0;
        tvFormat.setTextColor(bad ? Ui.RED : Ui.MUTED);
        if (s.trim().length() == 0) {
            tvPreview.setText("（暂无配置）");
        } else {
            String one = s.replaceAll("\\s+", " ").trim();
            tvPreview.setText(one.length() > 150 ? one.substring(0, 150) + " …" : one);
        }
        String[] eps = Config.endpoints(s);
        if (eps.length == 0) {
            tvEndpoints.setText("连接地址：未解析到（点此复制当前地址）");
        } else {
            StringBuilder sb = new StringBuilder("连接地址：");
            for (int i = 0; i < eps.length; i++) {
                if (i > 0) sb.append("  ·  ");
                sb.append(Config.displayHost(eps[i]));
            }
            sb.append("（点复制 / 长按复制全部）");
            tvEndpoints.setText(sb.toString());
        }
        renderTunnel();
    }

    private void refreshCompat() {
        String s = etConfig.getText().toString();
        if (!ki.probed) {
            tvCompat.setText("正在检测内核能力…");
            tvCompat.setTextColor(Ui.MUTED);
            return;
        }
        boolean bad = !ki.configCompatible(s) && s.trim().length() > 0;
        if (bad) {
            tvCompat.setText("⚠ 当前内核 " + ki.version + " 不支持 TOML，"
                    + "请换回面板生成的 INI 配置，否则无法启动。");
            tvCompat.setTextColor(Ui.RED);
        } else if (!ki.chmlfrp) {
            tvCompat.setText("⚠ " + ki.version + " 是标准 frp 内核，"
                    + "可能无法通过 ChmlFrp 魔改服务端认证；"
                    + "建议安装 arm64 版（内置官方魔改内核）。");
            tvCompat.setTextColor(Ui.WARN);
        } else {
            tvCompat.setText("✓ " + ki.version + " · " + ki.typeLabel()
                    + " · 只认 INI · 启动参数已自适应（只传 -c）");
            tvCompat.setTextColor(Ui.GREEN);
        }
    }

    // ==================================================== 隧道信息 / 流量

    /** 从 API 返回对象里按候选字段名取第一个非空值。 */
    private static String jpick(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            String s = String.valueOf(v).trim();
            if (s.length() > 0 && !"null".equalsIgnoreCase(s)) return s;
        }
        return "";
    }

    private String currentEndpoint() {
        String custom = sp.getString("customEndpoint", "").trim();
        String saved = sp.getString("tunnelDomain", "").trim();
        String raw = custom.length() > 0 ? custom : saved;
        if (raw.length() == 0) {
            String[] eps = Config.endpoints(etConfig.getText().toString());
            if (eps.length > 0) raw = eps[0];
        }
        if (raw.length() == 0) return "";
        return Config.applyScheme(this, raw);
    }

    private void openEndpoint() {
        String e = currentEndpoint();
        if (e.length() == 0) {
            toast("还没有连接地址，先拉取配置或选择隧道"); return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Config.urlOf(e))));
        } catch (Exception ex) { toast("无法打开：" + e); }
    }

    private void shareEndpoint() {
        String e = currentEndpoint();
        if (e.length() == 0) { toast("还没有连接地址"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, Config.urlOf(e));
            i.putExtra(Intent.EXTRA_SUBJECT, "ChmlFrp 连接地址");
            startActivity(Intent.createChooser(i, "分享连接地址"));
        } catch (Exception ex) { toast("分享失败：" + ex.getMessage()); }
    }

    private void resetTraffic() {
        Traffic.reset(this);
        refreshTraffic();
        toast("流量统计已清零");
        log("[流量] 统计已清零");
    }

    // ==================================== 通知栏配置 / 协议 / 地址 / 诊断

    /** 通知栏展开区显示哪些字段：15 项多选，关掉全量开关时用紧凑预设。 */
    private void notifyFieldsDialog() {
        final long cur = NotifyFields.read(sp);
        final boolean[] checked = new boolean[NotifyFields.count()];
        for (int i = 0; i < checked.length; i++) {
            checked[i] = NotifyFields.has(cur, i);
        }
        new GlassDialog.Builder(this)
                .setTitle("通知栏显示字段（" + NotifyFields.count() + " 项）")
                .setMessage("勾选的内容会在展开通知时逐行显示。"
                        + "部分 ROM 对展开行数有上限，勾太多可能只显示前几行。")
                .setMultiChoiceItems(NotifyFields.LABELS, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override public void onClick(DialogInterface d, int w, boolean isChecked) {
                                checked[w] = isChecked;
                            }
                        })
                .setPositiveButton("保存并刷新", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        long mask = NotifyFields.from(checked);
                        if (mask == 0) {
                            toast("至少保留一项，已自动恢复全量");
                            mask = NotifyFields.all();
                        }
                        sp.edit().putBoolean("notifyFull", true)
                                .putLong("notifyFields", mask).commit();
                        cbNotifyFull.setChecked(true);
                        refreshNotify();
                        toast("通知栏字段已更新");
                        log("[通知] 显示字段已自定义，共 "
                                + Long.bitCount(mask) + " 项");
                    }
                })
                .setNeutralButton("全选", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sp.edit().putBoolean("notifyFull", true)
                                .putLong("notifyFields", NotifyFields.all()).commit();
                        cbNotifyFull.setChecked(true);
                        refreshNotify();
                        toast("已全选");
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void notifyCollapsedDialog() {
        final String[] labels = {
                "速度优先：隧道名 · ↑ ↓ 速度",
                "地址优先：连接域名",
                "隧道优先：隧道名 · 速度",
                "节点优先：节点 · 速度",
        };
        final String[] vals = {"speed", "addr", "tunnel", "node"};
        String cur = sp.getString("notifyCollapsed", "speed");
        int checked = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(cur)) checked = i;
        new GlassDialog.Builder(this)
                .setTitle("通知栏折叠行显示什么")
                .setMessage("通知栏收起时只有一行；想看更多信息请展开通知。")
                .setSingleChoiceItems(labels, checked,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                sp.edit().putString("notifyCollapsed", vals[w]).commit();
                                d.dismiss();
                                refreshNotify();
                                toast("已设为：" + labels[w]);
                            }
                        })
                .setNegativeButton("取消", null).show();
    }

    /** 设置变更后立刻重建通知，不必等 3 秒轮询；服务没运行时什么都不做。 */
    private void refreshNotify() {
        if (!(FrpService.running || FrpService.waiting)) return;
        try {
            Intent i = new Intent(this, FrpService.class)
                    .setAction(FrpService.ACTION_REFRESH);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            log("[通知] 即时刷新失败：" + e.getMessage());
        }
    }

    /** 只改 App 里「打开 / 复制 / 显示」用的地址，不改 frpc 配置。 */
    private void schemeDialog() {
        final String[] labels = {"自动（按配置里的 type）", "强制 HTTPS", "强制 HTTP"};
        final String[] vals = {"auto", "https", "http"};
        String cur = sp.getString("endpointScheme", "auto");
        int checked = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(cur)) checked = i;
        new GlassDialog.Builder(this)
                .setTitle("连接地址协议")
                .setMessage("访问 https:// 若报 ERR_SSL_UNRECOGNIZED_NAME_ALERT，"
                        + "可临时切到 HTTP 试试。\n\n"
                        + "这里只影响 App 内打开/复制/显示的地址，不会修改隧道配置。")
                .setSingleChoiceItems(labels, checked,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                sp.edit().putString("endpointScheme", vals[w]).commit();
                                d.dismiss();
                                renderTunnel();
                                refreshTraffic();
                                refreshNotify();
                                toast("已设为 " + labels[w]);
                                log("[地址] 协议覆盖已设为 " + labels[w]);
                            }
                        })
                .setNegativeButton("取消", null).show();
    }

    private void customEndpointDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, pad, pad, 0);
        final EditText e = Ui.edit(this, "留空 = 用面板/配置解析出的地址", 1, false, false);
        e.setText(sp.getString("customEndpoint", ""));
        box.addView(e);
        new GlassDialog.Builder(this)
                .setTitle("自定义连接地址")
                .setMessage("面板没返回域名时可手动指定，填完整 URL 或裸域名都行。")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String v = e.getText().toString().trim();
                        sp.edit().putString("customEndpoint", v).commit();
                        renderTunnel();
                        refreshTraffic();
                        refreshNotify();
                        toast(v.length() > 0 ? "已保存" : "已恢复自动解析");
                        log("[地址] 自定义地址 = " + (v.length() > 0 ? v : "（空）"));
                    }
                })
                .setNeutralButton("清空", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sp.edit().putString("customEndpoint", "").commit();
                        renderTunnel();
                        toast("已清空");
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void openHttp() {
        String ep = currentEndpoint();
        if (ep.length() == 0) { toast("还没有连接地址"); return; }
        String url = "http://" + Config.displayHost(ep);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            log("[诊断] 已用 HTTP 打开 " + url);
        } catch (Exception e) { toast("无法打开：" + url); }
    }

    /** 后台跑 DNS / TCP / TLS SNI 探测，复现浏览器侧报错并给建议。 */
    private void connectionDiagnose() {
        final String ep = currentEndpoint();
        if (ep.length() == 0) { toast("还没有连接地址，先拉取配置"); return; }
        final String node = Config.server(etConfig.getText().toString())[0];
        toast("正在诊断 " + Config.displayHost(ep) + " …");
        bg(new Job() {
            @Override public String work() throws Exception {
                Diagnose.Result r = Diagnose.check(ep, node);
                return (r.ok ? "OK|" : "FAIL|") + r.report + "\n\n" + r.advice;
            }
            @Override public void done(String r) {
                String body = r.substring(r.indexOf('|') + 1);
                log("[诊断] " + body.replace('\n', ' '));
                new GlassDialog.Builder(MainActivity.this)
                        .setTitle(r.startsWith("OK|") ? "连接诊断 · 正常" : "连接诊断 · 发现问题")
                        .setMessage(body)
                        .setPositiveButton("知道了", null)
                        .setNegativeButton("用 HTTP 打开", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { openHttp(); }
                        })
                        .setNeutralButton("协议切换", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { schemeDialog(); }
                        }).show();
            }
        });
    }

    private void copyAllInfo() {
        boolean binary = cbBinaryUnits == null || cbBinaryUnits.isChecked();
        String ep = currentEndpoint();
        long[] tot = Traffic.grandTotal();
        StringBuilder sb = new StringBuilder("ChmlFrp 隧道信息\n");
        sb.append("隧道  ").append(sp.getString("tunnelName", etTunnel.getText().toString()))
          .append(' ').append(sp.getString("tunnelId", "")).append('\n')
          .append("节点  ").append(sp.getString("tunnelNode", etNode.getText().toString()))
          .append(" · ").append(Config.proxyType(etConfig.getText().toString())).append('\n')
          .append("地址  ").append(ep.length() > 0 ? Config.urlOf(ep) : "—").append('\n')
          .append("内核  ").append(ki.version).append('\n')
          .append("状态  ").append(FrpService.stateLabel())
          .append(" · ").append(FrpService.uptime()).append('\n')
          .append("实时  ↑ ").append(Units.speed(Traffic.txSpeed, binary))
          .append("  ↓ ").append(Units.speed(Traffic.rxSpeed, binary)).append('\n')
          .append("本次  ↑ ").append(Units.bytes(Traffic.sessionTx, binary))
          .append("  ↓ ").append(Units.bytes(Traffic.sessionRx, binary)).append('\n')
          .append("累计  ↑ ").append(Units.bytes(tot[1], binary))
          .append("  ↓ ").append(Units.bytes(tot[0], binary)).append('\n')
          .append("峰值  ↑ ").append(Units.speed(Traffic.txPeak, binary))
          .append("  ↓ ").append(Units.speed(Traffic.rxPeak, binary)).append('\n')
          .append("主题  ").append(Theme.of(themeId).name);
        copy(sb.toString());
    }

    /** 把选中/刷新得到的隧道详情落盘并渲染。 */
    private void saveTunnel(JSONObject t, boolean notify) {
        String node = jpick(t, "node", "nodename", "node_name");
        String name = jpick(t, "name", "tunnel_name", "tunnelname");
        String id = jpick(t, "id", "proxyid", "proxy_id", "tunnel_id", "tunnelid");
        String lip = jpick(t, "localip", "local_ip", "localIP");
        String lport = jpick(t, "nport", "localport", "local_port", "localPort");
        // 协议以本地配置为准：面板 API 的 type 可能返回内部类型 http，
        // 直接采用会把 https 隧道错标成 http，并把地址补成 http://
        String cfgText = etConfig.getText().toString();
        String cfgType = Config.proxyType(cfgText);
        String type = cfgType.length() > 0 ? cfgType : jpick(t, "type", "protocol");
        // 面板没返回本地端口时，从配置里兜底，避免出现 "127.0.0.1:—"
        String[] la = Config.localAddr(cfgText);
        if (lip.length() == 0) lip = la[0];
        if (lport.length() == 0) lport = la[1];
        String domain = jpick(t, "domain", "connectaddress", "connect_address",
                "connect", "url", "customdomain", "custom_domains", "customDomains");
        String ip = jpick(t, "ip", "serverip", "server_ip");
        String dorp = jpick(t, "dorp", "port", "remoteport", "remote_port");
        String conn = jpick(t, "conn", "connections", "connectnum",
                "connect_count", "connection", "connect_count");
        String state = jpick(t, "online", "state", "status", "nodestate", "isOnline");
        String time = jpick(t, "time", "lasttime", "createtime",
                "updated_at", "date", "last_time");

        String ep = domain;
        if (ep.length() == 0) {
            String[] eps = Config.endpoints(etConfig.getText().toString());
            if (eps.length > 0) ep = eps[0];
        }
        if (ep.length() == 0 && ip.length() > 0) {
            ep = dorp.length() > 0 ? ip + ":" + dorp : ip;
        }
        // 统一走协议覆盖：auto 时按配置 type 补 scheme，不再凭 API 字段猜
        if (ep.length() > 0) ep = Config.applyScheme(this, ep);

        sp.edit()
                .putString("tunnelName", name)
                .putString("tunnelId", id)
                .putString("tunnelNode", node)
                .putString("tunnelLocalIp", lip)
                .putString("tunnelLocalPort", lport)
                .putString("tunnelType", type)
                .putString("tunnelDomain", ep)
                .putString("tunnelConn", conn)
                .putString("tunnelState", state)
                .putString("tunnelTime", time)
                .putString("tunnelLink", ep)
                .commit();
        if (node.length() > 0) etNode.setText(node);
        if (name.length() > 0) etTunnel.setText(name);
        tvLink.setText("连接地址：" + (ep.length() > 0
                ? Config.displayHost(ep) : "节点 " + node) + "（长按复制，以面板为准）");
        renderTunnel();
        refreshTraffic();
        refreshNotify();
        if (notify) log("[隧道] 已保存 " + name + " · " + Config.displayHost(ep));
    }

    private void renderTunnel() {
        if (tvTunnelName == null) return;
        String name = sp.getString("tunnelName", "").trim();
        String id = sp.getString("tunnelId", "").trim();
        String node = sp.getString("tunnelNode", "").trim();
        String lip = sp.getString("tunnelLocalIp", "").trim();
        String lport = sp.getString("tunnelLocalPort", "").trim();
        String type = sp.getString("tunnelType", "").trim();
        String conn = sp.getString("tunnelConn", "").trim();
        String state = sp.getString("tunnelState", "").trim();
        String time = sp.getString("tunnelTime", "").trim();

        if (name.length() == 0) name = etTunnel.getText().toString().trim();
        if (name.length() == 0) name = "未选择隧道";
        tvTunnelName.setText(name + (id.length() > 0 ? "  " + id : ""));
        if (node.length() == 0) node = etNode.getText().toString().trim();

        String cfgText = etConfig.getText().toString();
        String cfgType = Config.proxyType(cfgText);
        if (cfgType.length() > 0) type = cfgType;   // 配置优先，修掉误显示 http
        String[] la = Config.localAddr(cfgText);
        if (lip.length() == 0) lip = la[0];
        if (lport.length() == 0) lport = la[1];
        tvTunnelMeta.setText("节点 " + (node.length() > 0 ? node : "—")
                + " · 协议 " + (type.length() > 0 ? type : "—")
                + " · 本地 " + (lip.length() > 0 ? lip : "127.0.0.1")
                + ":" + (lport.length() > 0 ? lport : "—"));

        String ep = currentEndpoint();
        if (ep.length() == 0) {
            tvDomain.setText("拉取配置后自动显示");
            tvDomainSub.setText("也可在下方「刷新 / 选隧道」里选一条");
            tvDomain.setTextColor(Ui.MUTED);
        } else {
            tvDomain.setText(Config.displayHost(ep));
            tvDomainSub.setText(Config.urlOf(ep) + " · 点击打开 · 长按复制");
            tvDomain.setTextColor(Ui.BLUE);
        }
        String linkText = currentEndpoint();
        if (linkText.length() > 0) {
            tvLink.setText("连接地址：" + Config.displayHost(linkText)
                    + "（长按复制，以面板为准）");
        }
        tvConnCount.setText("连接数 " + (conn.length() > 0 ? conn : "—")
                + " · 面板 " + (state.length() > 0 ? state : "—")
                + (time.length() > 0 ? " · " + time : ""));

        if (tvSchemeBadge != null) {
            String mode = sp.getString("endpointScheme", "auto");
            String shown = ep.startsWith("https://") ? "HTTPS"
                    : ep.startsWith("http://") ? "HTTP" : "—";
            String label = "auto".equals(mode) ? "自动" : mode.toUpperCase();
            tvSchemeBadge.setText("协议  " + label + " · " + shown);
            tvSchemeBadge.setBackground(Ui.roundStroke(0x00000000,
                    ep.startsWith("https://") ? Ui.GREEN : Ui.BLUE, 1, 8, this));
            tvSchemeBadge.setTextColor(ep.startsWith("https://") ? Ui.GREEN : Ui.BLUE);
        }
    }

    private void refreshTraffic() {
        if (speedBar == null) return;
        boolean binary = cbBinaryUnits == null || cbBinaryUnits.isChecked();
        boolean avail = Traffic.available;
        String up = avail ? Units.speed(Traffic.txSpeed, binary) : "不可用";
        String down = avail ? Units.speed(Traffic.rxSpeed, binary) : "不可用";
        tvSpeedUp.setText("↑  " + up);
        tvSpeedDown.setText("↓  " + down);
        if (tvSpeedMini != null) {
            tvSpeedMini.setText("↑  " + up + "     ↓  " + down);
        }
        speedBar.set(Traffic.txSpeed, Traffic.rxSpeed,
                Math.max(Traffic.txPeak, Traffic.rxPeak));
        if (sparkline != null) {
            double[][] hist = Traffic.history();
            sparkline.set(hist[0], hist[1]);
        }
        tvTrafficSession.setText("本次 ↑ " + Units.bytes(Traffic.sessionTx, binary)
                + "  ↓ " + Units.bytes(Traffic.sessionRx, binary));
        long[] tot = Traffic.grandTotal();
        tvTrafficTotal.setText("累计 ↑ " + Units.bytes(tot[1], binary)
                + "  ↓ " + Units.bytes(tot[0], binary));
        tvTrafficPeak.setText("峰值 ↑ " + Units.speed(Traffic.txPeak, binary)
                + "  ↓ " + Units.speed(Traffic.rxPeak, binary)
                + (avail ? "" : " · 设备不支持 UID 统计"));

        boolean run = FrpService.running;
        String badge = run ? "● 运行中" : FrpService.waiting ? "◌ 重连中" : "○ 未运行";
        tvOnlineBadge.setText(badge);
        int col = run ? Ui.GREEN : FrpService.waiting ? Ui.WARN : Ui.MUTED;
        tvOnlineBadge.setTextColor(col);
        tvOnlineBadge.setBackground(Ui.roundStroke(0x00000000, col, 1, 8, this));

        String base = run ? "已运行 " + FrpService.uptime()
                : FrpService.waiting ? "等待重连" : "未运行";
        tvUptime.setText(base + " · ↑ " + up + " ↓ " + down);
    }

    private void refreshTunnelStatus() { refreshTunnelStatus(false); }

    /** 从面板刷新在线状态 / 连接数（字段名宽松适配）。 */
    private void refreshTunnelStatus(final boolean silent) {
        final String token = etToken.getText().toString().trim();
        if (token.length() == 0) {
            if (!silent) toast("请先 OAuth 登录再刷新面板状态");
            return;
        }
        final String want = etTunnel.getText().toString().trim();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String r = Net.request(Net.API + "tunnel", token, null);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            try {
                                JSONObject o = new JSONObject(r);
                                JSONArray a = o.optJSONArray("data");
                                if (a == null || a.length() == 0) return;
                                JSONObject chosen = null;
                                for (int i = 0; i < a.length(); i++) {
                                    JSONObject t = a.getJSONObject(i);
                                    String n = jpick(t, "name", "tunnel_name", "tunnelname");
                                    if (want.length() == 0 || want.equals(n) || want.contains(n)) {
                                        chosen = t; break;
                                    }
                                }
                                if (chosen == null) chosen = a.getJSONObject(0);
                                saveTunnel(chosen, false);
                                if (!silent) toast("面板状态已刷新");
                            } catch (Exception e) {
                                if (!silent) toast("解析失败：" + e.getMessage());
                            }
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (!silent) toast("刷新失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void newConfigDialog() {
        final EditText e = Ui.edit(this, "配置名称，如：家里NAS", 1, false, false);
        new GlassDialog.Builder(this)
                .setTitle("新建配置")
                .setMessage("将以当前编辑器内容作为初始配置")
                .setView(e)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String n = e.getText().toString().trim();
                        if (!Config.create(MainActivity.this, n, etConfig.getText().toString())) {
                            toast("名称无效或已存在"); return;
                        }
                        curConfig = n.trim();
                        refreshSpinner();
                        refreshPreview();
                        savePrefs();
                        toast("已创建「" + curConfig + "」");
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void deleteConfigDialog() {
        new GlassDialog.Builder(this)
                .setTitle("删除配置")
                .setMessage("确定删除「" + curConfig + "」？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Config.delete(MainActivity.this, curConfig);
                        List<String> names = Config.names(MainActivity.this);
                        curConfig = names.get(0);
                        etConfig.setText(Config.read(MainActivity.this, curConfig));
                        try { Config.write(MainActivity.this, curConfig,
                                etConfig.getText().toString()); } catch (Exception ignored) {}
                        refreshSpinner();
                        refreshPreview();
                        refreshCompat();
                        savePrefs();
                        toast("已删除");
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void pasteConfig() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) { toast("剪贴板为空"); return; }
            CharSequence t = cd.getItemAt(0).getText();
            if (t == null) { toast("剪贴板没有文本"); return; }
            applyConfig(t.toString(), "[配置] 已从剪贴板导入");
            if (editorWrap.getVisibility() != View.VISIBLE) toggleEditor();
        } catch (Exception e) { toast("读取剪贴板失败：" + e.getMessage()); }
    }

    private void templateDialog() {
        final boolean toml = ki.probed && ki.toml;
        final String[] names = Templates.names(toml);
        new GlassDialog.Builder(this)
                .setTitle(toml ? "插入配置模板（INI / TOML 均可）"
                        : "插入配置模板（当前内核仅支持 INI）")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String old = etConfig.getText().toString();
                        String tpl = Templates.get(toml, which);
                        if (old.trim().length() > 0) old = old + "\n\n";
                        applyConfig(old + tpl, "[配置] 已插入模板「" + names[which] + "」");
                        if (editorWrap.getVisibility() != View.VISIBLE) toggleEditor();
                        toast("模板已插入，请改占位符后保存");
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void convertDialog() {
        final String src = etConfig.getText().toString();
        if (ki.probed && !ki.toml) {
            new GlassDialog.Builder(this)
                    .setTitle("当前内核不支持 TOML")
                    .setMessage("检测到 " + ki.version + "（ChmlFrp 魔改内核），"
                            + "它只认 INI 格式。\n\n转换成 TOML 会导致隧道无法启动。"
                            + "建议保持面板生成的 INI 不变。\n\n"
                            + "（此功能仅在换用标准 frp 0.6x 内核时有用）")
                    .setPositiveButton("保持 INI", null)
                    .setNegativeButton("仍要转换", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            doConvert(src);
                        }
                    }).show();
            return;
        }
        if (!Ini2Toml.looksLikeIni(src)) {
            toast("当前配置不是 INI，无需转换"); return;
        }
        doConvert(src);
    }

    private void doConvert(String src) {
        final String[] r = Ini2Toml.convert(src);
        new GlassDialog.Builder(this)
                .setTitle("INI → TOML")
                .setMessage(r[1] + "\n\n转换后建议点「配置自检」确认。")
                .setPositiveButton("应用转换", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        applyConfig(r[0], "[配置] 已转换为 TOML");
                        log("[转换] " + r[1]);
                        if (editorWrap.getVisibility() != View.VISIBLE) toggleEditor();
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void exportConfig() {
        String s = etConfig.getText().toString();
        if (s.trim().length() == 0) { toast("没有可导出的配置"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, s);
            i.putExtra(Intent.EXTRA_SUBJECT, curConfig + " · ChmlFrp 配置");
            startActivity(Intent.createChooser(i, "导出配置"));
        } catch (Exception e) { toast("导出失败：" + e.getMessage()); }
    }

    private void importConfig() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) { toast("无法打开文件选择器：" + e.getMessage()); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == Perm.REQ_BATTERY) {
            renderDevice();
            toast("已返回，适配度自动重算");
            return;
        }
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            InputStream is = getContentResolver().openInputStream(data.getData());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (is != null && (n = is.read(buf)) > 0) bos.write(buf, 0, n);
            if (is != null) is.close();
            String text = new String(bos.toByteArray(), "UTF-8");
            if (text.trim().length() == 0) { toast("文件内容为空"); return; }
            applyConfig(text, "[配置] 已从文件导入");
            if (editorWrap.getVisibility() != View.VISIBLE) toggleEditor();
        } catch (Exception e) { toast("导入失败：" + e.getMessage()); }
    }

    private void selfCheck() {
        persistEditor(null);
        final File cfg = Config.current(this);
        if (!cfg.exists() || cfg.length() == 0) { toast("配置为空，无法自检"); return; }
        if (ki.probed && !ki.configCompatible(etConfig.getText().toString())) {
            toast("配置格式与内核不兼容，自检无意义"); return;
        }
        bg(new Job() {
            @Override public String work() throws Exception { return FrpService.verify(MainActivity.this); }
            @Override public void done(String r) {
                log("[自检] " + r.trim().replace('\n', ' '));
                toast(r.contains("syntax is ok") || r.toLowerCase().contains("success")
                        ? "配置语法通过" : "请看自检日志");
            }
        });
    }

    private void probeNode() {
        final String[] s = Config.server(etConfig.getText().toString());
        if (s[0].length() == 0) { toast("未解析到节点地址，请检查配置"); return; }
        toast("正在测试 " + s[0] + ":" + s[1] + " …");
        bg(new Job() {
            @Override public String work() throws Exception {
                String[] r = FrpService.probe(s[0], s[1]);
                return (r[0].equals("true") ? "OK|" : "FAIL|") + r[1];
            }
            @Override public void done(String r) {
                boolean ok = r.startsWith("OK|");
                String msg = r.substring(r.indexOf('|') + 1);
                log("[连通] " + msg);
                toast((ok ? "✓ " : "✗ ") + msg);
            }
        });
    }

    /** 启动前预检：内核能力、配置格式、参数兼容、守护策略一次性汇总。 */
    private void preflight() {
        final String text = etConfig.getText().toString();
        final String[] sv = Config.server(text);
        final boolean watchdog = cbWatchdog.isChecked();
        final int maxRestart = sp.getInt("maxRestart", 5);
        bg(new Job() {
            @Override public String work() throws Exception {
                Kernel.Info k = Kernel.probe(MainActivity.this);
                String[] pr = FrpService.probe(sv[0], sv[1]);
                StringBuilder sb = new StringBuilder();
                sb.append("内核：").append(k.version).append('\n')
                  .append("类型：").append(k.typeLabel()).append('\n')
                  .append("ABI：").append(FrpService.abi()).append('\n')
                  .append("启动参数：-c <配置文件>（不附加任何可选 flag）\n")
                  .append("配置格式：").append(Config.format(text)).append('\n')
                  .append("格式兼容：").append(k.configCompatible(text) ? "✓ 兼容" : "✗ 不兼容")
                  .append('\n')
                  .append("配置非空：").append(text.trim().length() > 0 ? "✓" : "✗ 请先拉取配置")
                  .append('\n')
                  .append("节点连通：").append(pr[0].equals("true") ? "✓ " : "✗ ")
                  .append(pr[1]).append('\n')
                  .append("连通质量：").append(qualityOf(pr[1])).append('\n')
                  .append("守护：").append(watchdog
                        ? "开启，上限 " + maxRestart + " 次" : "关闭")
                  .append('\n');
                if (!k.configCompatible(text)) {
                    sb.append("\n⚠ 当前内核只支持 INI，请换回面板生成的配置。");
                } else if (text.trim().length() == 0) {
                    sb.append("\n⚠ 还没有配置，先点「拉取配置」。");
                } else if (!pr[0].equals("true")) {
                    sb.append("\n⚠ 节点连不上，检查手机网络或换节点。");
                } else {
                    sb.append("\n✓ 预检通过，可以点「启动隧道」。");
                }
                return sb.toString();
            }
            @Override public void done(String r) {
                new GlassDialog.Builder(MainActivity.this)
                        .setTitle("启动预检")
                        .setMessage(r)
                        .setPositiveButton("知道了", null)
                        .show();
                log("[预检] " + r.replace('\n', ' '));
            }
        });
    }

    /** 把「xxx ms」映射成看得懂的评级（3328 ms 就是偏差）。 */
    public static String qualityOf(String probe) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("([0-9]+)\\s*ms").matcher(probe);
            if (!m.find()) return "未知";
            int ms = Integer.parseInt(m.group(1));
            if (ms < 300) return "优（" + ms + " ms，游戏联机也够）";
            if (ms < 800) return "良（" + ms + " ms）";
            if (ms < 2000) return "一般（" + ms + " ms，建站/SSH 没问题）";
            return "偏差（" + ms + " ms，建议换个近一点的节点）";
        } catch (Exception e) { return "未知"; }
    }

    /** 读日志给针对性建议。 */
    private void diagnoseLog() {
        String s = readLog();
        StringBuilder sb = new StringBuilder();
        if (s.contains("unknown flag")) {
            sb.append("• 启动参数不兼容：这是 v2.0 的 bug（多传了 --strict-config）。\n")
              .append("  v2.1 已改为只传 -c，覆盖安装后不会再出现。\n\n");
        }
        if (s.contains("Permission denied") || s.contains("error=13")) {
            sb.append("• 内核不可执行：确认装的是本次 APK（内核在 lib/ 下由系统解压）。\n\n");
        }
        if (s.contains("login to the server failed") || s.contains("EOF")) {
            sb.append("• 登录被拒：核对配置里的 user / token 与面板一致；"
                    + "也可试「官方参数启动」用用户令牌+隧道 ID。\n\n");
        }
        if (s.contains("connect to server error: dial tcp") || s.contains("no such host")) {
            sb.append("• 节点连不上：用「测试节点」确认，换节点或检查网络。\n\n");
        }
        if (s.contains("[守护] 判定为不可重连错误")) {
            sb.append("• 守护已识别为不可重连错误并主动停下，不会空转。\n\n");
        }
        if (s.contains("ini format is deprecated")) {
            sb.append("• INI 弃用警告只出现在标准 frp 0.6x，可忽略或转 TOML。\n\n");
        }
        if (sb.length() == 0) {
            sb.append("未发现已知故障模式。\n\n")
              .append("当前状态：").append(FrpService.stateLabel()).append('\n')
              .append("内核：").append(ki.version).append('\n')
              .append("建议：先点控制台「启动预检」，再看运行日志。");
        }
        new GlassDialog.Builder(this).setTitle("日志诊断")
                .setMessage(sb.toString().trim())
                .setPositiveButton("知道了", null)
                .setNegativeButton("分享日志", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { shareLog(); }
                }).show();
    }

    /** 分享 / 复制日志脱敏：令牌、域名、用户名一律打码。 */
    private String redact(String s) {
        if (s == null) return "";
        String t = s;
        String tok = sp.getString("token", "").trim();
        if (tok.length() > 4) t = t.replace(tok, "[TOKEN]");
        t = t.replaceAll("(?im)^(\\s*(?:access_)?token\\s*[=:]\\s*).+$", "$1[TOKEN]");
        t = t.replaceAll("(?i)(custom_domains\\s*=\\s*)\\S+", "$1[DOMAIN]");
        t = t.replaceAll("(?i)(https?://)[a-z0-9.\\-]+\\.asia", "$1[DOMAIN]");
        t = t.replaceAll("(?i)(user\\s*=\\s*)\\S+", "$1[USER]");
        t = t.replaceAll("(?i)(getRunID\\s*\\[)[^\\]]+", "$1[RUNID]");
        return t + "\n\n（已脱敏：令牌 / 域名 / 用户名 / RunID）";
    }

    private void logSearchDialog() {
        final EditText e = Ui.edit(this, "输入关键词，如 映射启动 / 重连 / [错误]", 1, false, false);
        e.setText(logFilter);
        new GlassDialog.Builder(this)
                .setTitle("搜索日志")
                .setTip("留空并点「显示全部」可清除过滤；只看异常开关可叠加使用", 2)
                .setView(e)
                .setPositiveButton("筛选", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        logFilter = e.getText().toString().trim();
                        rebuildLog();
                        toast(logFilter.length() > 0 ? "已筛选：" + logFilter : "已显示全部");
                    }
                })
                .setNeutralButton("显示全部", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        logFilter = "";
                        cbOnlyErrors.setChecked(false);
                        rebuildLog();
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private CheckBox privCheck(String text, final String key) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setTextSize(13);
        cb.setTextColor(Ui.TEXT);
        cb.setChecked(sp.getBoolean(key, false));
        cb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = ((CheckBox) v).isChecked();
                Privacy.set(MainActivity.this, key, on);
                if (Privacy.K_RECENTS.equals(key)) {
                    if (!Privacy.applyRecents(MainActivity.this))
                        toast("本系统不支持隐藏最近任务，其他项仍可用");
                } else {
                    refreshNotify();
                }
                toast(Privacy.summary(MainActivity.this));
            }
        });
        c().addView(cb, Ui.gap(this, 4));
        return cb;
    }

    /** privCheck 需要拿到当前 systemCard 容器：用临时字段传递。 */
    private LinearLayout cardHost;
    private LinearLayout c() { return cardHost; }

    private void syncPrivacyChecks() {
        if (cbHideRecents != null) cbHideRecents.setChecked(Privacy.recents(this));
        if (cbSilentNotify != null) cbSilentNotify.setChecked(Privacy.silent(this));
        if (cbLockPrivate != null) cbLockPrivate.setChecked(Privacy.lockPrivate(this));
        if (cbMaskEndpoint != null) cbMaskEndpoint.setChecked(Privacy.mask(this));
    }

    /** 通知栏 3 个动作的组合（ROM 一般只显示 3 个，故做成可选）。 */
    private void notifyActionsDialog() {
        final String[] labels = {
                "默认 · 启停 / 重启或隧道列表 / 复制全部",
                "地址向 · 启停 / 打开地址 / 复制地址",
                "排障向 · 启停 / 看日志 / 隧道列表",
                "隐私向 · 启停 / 复制全部 / 静默切换",
                "多隧道 · 启停 / 隧道列表 / 复制全部",
        };
        int cur = sp.getInt("notifyActionSet", 0);
        new GlassDialog.Builder(this)
                .setTitle("通知栏动作组合")
                .setTip("系统通常只显示 3 个动作按钮，选一组最适合你的；"
                        + "未运行通知固定带「▶ 启动隧道」", 2)
                .setSingleChoiceItems(labels, cur, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sp.edit().putInt("notifyActionSet", w).commit();
                        refreshNotify();
                        toast("通知栏动作已更新");
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    /** 通知栏密度：0 紧凑（默认，防 ROM 截断）/ 1 标准 / 2 全量 */
    private void notifyDensityDialog() {
        final String[] labels = {
                "紧凑（推荐）· 5 行关键信息，OriginOS 不会截断",
                "标准 · 状态 / 隧道 / 节点 / 本地 / 地址 / 实时速度",
                "全量 · 再加内核 / 守护 / 网络 / 本次 / 累计流量",
        };
        int cur = sp.getInt("notifyDensity", 0);
        new GlassDialog.Builder(this)
                .setTitle("通知栏展开密度")
                .setTip("系统对展开通知的行数有上限，内容越多越可能被截断", 1)
                .setSingleChoiceItems(labels, cur, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sp.edit().putInt("notifyDensity", w).commit();
                        refreshNotify();
                        toast("已设为：" + labels[w].split(" · ")[0]);
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    /** 弹窗玻璃强度：0 通透 / 1 标准 / 2 厚实，三档都保留动态光斑。 */
    private void glassLevelDialog() {
        final String[] labels = {
                "通透 · 光斑最明显，背景内容隐约可见",
                "标准（推荐）· 平衡通透与文字可读性",
                "厚实 · 可读性最好，仍保留高光与描边",
        };
        int cur = sp.getInt("glassLevel", 1);
        new GlassDialog.Builder(this)
                .setTitle("弹窗玻璃强度")
                .setTip("21 套主题的所有弹窗都套用动态液态半透明玻璃；此项只调不透明度", 0)
                .setSingleChoiceItems(labels, cur, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sp.edit().putInt("glassLevel", w).commit();
                        toast("已设为：" + labels[w].split(" · ")[0] + " · 下次打开弹窗生效");
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void shareLog() {
        String s = readLog();
        if (s.length() == 0) { toast("日志为空"); return; }
        if (sp.getBoolean("logRedact", true)) s = redact(s);
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, s);
            i.putExtra(Intent.EXTRA_SUBJECT, "ChmlFrp 启动器 运行日志");
            startActivity(Intent.createChooser(i, "分享日志"));
        } catch (Exception e) { toast("分享失败：" + e.getMessage()); }
    }

    /** 魔改内核特有：-u 用户令牌 -p 隧道 ID，直接向服务端拉配置，无需本地文件。 */
    private void officialStartDialog() {
        if (ki.probed && !ki.idToken) {
            new GlassDialog.Builder(this)
                    .setTitle("当前内核不支持")
                    .setMessage(ki.version + " 没有 -u / -p 参数，"
                            + "请用常规「拉取配置 → 启动」流程。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 12);
        box.setPadding(pad, pad, pad, 0);
        final EditText eTok = Ui.edit(this, "ChmlFrp 用户令牌（面板「用户令牌」，非 OAuth Token）",
                1, false, true);
        final EditText eId = Ui.edit(this, "隧道 ID / ProxyID", 1, false, false);
        eTok.setText(sp.getString("officialToken", ""));
        eId.setText(sp.getString("officialId", ""));
        box.addView(eTok, Ui.gap(this, 8));
        box.addView(eId, Ui.gap(this, 4));
        TextView t = new TextView(this);
        t.setText("实验功能：魔改内核会用这两个参数直接向服务端取配置，不走本地 frpc.ini。");
        t.setTextSize(11);
        t.setTextColor(Ui.MUTED);
        box.addView(t);
        new GlassDialog.Builder(this)
                .setTitle("官方参数启动（实验）")
                .setView(box)
                .setPositiveButton("启动", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String ut = eTok.getText().toString().trim();
                        String pid = eId.getText().toString().trim();
                        if (ut.length() == 0 || pid.length() == 0) {
                            toast("请填用户令牌和隧道 ID"); return;
                        }
                        startTunnel("official", ut, pid);
                        switchPage(2);
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void chooseRestartCount() {
        final String[] labels = {"关闭重连（0 次）", "3 次", "5 次（默认）", "10 次"};
        final int[] vals = {0, 3, 5, 10};
        new GlassDialog.Builder(this)
                .setTitle("自动重连次数上限")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sp.edit().putInt("maxRestart", vals[w]).commit();
                        toast("已设为 " + labels[w]);
                        log("[守护] 重连上限已设为 " + labels[w]);
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void refreshKernelInfo() {
        bg(new Job() {
            @Override public String work() throws Exception {
                Kernel.Info k = Kernel.probe(MainActivity.this);
                return k.version + "|" + k.typeLabel() + "|" + k.chmlfrp + "|"
                        + k.toml + "|" + k.strictConfig + "|" + k.idToken;
            }
            @Override public void done(String r) {
                String[] a = r.split("\\|", 6);
                ki = Kernel.cached();
                tvKernel.setText("内核 " + a[0]);
                tvKernel.setTextColor(Ui.TEXT);
                tvKernelBadge.setText(a[1]);
                tvKernelBadge.setBackground(Ui.roundStroke(0x00000000,
                        "true".equals(a[2]) ? Ui.GREEN : Ui.BLUE, 1, 8, MainActivity.this));
                tvKernelBadge.setTextColor("true".equals(a[2]) ? Ui.GREEN : Ui.BLUE);
                refreshPreview();
                refreshCompat();
                log("[内核] " + a[0] + " · " + a[1]
                        + " · TOML=" + a[3] + " · strict=" + a[4] + " · idToken=" + a[5]);
            }
        });
    }

    // ============================================================ 隧道列表

    private void showTunnels(String r) {
        java.util.List<TunnelStore.T> all = TunnelStore.parse(r);
        if (all.isEmpty()) {
            tunnelEmptyDialog(r);
            log("[隧道] 接口已响应，但解析出 0 条；已显示原始响应诊断");
            return;
        }
        tunnelPicker(all, true);
    }

    /** 从本机缓存显示所有隧道（不联网也能看）。 */
    private void allTunnelsDialog() {
        java.util.List<TunnelStore.T> all = TunnelStore.load(this);
        if (all.isEmpty()) {
            tunnelEmptyDialog(TunnelStore.raw(this));
            return;
        }
        tunnelPicker(all, false);
    }

    /**
     * 图21 修复：v2.8 里「0 条隧道」仍渲染完整多选弹窗，
     * 固定 0.86 屏高 + weight=1 撑出一大片空白，还留着全选/清空按钮。
     * 现在改为紧凑诊断卡：把接口原始响应摊开、可粘贴 JSON 手动导入、可重新刷新。
     */
    private void tunnelEmptyDialog(final String rawJson) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 4);
        box.setPadding(pad, pad, pad, pad);

        box.addView(Ui.osTip(this,
                "接口已返回内容，但本机解析出 0 条隧道。常见原因：令牌过期、"
                + "面板返回错误码、或返回结构是「对象包数组」。下面是原始响应诊断，"
                + "可以据此判断，也可以直接粘贴 JSON 手动导入。", 1), Ui.gap(this, 10));

        TextView diag = new TextView(this);
        diag.setText(TunnelStore.diagnose(rawJson));
        diag.setTextSize(10);
        diag.setTextColor(Ui.TEXT);
        diag.setLineSpacing(Ui.dp(this, 2), 1f);
        diag.setTextIsSelectable(true);
        diag.setBackground(Ui.roundStroke(
                Ui.alpha(Ui.CARD, Ui.GLASS ? 0x70 : 0xFF), Ui.LINE, 1, 12, this));
        int dp = Ui.dp(this, 10);
        diag.setPadding(dp, dp, dp, dp);
        box.addView(diag, Ui.gap(this, 10));

        box.addView(Ui.section(this, "手动导入（离线也能用）"));
        final EditText paste = Ui.edit(this,
                "粘贴官方返回的隧道 JSON，或只粘贴 [{\"name\":\"A\"...}]", 4, true, false);
        box.addView(paste, Ui.gap(this, 6));
        TextView tip = new TextView(this);
        tip.setText("导入只写入本机缓存，不会发出任何请求；导入成功即可多选启动。");
        tip.setTextSize(10);
        tip.setTextColor(Ui.MUTED);
        box.addView(tip);

        new GlassDialog.Builder(this)
                .setTitle("所有隧道 · 暂无列表")
                .setSubtitle("原始响应诊断与手动导入")
                .setView(box)
                .setPositiveButton("解析并导入", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String txt = paste.getText().toString().trim();
                        if (txt.length() == 0) txt = rawJson;
                        TunnelStore.save(MainActivity.this, txt);
                        java.util.List<TunnelStore.T> got = TunnelStore.parse(txt);
                        if (got.isEmpty()) {
                            toast("仍解析不到隧道，请看上方原文预览");
                            log("[隧道] 手动导入解析失败，长度 " + txt.length());
                        } else {
                            toast("已导入 " + got.size() + " 条隧道（本机）");
                            log("[隧道] 手动导入成功，共 " + got.size() + " 条");
                            h.postDelayed(new Runnable() {
                                @Override public void run() { allTunnelsDialog(); }
                            }, 250);
                        }
                    }
                })
                .setNeutralButton("重新刷新", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        tunnelsClick.onClick(null);
                    }
                })
                .setNegativeButton("关闭", null).show();
    }

    /**
     * 所有隧道列表 + 多选启动。
     * 勾选多条后用 tunnel_names=A,B 拉一次合并配置，一个 frpc 进程同时带多个 proxy。
     */
    private void tunnelPicker(final java.util.List<TunnelStore.T> all,
                              final boolean justFetched) {
        final boolean[] picked = new boolean[all.size()];
        // 默认勾选当前正在用的那条
        String cur = etTunnel.getText().toString().trim();
        for (int i = 0; i < all.size(); i++) {
            if (cur.length() > 0 && cur.contains(all.get(i).name)) picked[i] = true;
        }
        if (justFetched && all.size() == 1) picked[0] = true;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));

        box.addView(Ui.osTip(this,
                "共 " + all.size() + " 条隧道。勾选多条后点「启动所选」，"
                + "官方接口会返回含多个代理段的合并配置，一个进程同时运行它们。"
                + "不同节点请分批启动。", 2), Ui.gap(this, 10));

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final CheckBox[] cbs = new CheckBox[all.size()];
        for (int i = 0; i < all.size(); i++) {
            final int idx = i;
            final TunnelStore.T t = all.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.TOP);
            row.setBackground(Ui.roundStroke(
                    Ui.alpha(Ui.CARD, Ui.GLASS ? 0x70 : 0xFF), Ui.LINE, 1, 12, this));
            int p = Ui.dp(this, 11);
            row.setPadding(p, p, p, p);
            CheckBox cb = new CheckBox(this);
            cb.setChecked(picked[i]);
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                    picked[idx] = v;
                }
            });
            row.addView(cb);
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            TextView n = new TextView(this);
            n.setText(t.title());
            n.setTextSize(13);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            n.setTextColor(t.onlineLooksUp() ? Ui.GREEN : Ui.TEXT);
            tx.addView(n);
            TextView sub = new TextView(this);
            sub.setText(t.sub());
            sub.setTextSize(11);
            sub.setTextColor(Ui.MUTED);
            sub.setLineSpacing(Ui.dp(this, 2), 1f);
            tx.addView(sub);
            row.addView(tx, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            list.addView(row, Ui.gap(this, 7));
            cbs[i] = cb;
        }
        box.addView(list, Ui.gap(this, 8));

        box.addView(Ui.row(this,
                Ui.button(this, "全选", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        for (int i = 0; i < all.size(); i++) {
                            picked[i] = true; cbs[i].setChecked(true);
                        }
                    }
                }),
                Ui.button(this, "清空", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        for (int i = 0; i < all.size(); i++) {
                            picked[i] = false; cbs[i].setChecked(false);
                        }
                    }
                }),
                Ui.button(this, "复制清单", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        copy(TunnelStore.asText(all));
                    }
                })));
        TextView foot = new TextView(this);
        foot.setText("列表仅缓存在本机 · 上次刷新 "
                + (TunnelStore.savedAt(this) > 0
                    ? new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                        .format(new java.util.Date(TunnelStore.savedAt(this)))
                    : "—"));
        foot.setTextSize(10);
        foot.setTextColor(Ui.MUTED);
        box.addView(foot, Ui.gap(this, 8));

        new GlassDialog.Builder(this)
                .setTitle("所有隧道 · 多选启动")
                .setSubtitle(all.size() + " 条 · 可一次启动多条")
                .setView(box)
                .setPositiveButton("启动所选", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        applyPicked(all, picked, true);
                    }
                })
                .setNeutralButton("仅拉取", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        applyPicked(all, picked, false);
                    }
                })
                .setNegativeButton("关闭", null).show();
        log("[隧道] 显示全部 " + all.size() + " 条，支持多选启动");
    }

    private void applyPicked(java.util.List<TunnelStore.T> all, boolean[] picked,
                             boolean autoStart) {
        String names = TunnelStore.namesParam(all, picked);
        if (names.length() == 0) { toast("请先勾选至少一条隧道"); return; }
        String node = TunnelStore.commonNode(all, picked);
        if (node.length() == 0) node = etNode.getText().toString().trim();
        if (node.length() == 0) { toast("请填写节点名称"); return; }
        etNode.setText(node);
        etTunnel.setText(names);
        // 第一条同步到状态卡（连接地址仍以配置为准）
        for (int i = 0; i < all.size() && i < picked.length; i++) {
            if (picked[i]) { saveTunnel(all.get(i).raw, false); break; }
        }
        savePrefs();
        pullConfigFor(node, names, autoStart);
        toast(names.contains(",") ? "已选多条隧道 · 正在拉取合并配置" : "已选 1 条隧道");
    }

    // =============================================== 登录方式

    /** 除 OAuth 外的登录方式：粘贴令牌 / 导入 / 多账号切换 / 退出登录。 */
    private void loginMethodsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        box.addView(Ui.osTip(this,
                "官方未公开账号密码登录端点，本 App 不猜接口、不索密码。"
                + "除 OAuth 授权外，可用粘贴令牌直登、从文本导入、多账号切换。", 2),
                Ui.gap(this, 10));

        final java.util.List<AccountStore.A> acc = AccountStore.list(this);
        box.addView(Ui.section(this, "登录方式"));
        box.addView(Ui.button(this, "① OAuth 授权登录（推荐）", 1,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { loginClick.onClick(null); }
                }), Ui.gap(this, 6));
        box.addView(Ui.button(this, "② 粘贴 Access Token 直登", 1,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { pasteTokenDialog(); }
                }), Ui.gap(this, 6));
        box.addView(Ui.button(this, "③ 从剪贴板 / 文本导入令牌", 2,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { importTokenDialog(); }
                }), Ui.gap(this, 12));

        box.addView(Ui.section(this, "本机账号档案（" + acc.size() + " 个 · 不上传）"));
        if (acc.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("暂无档案 · 登录后点「保存当前账号」即可留档切换");
            e.setTextSize(11);
            e.setTextColor(Ui.MUTED);
            box.addView(e, Ui.gap(this, 8));
        } else {
            for (int i = 0; i < acc.size(); i++) {
                final int idx = i;
                AccountStore.A a = acc.get(i);
                LinearLayout row = Ui.osRow(this, a.label,
                        a.shortToken() + (a.userName.length() > 0 ? " · " + a.userName : ""),
                        a.token.equals(sp.getString("token", "")) ? "当前" : "", true);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (AccountStore.switchTo(MainActivity.this, idx)) {
                            etToken.setText(sp.getString("token", ""));
                            updateLoginBadge();
                            toast("已切换到「" + acc.get(idx).label + "」");
                            log("[登录] 已切换本机账号档案");
                        }
                    }
                });
                box.addView(row, Ui.match());
            }
        }
        box.addView(Ui.row(this,
                Ui.button(this, "保存当前账号", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sp.getString("token", "").length() == 0) {
                            toast("当前没有令牌，先登录"); return;
                        }
                        int n = AccountStore.saveCurrent(MainActivity.this, "");
                        toast("已存为档案 #" + n + "（仅本机）");
                        loginMethodsDialog();
                    }
                }),
                Ui.button(this, "退出登录", 3, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        AccountStore.logout(MainActivity.this);
                        etToken.setText("");
                        savePrefs();
                        updateLoginBadge();
                        toast("已退出登录，档案仍保留可切回");
                        log("[登录] 已退出登录（本机档案保留）");
                    }
                })), Ui.gap(this, 8));

        new GlassDialog.Builder(this)
                .setTitle("登录方式")
                .setSubtitle("全部凭据只存本机")
                .setView(box)
                .setPositiveButton("完成", null).show();
    }

    private void pasteTokenDialog() {
        final EditText e = Ui.edit(this, "粘贴官方 OAuth 返回的 Access Token", 1, false, false);
        new GlassDialog.Builder(this)
                .setTitle("粘贴令牌直登")
                .setTip("令牌只写入本机，不会发到除官方 API 之外的任何地址", 2)
                .setView(e)
                .setPositiveButton("保存并使用", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String t = AccountStore.extractToken(e.getText().toString());
                        if (t.length() < 8) { toast("令牌格式看起来不对"); return; }
                        sp.edit().putString("token", t).putString("tokenVerify", "")
                                .putBoolean("refreshInvalid", false).commit();
                        etToken.setText(t);
                        savePrefs();
                        updateLoginBadge();
                        toast("已保存令牌（本机）");
                        log("[登录] 已通过粘贴令牌直登（本机保存）");
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void importTokenDialog() {
        final EditText e = Ui.edit(this,
                "粘贴含 token= 的配置、JSON 或直接粘贴令牌", 5, true, false);
        new GlassDialog.Builder(this)
                .setTitle("从文本导入令牌")
                .setMessage("支持三种：纯令牌 / INI 里的 token=xxx / JSON 的 access_token。"
                        + "解析全在本机完成，不联网。")
                .setView(e)
                .setPositiveButton("解析并保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String t = AccountStore.extractToken(e.getText().toString());
                        if (t.length() < 8) { toast("没找到有效令牌"); return; }
                        sp.edit().putString("token", t).putString("tokenVerify", "")
                                .putBoolean("refreshInvalid", false).commit();
                        etToken.setText(t);
                        savePrefs();
                        updateLoginBadge();
                        toast("已导入令牌（本机）");
                        log("[登录] 已从文本导入令牌");
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    // =============================================================== 其他

    private void open(Intent i, String tip) {
        try { startActivity(i); toast(tip); }
        catch (Exception e) { toast("无法打开设置，请手动前往系统设置"); }
    }

    private Intent appDetails() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
    }

    private void openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 26) {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            open(i, "请允许通知，前台服务才能常驻");
        } else open(appDetails(), "请在应用详情里允许通知");
    }

    /** v3.1.0：手动检查更新，连接本项目 GitHub Releases（只读取，不上传）。 */
    private void checkUpdate() {
        toast("正在连接 GitHub 检查更新…");
        UpdateChecker.checkAsync(new UpdateChecker.Callback() {
            @Override public void done(UpdateChecker.Info info) { showUpdateResult(info); }
        });
    }

    private void openGithub(String url) {
        if (!UpdateChecker.open(MainActivity.this, url)) toast("无法打开浏览器");
    }

    /** 结果弹窗：和「设置 / 关于」同一套液态玻璃卡片。 */
    private void showUpdateResult(final UpdateChecker.Info info) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        box.addView(Ui.osTitle(this, "检查更新", UpdateChecker.APP_VERSION), Ui.gap(this, 8));
        box.addView(Ui.osTip(this, info.status(), info.ok && info.newer ? 1 : 2), Ui.gap(this, 10));

        box.addView(Ui.section(this, "数据源"), Ui.gap(this, 8));
        box.addView(Ui.osRow(this, "GitHub 仓库", UpdateChecker.REPO, "", true), Ui.match());
        box.addView(Ui.osRow(this, "联网时机", "仅手动点「检查更新」时读取公开 Release 信息，不上传任何数据", "", true), Ui.match());
        box.addView(Ui.osRow(this, "本机版本", UpdateChecker.APP_VERSION, "", false), Ui.match());
        if (info.ok) {
            box.addView(Ui.section(this, "最新版本"), Ui.gap(this, 8));
            String title = info.tag + (info.name.length() > 0 ? " · " + info.name : "");
            box.addView(Ui.osRow(this, "Release", title, "", true), Ui.match());
            box.addView(Ui.osRow(this, "下载页", info.htmlUrl, "", false), Ui.match());
            if (info.body.length() > 0) {
                String brief = info.body.length() > 420 ? info.body.substring(0, 420) + "…" : info.body;
                box.addView(Ui.section(this, "更新说明"), Ui.gap(this, 8));
                box.addView(Ui.osTip(this, brief, 0), Ui.gap(this, 8));
            }
        }

        final String page = info.ok && info.htmlUrl.length() > 0
                ? info.htmlUrl : UpdateChecker.LATEST_URL;
        final String apk = info.apkUrl.length() > 0 ? info.apkUrl : UpdateChecker.RELEASES_URL;
        new GlassDialog.Builder(this)
                .setTitle(info.ok && info.newer ? "发现新版本" : "检查更新")
                .setSubtitle("连接本项目 GitHub Releases · 只读取版本信息")
                .setView(box)
                .setPositiveButton("打开 Releases / 下载页", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { openGithub(page); }
                })
                .setNeutralButton(info.ok && info.apkUrl.length() > 0 ? "直接下载 APK" : "项目主页",
                        new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        openGithub(info.ok && info.apkUrl.length() > 0 ? apk : UpdateChecker.REPO_URL);
                    }
                })
                .setNegativeButton("关闭", null).show();
    }

    /** 图二风格的项目 / 更新行，设置页与关于页共用。 */
    private void addProjectRows(LinearLayout box) {
        box.addView(Ui.section(this, "项目与更新"), Ui.gap(this, 8));
        View r1 = Ui.osRow(this, "检查更新", "连接本项目 GitHub Releases，手动检查新版本", "", true);
        r1.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { checkUpdate(); }
        });
        box.addView(r1, Ui.match());
        View r2 = Ui.osRow(this, "项目地址", UpdateChecker.REPO_URL, "", true);
        r2.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openGithub(UpdateChecker.REPO_URL); }
        });
        box.addView(r2, Ui.match());
        View r3 = Ui.osRow(this, "在 GitHub 点个 Star", UpdateChecker.STAR_URL, "", true);
        r3.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openGithub(UpdateChecker.STAR_URL); }
        });
        box.addView(r3, Ui.match());
        View r4 = Ui.osRow(this, "更新日志", "CHANGELOG.md", "", true);
        r4.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openGithub(UpdateChecker.CHANGELOG_URL); }
        });
        box.addView(r4, Ui.match());
        View r5 = Ui.osRow(this, "开源许可声明", "MIT · LICENSE", "", true);
        r5.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openGithub(UpdateChecker.LICENSE_URL); }
        });
        box.addView(r5, Ui.match());
        View r6 = Ui.osRow(this, "同账号下的另一个项目", UpdateChecker.OTHER_REPO, "", false);
        r6.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openGithub(UpdateChecker.OTHER_REPO_URL); }
        });
        box.addView(r6, Ui.match());
    }

    private void about() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));

        box.addView(Ui.osTitle(this, "使用帮助", "v3.1.0"), Ui.gap(this, 8));
        box.addView(Ui.osTip(this,
                "本 App 是第三方本地启动器：只负责在你手机本地运行 frpc 内核，"
                + "不提供任何服务器；唯一外部连接是官方 API 与官方节点。", 2), Ui.gap(this, 12));

        box.addView(Ui.section(this, "使用流程"));
        box.addView(Ui.osRow(this, "① 面板创建隧道", "节点 / 协议 / 本地 IP 与端口", "", true), Ui.match());
        box.addView(Ui.osRow(this, "② 登录", "OAuth 授权，或粘贴令牌直登 / 多账号切换", "", true), Ui.match());
        box.addView(Ui.osRow(this, "③ 选择隧道", "「所有隧道」可多选，一次启动多条", "", true), Ui.match());
        box.addView(Ui.osRow(this, "④ 拉取配置并启动", "到日志页看「映射启动成功」", "", false), Ui.match());

        box.addView(Ui.section(this, "v3.1.0 新增（本版）"));
        box.addView(Ui.osTip(this,
                "· 新增「检查更新」：连接本项目 GitHub Releases，手动点击才联网，只读取版本信息\n"
                + "· 设置页「版本更新」卡片：检查更新 / 项目地址 / GitHub Star / 更新日志\n"
                + "· 关于页新增项目与更新行：检查更新、项目地址、Star、更新日志、开源许可、另一项目入口\n"
                + "· 发现新版本弹窗显示 Release 标题、更新说明与下载入口，不自动下载、不静默安装\n"
                + "· 免责声明与域名清单如实补充 GitHub 更新检查（不点不联网）", 0), Ui.gap(this, 10));

        box.addView(Ui.section(this, "v3.0.1 修复"));
        box.addView(Ui.osTip(this,
                "· 修复「装了 QQ 只跳到 QQ 主界面」：改用 QQ 官方落地页的 Android 通道 "
                + "mqqopensdkapi://bizAgent/qm/qr 直达加好友页\n"
                + "· 修复 v3.0 的 queryIntentActivities 假阴性：不再「先查再跳」，"
                + "直接拉起并靠异常判成败，OriginOS 上不再误判成无通道\n"
                + "· 内置短链 302 解析出的官方落地页（带 k），点击零联网直达；可手动刷新\n"
                + "· 解析出落地页 rawuin，补一条 mqqapi 资料卡备用通道\n"
                + "· 不再自动复制链接：只有点「复制链接」才写剪贴板，3 秒内重复点不重复写\n"
                + "· 未装 QQ 时明确提示手动复制，不再静默覆盖剪贴板", 0), Ui.gap(this, 10));

        box.addView(Ui.section(this, "v3.0 新增"));
        box.addView(Ui.osTip(this,
                "· 修复「装了 QQ 却跳浏览器」：声明包可见性 + 强制把短链交给 QQ，"
                + "装了 QQ 时绝不悄悄落浏览器\n"
                + "· 联系作者弹窗显示本机检测结果，可填 QQ 号走官方 mqqwpa 通道，"
                + "「用浏览器打开」独立成按钮\n"
                + "· 修复守护竞态：旧代次不再清掉新进程，消除网络恢复后反复 code=143 自杀循环\n"
                + "· 停止导致的读取中断统一记为正常收尾，不再报红色 [异常]\n"
                + "· 令牌校验结果收敛：状态不变或 5 分钟内不重复刷日志\n"
                + "· 控制台首屏收紧：签到中心默认折叠，标题不再折成两行\n"
                + "· 玻璃弹窗遮罩与内容底加深，底层文字不再透出重叠\n"
                + "· 新增「一键自检」：内核 / ABI / 配置 / 令牌 / 守护 / QQ 跳转一次汇总\n"
                + "· 运行诊断附带 QQ 跳转检测结论", 0), Ui.gap(this, 10));

        box.addView(Ui.section(this, "v2.9 新增"));
        box.addView(Ui.osTip(this,
                "· 空列表弹窗自动收缩，不再占满整屏留一大片空白\n"
                + "· 隧道列表兼容更多官方返回结构，解析失败显示原始响应诊断 + 手动导入\n"
                + "· 设备信息全部系统实时读取，架构说明不再写死机型与「64 位 ARM」\n"
                + "· 标题右侧「联系作者」+ 下方 QQ 链接，一键跳转加好友\n"
                + "· 弹窗按钮可两行不再截断、浅色玻璃提示条加深对比度\n"
                + "· 运行中不再误弹过期长提示，按钮文字不再折行\n"
                + "· 新增脱敏运行诊断与清空隧道缓存；Toast 移到顶部不再压住底部 Tab", 0),
                Ui.gap(this, 10));

        box.addView(Ui.section(this, "v2.8 新增"));
        box.addView(Ui.osTip(this,
                "· 一次启动多条隧道 + 显示所有隧道（官方 tunnel_names 合并配置）\n"
                + "· 其他登录方式：粘贴令牌直登 / 文本导入 / 多账号本机档案 / 退出登录\n"
                + "· 免责声明：只负责本地运行，仅连接官方 API 与官方节点\n"
                + "· 通知栏修好对比度与行数截断，新增密度、事件通知、复制全部、看日志\n"
                + "· 所有弹窗固定高度可下滑，21 套主题统一动态液态半透明玻璃\n"
                + "· 隐藏后台：最近任务隐藏 / 通知最小化 / 锁屏隐藏 / 域名打码\n"
                + "· 日志分享默认脱敏、可搜索，玻璃强度三档", 0), Ui.gap(this, 10));

        box.addView(Ui.section(this, "历史版本"));
        box.addView(Ui.osTip(this,
                "v2.7 OriginOS 玻璃主题 / 通知栏启停重启 / 架构四层说明\n"
                + "v2.6 22 处液态玻璃弹窗 / 4 套玻璃动态 / 权限与签到优化\n"
                + "v2.5 签到中心 / 设备适配度 / 一键权限 / 20 套主题\n"
                + "v2.4 通知栏 15 字段多选 / 折叠行四口径\n"
                + "v2.3 协议与地址解析修正 / 10 套主题 / 连接诊断\n"
                + "v2.1 不再传 --strict-config（真机内核不认这个 flag）", 2), Ui.gap(this, 10));

        addProjectRows(box);

        box.addView(Ui.section(this, "安全与声明"));
        box.addView(Ui.osRow(this, "本地数据",
                "令牌 / 配置 / 日志 / 隧道列表 / 签到记录只存本机，卸载即清除", "", true), Ui.match());
        box.addView(Ui.osRow(this, "外部连接",
                "仅官方 v2 API、官方 OAuth、官方 frp 节点；无广告 / 统计 / 推送 SDK", "", true), Ui.match());
        box.addView(Ui.osRow(this, "公网风险",
                "隧道等于把本机端口开放到公网，只映射必要服务并设强密码", "", false), Ui.match());
        TextView ver = new TextView(this);
        ver.setText("\n版本 3.1.0 · 包名 net.chmlfrp.mobile\n"
                + "内核来自 ChmlFrp 官方客户端与 fatedier/frp（Apache-2.0）");
        ver.setTextSize(10);
        ver.setTextColor(Ui.MUTED);
        box.addView(ver);

        new GlassDialog.Builder(this)
                .setTitle("使用帮助 / 关于")
                .setSubtitle("分段卡片 · 可下滑查看全部内容")
                .setView(box)
                .setPositiveButton("知道了", null)
                .setNeutralButton("免责声明", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        fullDisclaimer();
                    }
                })
                .setNegativeButton("域名清单", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        hostsDialog();
                    }
                }).show();
    }

    /** 本版本实际访问的域名，便于核对「只连官方」。 */
    private void hostsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        box.addView(Ui.osTip(this,
                "除下列官方地址与你自己配置里写的节点外，本 App 不向任何服务器发数据。"
                + "可在系统「应用联网管理」里核对。", 2), Ui.gap(this, 10));
        for (String h : Disclaimer.hosts()) {
            box.addView(Ui.osRow(this, h, "", "", true), Ui.match());
        }
        TextView t = new TextView(this);
        t.setText("说明：frp 节点域名来自你拉取的配置，本启动器不内置、不指定任何节点。"
                + "签到走官方管理面板的人机验证码，App 不绕过。");
        t.setTextSize(11);
        t.setTextColor(Ui.MUTED);
        t.setLineSpacing(Ui.dp(this, 3), 1f);
        box.addView(t, Ui.gap(this, 8));
        new GlassDialog.Builder(this)
                .setTitle("网络与隐私 · 域名清单")
                .setView(box)
                .setPositiveButton("知道了", null)
                .setNeutralButton("完整声明", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { fullDisclaimer(); }
                }).show();
    }

    private void copy(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("ChmlFrp", s));
            toast("已复制");
        } catch (Exception e) { toast("复制失败：" + e.getMessage()); }
    }

    private String readLog() {
        File f = new File(getFilesDir(), "runtime.log");
        if (!f.exists()) return "";
        try {
            FileInputStream is = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) { return ""; }
    }

    private void bg(final Job job) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String r = job.work();
                    runOnUiThread(new Runnable() {
                        @Override public void run() { job.done(r); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            String msg = String.valueOf(e);
                            if (msg.contains("无效的登录状态") || msg.contains("invalid_grant")) {
                                logOnce("[登录] 面板接口返回「无效登录状态」：本机访问令牌仍可用于启动隧道，"
                                        + "但拉取配置 / 隧道列表需要重新 OAuth 登录");
                                sp.edit().putString("tokenVerify", "stale").commit();
                                updateLoginBadge();
                                // 图22：运行中还弹长句「登录态已过期…」既误导又盖住底部导航
                                toast("令牌过期 · 隧道不受影响");
                            } else {
                                logOnce("[错误] " + e);
                                toast("失败：" + e.getMessage());
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void savePrefs() {
        sp.edit()
                .putString("token", etToken.getText().toString())
                .putString("node", etNode.getText().toString())
                .putString("tunnel", etTunnel.getText().toString())
                .putString("config", etConfig.getText().toString())
                .putString("curConfig", curConfig)
                .putBoolean("autostart", cbAuto.isChecked())
                .putBoolean("watchdog", cbWatchdog.isChecked())
                .putBoolean("follow", cbFollow.isChecked())
                .putBoolean("onlyErrors", cbOnlyErrors.isChecked())
                .putBoolean("notifySpeed", cbNotifySpeed.isChecked())
                .putBoolean("notifyIdle", cbNotifyIdle != null && cbNotifyIdle.isChecked())
                .putBoolean("foldRepeat", cbFoldRepeat == null || cbFoldRepeat.isChecked())
                .putBoolean("notifyFull", cbNotifyFull.isChecked())
                .putBoolean("notifyThemeColor", cbNotifyTheme.isChecked())
                .putBoolean("signRemind", cbSignRemind.isChecked())
                .putBoolean("autoRefresh", cbAutoRefresh.isChecked())
                .putBoolean("binaryUnits", cbBinaryUnits.isChecked())
                .putBoolean("glassMotion", cbGlassMotion.isChecked())
                .putBoolean("notifyEvents", cbNotifyEvents != null && cbNotifyEvents.isChecked())
                .putBoolean("logRedact", cbLogRedact == null || cbLogRedact.isChecked())
                .putInt("page", page)
                .commit();
    }

    private void loadPrefs() {
        curConfig = sp.getString("curConfig", Config.DEFAULT);
        etToken.setText(sp.getString("token", ""));
        etNode.setText(sp.getString("node", ""));
        etTunnel.setText(sp.getString("tunnel", ""));
        cbAuto.setChecked(sp.getBoolean("autostart", false));
        cbWatchdog.setChecked(sp.getBoolean("watchdog", true));
        cbFollow.setChecked(sp.getBoolean("follow", true));
        cbOnlyErrors.setChecked(sp.getBoolean("onlyErrors", false));
        cbNotifySpeed.setChecked(sp.getBoolean("notifySpeed", true));
        if (cbNotifyIdle != null) cbNotifyIdle.setChecked(sp.getBoolean("notifyIdle", true));
        if (cbFoldRepeat != null) cbFoldRepeat.setChecked(sp.getBoolean("foldRepeat", true));
        cbNotifyFull.setChecked(sp.getBoolean("notifyFull", true));
        cbNotifyTheme.setChecked(sp.getBoolean("notifyThemeColor", true));
        cbSignRemind.setChecked(sp.getBoolean("signRemind", true));
        cbAutoRefresh.setChecked(sp.getBoolean("autoRefresh", false));
        cbBinaryUnits.setChecked(sp.getBoolean("binaryUnits", true));
        // 先赋值再挂监听，避免初始 setChecked 触发 recreate 死循环
        cbGlassMotion.setChecked(sp.getBoolean("glassMotion", true));
        if (cbNotifyEvents != null) cbNotifyEvents.setChecked(sp.getBoolean("notifyEvents", true));
        if (cbLogRedact != null) cbLogRedact.setChecked(sp.getBoolean("logRedact", true));
        syncPrivacyChecks();
        cbGlassMotion.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(
                            android.widget.CompoundButton button, boolean isChecked) {
                        sp.edit().putBoolean("glassMotion", isChecked).commit();
                        refreshNotify();
                        recreate();   // 重建玻璃背景 View，动画开关才生效
                    }
                });
        String saved = sp.getString("config", "");
        String disk = Config.read(this, curConfig);
        etConfig.setText(saved.length() > 0 ? saved : disk);
        String link = sp.getString("tunnelLink", "").trim();
        if (link.length() > 0) {
            tvLink.setText("连接地址：" + Config.displayHost(link)
                    + "（长按复制，以面板为准）");
        }
    }

    private void log(String s) {
        FrpService.append(this, s);
        refreshLog();
    }

    /** 同类错误 60 秒内只记一次，避免「无效的登录状态」连刷 4 行。 */
    private void logOnce(String s) {
        long now = System.currentTimeMillis();
        if (s.equals(lastErrText) && now - lastErrAt < 60000L) {
            errMerged++;
            return;
        }
        if (errMerged > 0) {
            FrpService.append(this, "[日志] 上一条同类错误又出现 " + errMerged
                    + " 次，已合并（不重复刷屏）");
            errMerged = 0;
        }
        lastErrText = s;
        lastErrAt = now;
        log(s);
    }

    /** 把连续重复的行折叠成「… ×N」。 */
    private String foldRepeats(String raw) {
        if (cbFoldRepeat != null && !cbFoldRepeat.isChecked()) return raw;
        String[] ls = raw.split("\n", -1);
        StringBuilder out = new StringBuilder();
        String prev = null;
        int cnt = 0;
        for (String ln : ls) {
            if (ln.equals(prev)) { cnt++; continue; }
            if (prev != null) {
                out.append(prev);
                if (cnt > 0) out.append("   ×").append(cnt + 1);
                out.append('\n');
            }
            prev = ln;
            cnt = 0;
        }
        if (prev != null) {
            out.append(prev);
            if (cnt > 0) out.append("   ×").append(cnt + 1);
        }
        return out.toString();
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refreshLog();
            tickCount++;
            if (cbAutoRefresh != null && cbAutoRefresh.isChecked()
                    && tickCount % 30 == 0 && page == 0) {
                refreshTunnelStatus(true);
            }
            h.postDelayed(this, 1000);
        }
    };

    private boolean errorLine(String s) {
        if (s.contains("read interrupted") || s.contains("非故障")) return false;
        return s.contains("[异常]") || s.contains("[错误]") || s.contains("[W]")
                || s.contains("error") || s.contains("Error") || s.contains("failed")
                || s.contains("unknown flag") || s.contains("Permission denied")
                || s.contains("不兼容") || s.contains("⚠");
    }

    private int lineColor(String s) {
        if (errorLine(s)) return 0xFFFF8A80;
        if (s.contains("[内核]") || s.contains("[守护]") || s.contains("[自检]")
                || s.contains("[连通]") || s.contains("[预检]")) return 0xFFFFD54F;
        if (s.contains("[启动器]") || s.contains("[配置]") || s.contains("[转换]")) return 0xFF81D4FA;
        if (s.contains("[日志]") || s.contains("[架构]")) return 0xFFB0BEC5;
        if (s.contains("[提示]") || s.contains("[隧道]") || s.contains("[登录]")) return 0xFFA5D6A7;
        return Ui.LOG_FG;
    }

    private void appendColored(String chunk) {
        String[] lines = chunk.split("\n", -1);
        for (String ln : lines) {
            if (ln.length() == 0) continue;
            if (cbOnlyErrors.isChecked() && !errorLine(ln)) continue;
            if (logFilter.length() > 0
                    && !ln.toLowerCase().contains(logFilter.toLowerCase())) continue;
            SpannableString ss = new SpannableString(ln + "\n");
            ss.setSpan(new ForegroundColorSpan(lineColor(ln)), 0, ss.length(), 0);
            tvLog.append(ss);
        }
    }

    private void rebuildLog() {
        tvLog.setText("");
        logPos = 0;
        appendColored(foldRepeats(readLog()));
        File f = new File(getFilesDir(), "runtime.log");
        logPos = f.exists() ? f.length() : 0;
    }

    private void refreshLog() {
        File f = new File(getFilesDir(), "runtime.log");
        if (f.exists()) {
            long len = f.length();
            if (len < logPos) { logPos = 0; tvLog.setText(""); }
            if (len > logPos) {
                try {
                    RandomAccessFile raf = new RandomAccessFile(f, "r");
                    raf.seek(logPos);
                    byte[] buf = new byte[(int) (len - logPos)];
                    raf.readFully(buf);
                    raf.close();
                    appendColored(new String(buf, "UTF-8"));
                    if (tvLog.length() > 60000) {
                        String s = tvLog.getText().toString();
                        tvLog.setText(s.substring(s.length() - 50000));
                    }
                    logPos = len;
                    if (cbFollow.isChecked()) {
                        svLog.post(new Runnable() {
                            @Override public void run() { svLog.fullScroll(View.FOCUS_DOWN); }
                        });
                    }
                } catch (Exception ignored) {}
            }
        }
        boolean run = FrpService.running;
        String st = FrpService.stateLabel();
        tvPill.setText(st);
        tvPill.setBackground(Ui.round(run ? 0xFF66BB6A
                : FrpService.waiting ? 0xFFFFB74D : 0x33FFFFFF, 16, this));
        tvGuard.setText(cbWatchdog.isChecked()
                ? "守护：开启 · 上限 " + sp.getInt("maxRestart", 5)
                  + " 次 · 参数错误不重连"
                : "守护：关闭 · 掉线后不会自动重连");
        tvGuard.setTextColor(cbWatchdog.isChecked() ? Ui.GREEN : Ui.MUTED);
        btnStop.setEnabled(run || FrpService.waiting);
        btnStart.setText(run ? "●  运行中" : "▶  启动隧道");
        refreshTraffic();
    }


    // ============================================ 会话保持（登录一次，下次免登录）

    /** 启动时不弹浏览器：令牌过期就静默续期，否则轻量校验并更新登录徽章。 */
    private void silentRestoreSession() {
        final String token = sp.getString("token", "").trim();
        if (token.length() == 0) return;
        final long exp = sp.getLong("tokenExpires", 0);
        final String refresh = sp.getString("refreshToken", "").trim();
        final boolean refreshDead = sp.getBoolean("refreshInvalid", false);
        if (exp > 0 && System.currentTimeMillis() > exp - 60000L
                && refresh.length() > 0 && !refreshDead) {
            log("[登录] 令牌即将过期，尝试静默续期…");
            bg(new Job() {
                @Override public String work() throws Exception {
                    return Net.refreshAccess(refresh);
                }
                @Override public void done(String r) {
                    try {
                        JSONObject o = new JSONObject(r);
                        String at = o.optString("access_token", "");
                        if (at.length() == 0) {
                            sp.edit().putBoolean("refreshInvalid", true)
                                    .putString("tokenVerify", "stale").commit();
                            logOnce("[登录] 续期令牌已失效（invalid_grant）：本机访问令牌仍可启动隧道，"
                                    + "但拉取配置可能需要重新 OAuth 登录");
                            updateLoginBadge();
                            return;
                        }
                        long in = o.optLong("expires_in", 0);
                        sp.edit().putString("token", at)
                                .putLong("tokenExpires", in > 0
                                        ? System.currentTimeMillis() + in * 1000L : 0)
                                .putString("refreshToken",
                                        o.optString("refresh_token", refresh))
                                .putBoolean("refreshInvalid", false)
                                .putString("tokenVerify", "ok")
                                .putLong("tokenVerifyAt", System.currentTimeMillis())
                                .commit();
                        etToken.setText(at);
                        savePrefs();
                        updateLoginBadge();
                        log("[登录] 已静默续期，无需重新授权");
                        toast("已自动保持登录");
                    } catch (Exception e) {
                        sp.edit().putBoolean("refreshInvalid", true)
                                .putString("tokenVerify", "stale").commit();
                        logOnce("[登录] 续期响应无法解析（invalid_grant）：本机令牌仍可用，"
                                + "需要时点 OAuth 登录重新授权");
                        updateLoginBadge();
                    }
                }
            });
        } else {
            if (refreshDead) {
                logOnce("[登录] 上次续期失败，已跳过重试；本机令牌仍可用于启动隧道");
                sp.edit().putString("tokenVerify", "stale").commit();
                updateLoginBadge();
                return;
            }
            bg(new Job() {
                @Override public String work() throws Exception {
                    try { return Sign.userInfo(token).ok ? "OK" : "FAIL"; }
                    catch (Exception e) { return "ERR"; }
                }
                @Override public void done(String r) {
                    boolean ok = "OK".equals(r);
                    sp.edit().putString("tokenVerify", ok ? "ok" : "stale")
                            .putLong("tokenVerifyAt", System.currentTimeMillis()).commit();
                    // v3.0：校验结果没变化、或距上次不足 5 分钟，就不再写一条，
                    // 避免每次打开 App 都「一会儿有效一会儿失效」地刷屏。
                    String cur = ok ? "ok" : "stale";
                    String prev = sp.getString("lastVerify", "");
                    long prevAt = sp.getLong("lastVerifyAt", 0);
                    long now = System.currentTimeMillis();
                    if (!cur.equals(prev) || now - prevAt > 300000L) {
                        if (ok) log("[登录] 本机令牌校验通过（面板接口可用）");
                        else log("[登录] 面板接口校验未通过；本机令牌仍可启动隧道，"
                                + "若拉取配置失败再点 OAuth 登录");
                        sp.edit().putString("lastVerify", cur)
                                .putLong("lastVerifyAt", now).commit();
                    }
                    updateLoginBadge();
                }
            });
        }
    }

    private void updateLoginBadge() {
        if (tvLoginState == null) return;
        String token = sp.getString("token", "").trim();
        String refresh = sp.getString("refreshToken", "").trim();
        String verify = sp.getString("tokenVerify", "");
        String text;
        int color;
        if (token.length() == 0) { text = "○ 未登录"; color = Ui.MUTED; }
        else if ("stale".equals(verify) || sp.getBoolean("refreshInvalid", false)) {
            text = "⚠ 本机令牌可用 · 续期失效，建议重新授权 ›";
            color = Ui.WARN;
        } else if ("ok".equals(verify)) {
            text = "✓ 已登录 · 面板校验通过 ›"; color = Ui.GREEN;
        } else if (refresh.length() > 0) {
            text = "✓ 已保持登录 · 自动续期 ›"; color = Ui.GREEN;
        } else { text = "✓ 本机已登录 ›"; color = Ui.GREEN; }
        tvLoginState.setText(text);
        tvLoginState.setTextColor(color);
        tvLoginState.setBackground(Ui.roundStroke(0x00000000, color, 1, 8, this));
        tvLoginState.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tokenStateDialog(); }
        });
    }

    /** 令牌状态弹窗：把「访问令牌 / 续期令牌 / 面板校验」三件事分开说清。 */
    private void tokenStateDialog() {
        String token = sp.getString("token", "").trim();
        String refresh = sp.getString("refreshToken", "").trim();
        String verify = sp.getString("tokenVerify", "");
        long at = sp.getLong("tokenVerifyAt", 0);
        long exp = sp.getLong("tokenExpires", 0);
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                "MM-dd HH:mm", java.util.Locale.CHINA);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        box.addView(Ui.osRow(this, "本机访问令牌",
                token.length() > 0 ? token.substring(0, Math.min(10, token.length()))
                        + "…（已存本机，不上传）" : "未保存",
                token.length() > 0 ? "已保存" : "无", true), Ui.match());
        box.addView(Ui.osRow(this, "续期令牌（refresh）",
                sp.getBoolean("refreshInvalid", false) ? "已失效：invalid_grant，不再反复重试"
                        : refresh.length() > 0 ? "可用于静默续期" : "未保存",
                sp.getBoolean("refreshInvalid", false) ? "失效" : "正常", true), Ui.match());
        box.addView(Ui.osRow(this, "面板接口校验",
                at > 0 ? "上次校验 " + f.format(new java.util.Date(at)) : "尚未校验",
                "ok".equals(verify) ? "通过" : "stale".equals(verify) ? "未通过" : "未知", true),
                Ui.match());
        box.addView(Ui.osRow(this, "访问令牌到期",
                exp > 0 ? f.format(new java.util.Date(exp)) : "未知（接口未返回 expires_in）",
                "", false), Ui.match());
        TextView note = Ui.osTip(this,
                "「访问令牌仍可用」与「面板返回无效登录状态」不矛盾：启动隧道走本地配置 + 服务端认证，"
                + "而拉取配置 / 隧道列表走 v2 API，令牌过期时后者先失败。"
                + "重新 OAuth 登录一次即可全部恢复。", 2);
        box.addView(note, Ui.gap(this, 10));
        new GlassDialog.Builder(this)
                .setTitle("🔐 令牌状态")
                .setTip("四行分别说清：本机令牌、续期令牌、面板校验、到期时间", 0)
                .setView(box)
                .setPositiveButton("重新 OAuth 登录", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sp.edit().putBoolean("refreshInvalid", false)
                                .putString("tokenVerify", "").commit();
                        loginClick.onClick(null);
                    }
                })
                .setNeutralButton("复制状态", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        copy("token=" + (token.length() > 0 ? "已保存" : "无")
                                + "\nrefreshInvalid=" + sp.getBoolean("refreshInvalid", false)
                                + "\ntokenVerify=" + verify);
                    }
                })
                .setNegativeButton("关闭", null).show();
    }

    /** 通知栏/快捷区的「重启隧道」：让服务先收旧进程再起新代次。 */
    private void restartTunnel() {
        Intent i = new Intent(this, FrpService.class)
                .setAction(FrpService.ACTION_START)
                .putExtra(FrpService.EXTRA_MODE, "config")
                .putExtra(FrpService.EXTRA_FORCE_RESTART, true);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            log("[启动器] 已请求重启隧道");
            toast("正在重启隧道…");
        } catch (Exception e) {
            toast("重启失败：" + e.getMessage());
        }
    }

    /**
     * 架构分层说明：把「ROM 架构」「全新架构」和 CPU / 内核四件事分开讲清。
     * 四层卡片，每层给出本机真实值 + 为什么重要。
     */
    private void architectureDialog() {
        final DeviceInfo.Info i = DeviceInfo.collect(this);
        Kernel.Info k = Kernel.cached();
        String ver = "未知".equals(k.version) ? "启动隧道后自动识别" : k.version;
        final String[][] layers = RomInfo.layers(this, i.abis, i.primaryAbi,
                ver, k.chmlfrp, i.brand + " " + i.model, i.sdkInt);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 4);
        box.setPadding(pad, pad, pad, pad);

        // v2.9：设备信息卡，全部由 Build.* / 系统服务实时读取，不写死任何机型
        LinearLayout dev = Ui.card(this);
        TextView dh = new TextView(this);
        dh.setText("📱 本机设备（系统实时读取）");
        dh.setTextSize(14);
        dh.setTypeface(Typeface.DEFAULT_BOLD);
        dh.setTextColor(Ui.BLUE);
        dh.setPadding(0, 0, 0, Ui.dp(this, 6));
        dev.addView(dh);
        String[][] pp = i.pairs();
        int show = Math.min(8, pp.length);
        for (int q = 0; q < show; q++) {
            dev.addView(Ui.osRow(this, pp[q][0], pp[q][1], "", q < show - 1), Ui.match());
        }
        TextView auto = new TextView(this);
        auto.setText("以上字段来自 Build.BRAND / MODEL / VERSION / SUPPORTED_ABIS 与系统服务，"
                + "换任何手机都会自动显示那台设备的值；不申请权限、不读隐私、不上传。");
        auto.setTextSize(10);
        auto.setTextColor(Ui.MUTED);
        auto.setLineSpacing(Ui.dp(this, 2), 1f);
        auto.setPadding(0, Ui.dp(this, 8), 0, 0);
        dev.addView(auto);
        box.addView(dev, Ui.gap(this, 12));

        box.addView(Ui.osTip(this,
                "这四个「架构」不是一回事：CPU 决定装哪个包，内核决定 frpc 怎么跑，"
                + "ROM 决定后台能不能活，「全新架构」说的是本启动器自己的实现方案。", 0),
                Ui.gap(this, 12));

        for (int n = 0; n < layers.length; n++) {
            LinearLayout g = Ui.card(this);
            TextView h = new TextView(this);
            h.setText(layers[n][0]);
            h.setTextSize(15);
            h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setTextColor(Ui.BLUE);
            g.addView(h);
            TextView sub = new TextView(this);
            sub.setText(layers[n][1] + "  ·  " + layers[n][2]);
            sub.setTextSize(11);
            sub.setTextColor(Ui.MUTED);
            sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
            g.addView(sub);
            View div = new View(this);
            div.setBackgroundColor(Ui.alpha(Ui.LINE, 0xC0));
            g.addView(div, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1))));
            TextView d = new TextView(this);
            d.setText(layers[n][3]);
            d.setTextSize(12);
            d.setTextColor(Ui.TEXT);
            d.setLineSpacing(Ui.dp(this, 4), 1f);
            d.setPadding(0, Ui.dp(this, 10), 0, 0);
            g.addView(d);
            box.addView(g, Ui.gap(this, 10));
        }
        TextView privacy = new TextView(this);
        privacy.setText("ROM 识别只读 Build.BRAND / MANUFACTURER / DISPLAY 与公开系统属性，"
                + "不申请权限、不读隐私、不上传。");
        privacy.setTextSize(10);
        privacy.setTextColor(Ui.MUTED);
        box.addView(privacy);

        new GlassDialog.Builder(this)
                .setTitle("🧩 架构分层说明")
                .setTip("自动读取本机：" + i.brand + " " + i.model
                        + " · Android " + i.android + " · " + i.primaryAbi, 2)
                .setView(box)
                .setPositiveButton("一键权限", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        oneKeyPermissions();
                    }
                })
                .setNeutralButton("复制全部", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        StringBuilder sb = new StringBuilder("ChmlFrp 架构分层说明\n");
                        sb.append("\n【本机设备 · 系统实时读取】\n");
                        for (String[] p : i.pairs())
                            sb.append(p[0]).append("  ").append(p[1]).append('\n');
                        for (String[] l : layers) {
                            sb.append("\n").append(l[0]).append("（").append(l[1]).append("）\n")
                              .append("本机：").append(l[2]).append("\n")
                              .append(l[3]).append("\n");
                        }
                        copy(sb.toString());
                    }
                })
                .setNegativeButton("关闭", null).show();
        log("[架构] 已查看四层架构说明（设备=" + i.brand + " " + i.model
                + " · ROM=" + RomInfo.detect(this).name + "）");
    }

    private void oneKeyPermissions() {
        final java.util.List<Perm.Item> items = Perm.check(this);
        int ok = 0;
        for (Perm.Item it : items) if (it.ok) ok++;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 4);
        box.setPadding(pad, pad, pad, pad);

        // 概览条
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackground(Ui.round(Ui.alpha(Ui.BLUE, Ui.DARK ? 0x30 : 0x18), 14, this));
        int hp = Ui.dp(this, 12);
        head.setPadding(hp, hp, hp, hp);
        TextView h1 = new TextView(this);
        h1.setText("权限体检  " + ok + " / " + items.size() + " 项已就绪");
        h1.setTextSize(15);
        h1.setTypeface(Typeface.DEFAULT_BOLD);
        h1.setTextColor(Ui.BLUE);
        head.addView(h1);
        TextView h2 = new TextView(this);
        h2.setText(Perm.summary(items));
        h2.setTextSize(11);
        h2.setTextColor(Ui.MUTED);
        head.addView(h2, Ui.gap(this, 2));
        // 进度条
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(Ui.round(Ui.alpha(Ui.MUTED, 0x40), 4, this));
        View fill = new View(this);
        fill.setBackground(Ui.round(Ui.BLUE, 4, this));
        bar.addView(fill, new LinearLayout.LayoutParams(0,
                Ui.dp(this, 7), Math.max(1, ok)));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0,
                Ui.dp(this, 7), Math.max(1, items.size() - ok)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 7));
        blp.setMargins(0, Ui.dp(this, 9), 0, 0);
        head.addView(bar, blp);
        box.addView(head, Ui.gap(this, 10));

        // 逐项卡片
        for (int i = 0; i < items.size(); i++) {
            final Perm.Item it = items.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.roundStroke(
                    Ui.alpha(Ui.CARD, Ui.GLASS ? 0x70 : 0xFF), Ui.LINE, 1, 12, this));
            int rp = Ui.dp(this, 11);
            row.setPadding(rp, rp, rp, rp);

            TextView ic = new TextView(this);
            ic.setText(it.icon);
            ic.setTextSize(16);
            ic.setTypeface(Typeface.DEFAULT_BOLD);
            ic.setTextColor(it.level == 0 ? Ui.GREEN
                    : it.level == 2 ? Ui.RED : Ui.WARN);
            ic.setBackground(Ui.round(Ui.alpha(it.level == 0 ? Ui.GREEN
                    : it.level == 2 ? Ui.RED : Ui.WARN, 0x24), 9, this));
            ic.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 30), Ui.dp(this, 30));
            ilp.setMargins(0, 0, Ui.dp(this, 10), 0);
            row.addView(ic, ilp);

            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            TextView n = new TextView(this);
            n.setText(it.name);
            n.setTextSize(13);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            n.setTextColor(Ui.TEXT);
            tx.addView(n);
            TextView st = new TextView(this);
            st.setText(it.state);
            st.setTextSize(11);
            st.setTextColor(it.level == 0 ? Ui.GREEN : Ui.TEXT);
            tx.addView(st);
            TextView how = new TextView(this);
            how.setText(it.how + "\n" + it.tip);
            how.setTextSize(10);
            how.setTextColor(Ui.MUTED);
            how.setLineSpacing(Ui.dp(this, 2), 1f);
            tx.addView(how);
            row.addView(tx, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (!it.ok && it.action != null) {
                TextView go = new TextView(this);
                go.setText("去处理 ›");
                go.setTextSize(11);
                go.setTypeface(Typeface.DEFAULT_BOLD);
                go.setTextColor(Ui.BLUE);
                go.setBackground(Ui.roundStroke(0x00000000, Ui.BLUE, 1, 9, this));
                int gp = Ui.dp(this, 8);
                go.setPadding(gp, Ui.dp(this, 5), gp, Ui.dp(this, 5));
                go.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { it.action.run(); }
                        catch (Exception e) { toast("跳转失败：" + e.getMessage()); }
                    }
                });
                LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                glp.setMargins(Ui.dp(this, 8), 0, 0, 0);
                row.addView(go, glp);
            }
            box.addView(row, Ui.gap(this, 7));
        }

        TextView note = new TextView(this);
        note.setText("只有运行时通知权限能由 App 直接弹框申请；电池优化现在也能直接弹系统确认框。"
                + "自启动与通知渠道属于系统设置，Android 不允许第三方直接授予，只能按品牌跳转。"
                + "\n本 App 不申请定位、通讯录、电话、存储、相机等隐私权限。");
        note.setTextSize(10);
        note.setTextColor(Ui.MUTED);
        note.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), 0);
        box.addView(note);

        new GlassDialog.Builder(this)
                .setTitle("🔑 一键获取权限")
                .setView(box)
                .setPositiveButton("一键申请并引导", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String[] r = Perm.autoGrant(MainActivity.this, items).split("\\|");
                        int auto = Integer.parseInt(r[0]), manual = Integer.parseInt(r[1]);
                        toast("已自动申请 " + auto + " 项 · 还有 " + manual + " 项需手动");
                        log("[权限] 自动申请 " + auto + " 项，需手动 " + manual + " 项");
                    }
                })
                .setNegativeButton("关闭", null).show();
    }


    // ============================================ 设备信息与适配度

    private View deviceCard() {
        LinearLayout c = Ui.card(this);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tvFitSummary = new TextView(this);
        tvFitSummary.setText("适配度 — / 100");
        tvFitSummary.setTextSize(19);
        tvFitSummary.setTypeface(Typeface.DEFAULT_BOLD);
        tvFitSummary.setTextColor(Ui.BLUE);
        top.addView(tvFitSummary, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView detail = new TextView(this);
        detail.setText("详情 ›");
        detail.setTextSize(12);
        detail.setTypeface(Typeface.DEFAULT_BOLD);
        detail.setTextColor(Ui.BLUE);
        detail.setBackground(Ui.roundStroke(0x00000000, Ui.BLUE, 1, 9, this));
        int dp8 = Ui.dp(this, 8);
        detail.setPadding(dp8, Ui.dp(this, 5), dp8, Ui.dp(this, 5));
        detail.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { deviceDetailDialog(); }
        });
        top.addView(detail);
        c.addView(top, Ui.gap(this, 8));

        // 分数进度条
        fitBar = new LinearLayout(this);
        fitBar.setOrientation(LinearLayout.HORIZONTAL);
        fitBar.setBackground(Ui.round(Ui.alpha(Ui.MUTED, 0x38), 5, this));
        fitFill = new View(this);
        fitFill.setBackground(Ui.round(Ui.BLUE, 5, this));
        fitBar.addView(fitFill, new LinearLayout.LayoutParams(0,
                Ui.dp(this, 9), 1));
        fitBar.addView(new View(this), new LinearLayout.LayoutParams(0,
                Ui.dp(this, 9), 99));
        c.addView(fitBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 9)));

        tvDeviceBody = new TextView(this);
        tvDeviceBody.setText("正在评估…");
        tvDeviceBody.setTextSize(12);
        tvDeviceBody.setTextColor(Ui.TEXT);
        tvDeviceBody.setLineSpacing(Ui.dp(this, 3), 1f);
        tvDeviceBody.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        c.addView(tvDeviceBody);

        TextView privacy = new TextView(this);
        privacy.setText("✓ 只读基础信息 · ✗ 不读 IMEI / MAC / 位置 / 通讯录 / 应用列表 · 不上传");
        privacy.setTextSize(10);
        privacy.setTextColor(Ui.MUTED);
        c.addView(privacy, Ui.gap(this, 8));

        c.addView(Ui.row(this,
                Ui.button(this, "查看详情", 1, new View.OnClickListener() {
                    @Override public void onClick(View v) { deviceDetailDialog(); }
                }),
                Ui.button(this, "一键权限", 1, new View.OnClickListener() {
                    @Override public void onClick(View v) { oneKeyPermissions(); }
                }),
                Ui.button(this, "复制信息", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { copy(deviceText()); }
                })));
        c.addView(Ui.button(this, "🧩 架构说明 · ROM / 内核 / 全新架构分清楚", 1,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { architectureDialog(); }
                }), Ui.gap(this, 8));
        return c;
    }

    private void renderDevice() {
        if (tvDeviceBody == null) return;
        try {
            DeviceInfo.Report r = DeviceInfo.assess(this);
            int pending = 0;
            for (DeviceInfo.Check ck : r.checks) if (ck.level > 0) pending++;
            tvFitSummary.setText(r.score + " / 100 · " + r.level);
            int col = r.score >= 75 ? Ui.GREEN : r.score >= 60 ? Ui.WARN : Ui.RED;
            tvFitSummary.setTextColor(col);
            tvDeviceBody.setText(r.summary + "\n"
                    + (pending == 0 ? "全部检查项已通过，无需额外设置。"
                            : pending + " 项待处理 · 点「查看详情」逐条一键修复"));
            if (fitFill != null) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)
                        fitFill.getLayoutParams();
                lp.weight = Math.max(1, r.score);
                fitFill.setLayoutParams(lp);
                View rest = fitBar.getChildAt(1);
                if (rest != null) {
                    LinearLayout.LayoutParams rp = (LinearLayout.LayoutParams)
                            rest.getLayoutParams();
                    rp.weight = Math.max(1, 100 - r.score);
                    rest.setLayoutParams(rp);
                }
                fitFill.setBackground(Ui.round(col, 5, this));
            }
            if (tvFitBadge != null) {
                tvFitBadge.setText("适配 " + r.score + " 分 ›");
                tvFitBadge.setTextColor(col);
                tvFitBadge.setBackground(Ui.roundStroke(0x00000000, col, 1, 8, this));
            }
        } catch (Exception e) {
            tvFitSummary.setText("评估失败");
            tvDeviceBody.setText("无法读取设备信息：" + e.getMessage());
        }
    }

    /** 复制用的纯文本汇总。 */
    private String deviceText() {
        DeviceInfo.Info i = DeviceInfo.collect(this);
        DeviceInfo.Report r = DeviceInfo.assess(this);
        StringBuilder sb = new StringBuilder();
        sb.append("ChmlFrp 启动器 · 设备与适配 ").append(r.score).append("/100 · ")
          .append(r.level).append('\n').append(r.summary).append("\n\n适配检查\n");
        for (DeviceInfo.Check ck : r.checks) {
            sb.append(ck.level == 0 ? "✓ " : ck.level == 1 ? "◌ " : "✗ ")
              .append(ck.name).append("  ").append(ck.result).append('\n');
        }
        sb.append("\n补充观察\n");
        for (String e : r.extras) sb.append("· ").append(e).append('\n');
        sb.append("\n基础设备信息\n");
        for (String[] kv : i.pairs()) sb.append(kv[0]).append("  ").append(kv[1]).append('\n');
        sb.append("\n隐私：仅基础信息，不读取 IMEI/MAC/位置/通讯录/应用列表，不上传。");
        return sb.toString();
    }

    /** 结构化设备详情弹窗：两列信息网格，彻底解决等宽文本折行错乱。 */
    private void deviceDetailDialog() {
        final DeviceInfo.Info info = DeviceInfo.collect(this);
        final DeviceInfo.Report r = DeviceInfo.assess(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 4);
        box.setPadding(pad, pad, pad, pad);

        // 分数横幅
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Ui.HEADER_START, Ui.HEADER_END}));
        int bp = Ui.dp(this, 14);
        banner.setPadding(bp, bp, bp, bp);
        TextView sc = new TextView(this);
        sc.setText(r.score + " 分 · " + r.level);
        sc.setTextSize(24);
        sc.setTypeface(Typeface.DEFAULT_BOLD);
        sc.setTextColor(0xFFFFFFFF);
        banner.addView(sc);
        TextView ss = new TextView(this);
        ss.setText(r.summary);
        ss.setTextSize(12);
        ss.setTextColor(0xDDFFFFFF);
        banner.addView(ss);
        box.addView(banner, Ui.gap(this, 12));

        box.addView(Ui.section(this, "适配检查 · 每项可一键修复"));
        for (DeviceInfo.Check ck : r.checks) box.addView(checkRow(ck));

        box.addView(Ui.section(this, "基础设备信息 · 两列网格"));
        String[][] kv = info.pairs();
        for (int i = 0; i < kv.length; i++) box.addView(kvRow(kv[i][0], kv[i][1], i % 2 == 0));

        box.addView(Ui.section(this, "补充观察（不计分）"));
        for (String e : r.extras) {
            TextView t = new TextView(this);
            t.setText("· " + e);
            t.setTextSize(11);
            t.setTextColor(Ui.MUTED);
            t.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 3));
            box.addView(t);
        }

        TextView privacy = new TextView(this);
        privacy.setText("✓ 只读取：品牌 / 型号 / 系统 / ABI / 屏幕 / 内存 / 核数 / 深色模式 / 语言 / 权限状态\n"
                + "✗ 不读取：IMEI、序列号、MAC、广告 ID、位置、通讯录、账号、电话号码、应用列表、剪贴板\n"
                + "Manifest 未申请任何隐私权限，信息仅存本机、不上传。");
        privacy.setTextSize(10);
        privacy.setTextColor(Ui.MUTED);
        privacy.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), 0, 0);
        box.addView(privacy);

        new GlassDialog.Builder(this)
                .setTitle("设备与适配详情")
                .setView(box)
                .setPositiveButton("一键权限", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { oneKeyPermissions(); }
                })
                .setNeutralButton("复制信息", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { copy(deviceText()); }
                })
                .setNegativeButton("关闭", null).show();
    }

    private View checkRow(final DeviceInfo.Check ck) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.TOP);
        row.setBackground(Ui.roundStroke(
                Ui.alpha(Ui.CARD, Ui.GLASS ? 0x70 : 0xFF), Ui.LINE, 1, 12, this));
        int p = Ui.dp(this, 11);
        row.setPadding(p, p, p, p);

        TextView dot = new TextView(this);
        dot.setText(ck.level == 0 ? "✓" : ck.level == 1 ? "◌" : "✗");
        dot.setTextSize(13);
        dot.setTypeface(Typeface.DEFAULT_BOLD);
        dot.setTextColor(ck.level == 0 ? Ui.GREEN : ck.level == 1 ? Ui.WARN : Ui.RED);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 22), LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(0, 0, Ui.dp(this, 8), 0);
        row.addView(dot, dlp);

        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        TextView nm = new TextView(this);
        nm.setText(ck.name + "   " + ck.score + "/" + ck.weight + " 分");
        nm.setTextSize(12);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setTextColor(Ui.TEXT);
        tx.addView(nm);
        TextView rs = new TextView(this);
        rs.setText(ck.result);
        rs.setTextSize(11);
        rs.setTextColor(ck.level == 0 ? Ui.GREEN : Ui.TEXT);
        tx.addView(rs);
        TextView ad = new TextView(this);
        ad.setText(ck.advice);
        ad.setTextSize(10);
        ad.setTextColor(Ui.MUTED);
        ad.setLineSpacing(Ui.dp(this, 2), 1f);
        tx.addView(ad);
        row.addView(tx, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (ck.actionKey.length() > 0) {
            TextView fix = new TextView(this);
            fix.setText("修复 ›");
            fix.setTextSize(11);
            fix.setTypeface(Typeface.DEFAULT_BOLD);
            fix.setTextColor(Ui.BLUE);
            fix.setBackground(Ui.roundStroke(0x00000000, Ui.BLUE, 1, 9, this));
            int fp = Ui.dp(this, 8);
            fix.setPadding(fp, Ui.dp(this, 5), fp, Ui.dp(this, 5));
            fix.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { doFix(ck.actionKey); }
            });
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            flp.setMargins(Ui.dp(this, 8), 0, 0, 0);
            row.addView(fix, flp);
        }
        return row;
    }

    private View kvRow(String k, String v, boolean even) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.TOP);
        row.setBackground(Ui.round(even
                ? Ui.alpha(Ui.MUTED, 0x12) : 0x00000000, 6, this));
        int p = Ui.dp(this, 8);
        row.setPadding(p, p, p, p);
        TextView kt = new TextView(this);
        kt.setText(k);
        kt.setTextSize(11);
        kt.setTextColor(Ui.MUTED);
        kt.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.38f));
        row.addView(kt);
        TextView vt = new TextView(this);
        vt.setText(v);
        vt.setTextSize(12);
        vt.setTextColor(Ui.TEXT);
        vt.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.62f));
        row.addView(vt);
        return row;
    }

    private void doFix(String key) {
        if ("battery".equals(key)) {
            Perm.requestBatteryWhitelist(this);
        } else if ("notify".equals(key)) {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                       != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                        Perm.REQ_NOTIFY);
            } else {
                startActivity(Perm.appNotification(this));
            }
        } else if ("motion".equals(key)) {
            sp.edit().putBoolean("glassMotion", false).commit();
            if (cbGlassMotion != null) cbGlassMotion.setChecked(false);
            toast("已关闭动态光斑，静态玻璃更省电");
            renderDevice();
            recreate();
        } else {
            toast("当前包为通用架构，建议安装 arm64 版以匹配官方魔改内核");
        }
    }

    // ============================================ 签到中心

    /** v3.0：签到中心默认收起，把首屏还给隧道状态；展开状态本机记忆。 */
    private View signWrapper() {
        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.VERTICAL);
        final TextView hd = Ui.section(this, "签到中心");
        final View card = signCard();
        final boolean init = sp.getBoolean("signExpanded", false);
        hd.setText(init ? "签到中心  ▾  已展开（点击收起）"
                        : "签到中心  ▸  已收起（点击展开）");
        card.setVisibility(init ? View.VISIBLE : View.GONE);
        hd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean open = card.getVisibility() != View.VISIBLE;
                card.setVisibility(open ? View.VISIBLE : View.GONE);
                hd.setText(open ? "签到中心  ▾  已展开（点击收起）"
                                : "签到中心  ▸  已收起（点击展开）");
                sp.edit().putBoolean("signExpanded", open).commit();
            }
        });
        w.addView(hd);
        w.addView(card, Ui.gap(this, 8));
        return w;
    }

    private View signCard() {
        LinearLayout c = Ui.card(this);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tvSignTitle = new TextView(this);
        tvSignTitle.setText("每日签到 · ChmlFrp");
        tvSignTitle.setTextSize(16);
        tvSignTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvSignTitle.setTextColor(Ui.TEXT);
        top.addView(tvSignTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView tag = Ui.badge(this, "官方积分", Ui.BLUE);
        top.addView(tag);
        c.addView(top, Ui.gap(this, 8));

        tvSignBody = new TextView(this);
        tvSignBody.setText("登录后点「查询状态」");
        tvSignBody.setTextSize(12);
        tvSignBody.setTextColor(Ui.TEXT);
        tvSignBody.setBackground(Ui.panel(this, 10));
        int p = Ui.dp(this, 11);
        tvSignBody.setPadding(p, p, p, p);
        tvSignBody.setLineSpacing(Ui.dp(this, 3), 1f);
        c.addView(tvSignBody, Ui.gap(this, 8));

        tvSignTrend = new TextView(this);
        tvSignTrend.setText(Sign.trend(this) + "\n\n" + Sign.visitGrid(this));
        tvSignTrend.setTextSize(11);
        tvSignTrend.setTextColor(Ui.MUTED);
        tvSignTrend.setBackground(Ui.panel(this, 10));
        tvSignTrend.setPadding(p, p, p, p);
        tvSignTrend.setLineSpacing(Ui.dp(this, 2), 1f);
        c.addView(tvSignTrend, Ui.gap(this, 8));

        cbSignRemind = new CheckBox(this);
        cbSignRemind.setText("每天首次打开时提醒签到");
        cbSignRemind.setTextSize(12);
        cbSignRemind.setTextColor(Ui.MUTED);
        c.addView(cbSignRemind, Ui.gap(this, 8));

        c.addView(Ui.row(this,
                Ui.button(this, "查询状态", 1, new View.OnClickListener() {
                    @Override public void onClick(View v) { refreshSign(false); }
                }),
                Ui.button(this, "去签到", 0, new View.OnClickListener() {
                    @Override public void onClick(View v) { doSignAction(); }
                }),
                Ui.button(this, "打开面板", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { openSignPanel(); }
                })));
        c.addView(Ui.row(this,
                Ui.button(this, "签到接口设置", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) { signSettings(); }
                }),
                Ui.button(this, "复制签到信息", 2, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        copy(tvSignBody.getText() + "\n\n" + tvSignTrend.getText());
                    }
                })), Ui.gap(this, 8));

        TextView note = new TextView(this);
        note.setText(Sign.CAPTCHA_NOTE
                + "\n\n已接：v2 /userinfo（积分 / 用户 ID / 用户组）、v1 /qdxx.php（今日签到状态，"
                + "域名未公开可探测）。执行签到端点官方未公开，故不做假接口。");
        note.setTextSize(10);
        note.setTextColor(Ui.MUTED);
        note.setLineSpacing(Ui.dp(this, 2), 1f);
        note.setPadding(Ui.dp(this, 4), 0, 0, 0);
        c.addView(note);
        return c;
    }

    private void refreshSign(final boolean silent) {
        final String token = sp.getString("token", "").trim();
        if (token.length() == 0) {
            if (!silent) toast("请先 OAuth 登录");
            return;
        }
        if (!silent) toast("正在查询签到信息…");
        bg(new Job() {
            @Override public String work() throws Exception {
                Sign.User u;
                try { u = Sign.userInfo(token); }
                catch (Exception e) { return "ERR|用户信息接口失败：" + e.getMessage(); }
                String uid = sp.getString("signUserId", "").trim();
                if (uid.length() == 0) uid = u.id;
                if (u.ok && uid.length() > 0) {
                    sp.edit().putString("signUserId", uid)
                            .putString("signUserName", u.name).commit();
                }
                if (u.ok) Sign.recordPoints(MainActivity.this, u.integral);

                Sign.Status st = null;
                String err = "";
                try { st = Sign.signInfo(MainActivity.this, token, uid); }
                catch (Exception e) { err = e.getMessage(); }

                StringBuilder sb = new StringBuilder();
                if (u.ok) {
                    sb.append("账号      ").append(u.name.length() > 0 ? u.name : "—")
                      .append("   ID ").append(u.id.length() > 0 ? u.id : "—").append('\n')
                      .append("用户组    ").append(u.group.length() > 0 ? u.group : "—")
                      .append(u.term.length() > 0 ? " · " + u.term : "").append('\n')
                      .append("积分      ").append(u.integral).append('\n')
                      .append("隧道      ").append(u.tunnels)
                      .append(u.tunnelMax >= u.tunnels && u.tunnelMax > 0
                              ? " / " + u.tunnelMax + " 条上限" : " 条")
                      .append("   连接 ").append(u.conns).append('\n')
                      .append("累计流量  ↑ ").append(Units.bytes(u.up, true))
                      .append("   ↓ ").append(Units.bytes(u.down, true));
                } else {
                    sb.append("用户信息：接口未返回 data（令牌可能已失效）");
                }
                sb.append("\n\n────────────────\n");
                if (st != null && st.ok) {
                    sb.append("今日签到  ").append(st.today ? "✓ 已签到" : "○ 未签到").append('\n')
                      .append("最后签到  ").append(st.last.length() > 0 ? st.last : "—").append('\n')
                      .append("累计次数  ").append(st.total.length() > 0 ? st.total : "—").append('\n')
                      .append("累计积分  ").append(st.points).append('\n')
                      .append("今日人数  ").append(st.todayCount);
                } else {
                    sb.append("今日签到状态：未取得\n")
                      .append(Sign.diagnose(MainActivity.this));
                    if (err.length() > 0) sb.append("\n（接口提示：").append(err).append("）");
                }
                return "OK|" + sb.toString();
            }
            @Override public void done(String r) {
                String body = r.startsWith("ERR|") ? r.substring(4)
                        : r.substring(r.indexOf('|') + 1);
                tvSignBody.setText(body);
                if (tvSignTrend != null) {
                    tvSignTrend.setText(Sign.trend(MainActivity.this)
                            + "\n\n" + Sign.visitGrid(MainActivity.this));
                }
                if (!silent) toast(r.startsWith("ERR|") ? "查询失败，请看详情" : "签到信息已更新");
                if (r.startsWith("ERR|")) log("[签到] " + body);
            }
        });
    }

    private void openSignPanel() {
        try {
            Sign.markVisit(this);
            startActivity(new Intent(Intent.ACTION_VIEW, Sign.panelUri()));
            log("[签到] 已打开管理面板（面板内需完成点选验证码）");
            toast("面板里完成点选验证码后点「签到信息 / 签到」");
        } catch (Exception e) { toast("无法打开面板：" + e.getMessage()); }
    }

    private void doSignAction() {
        final String token = sp.getString("token", "").trim();
        if (token.length() == 0) { toast("请先 OAuth 登录"); return; }
        final boolean hasCustom = Sign.doSignEndpoint(this).length() > 0;
        String msg = "ChmlFrp 官方公开文档里只有「签到信息查询」接口，没有公布执行签到的端点。\n\n"
                + Sign.CAPTCHA_NOTE + "\n\n"
                + "所以这里直接带你去管理面板：在面板里完成点选验证码后点签到即可"
                + "（官网说明每日签到可免费领积分 / 会员）。";
        GlassDialog.Builder b = new GlassDialog.Builder(this)
                .setTitle("去面板签到")
                .setMessage(msg)
                .setPositiveButton("打开面板签到", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { openSignPanel(); }
                })
                .setNegativeButton("取消", null);
        if (hasCustom) {
            b.setNeutralButton("试自定义端点", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { tryCustomSign(); }
            });
        }
        b.show();
    }

    private void tryCustomSign() {
        final String token = sp.getString("token", "").trim();
        final String uid = sp.getString("signUserId", "").trim();
        toast("自定义端点可能被人机验证拦下…");
        bg(new Job() {
            @Override public String work() throws Exception {
                String r = Sign.doSign(MainActivity.this, token, uid);
                return r == null ? "PANEL|" : "OK|" + r;
            }
            @Override public void done(String r) {
                if (r.startsWith("PANEL|")) { openSignPanel(); return; }
                String body = r.substring(r.indexOf('|') + 1);
                log("[签到] 自定义端点返回：" + body.replace('\n', ' '));
                new GlassDialog.Builder(MainActivity.this)
                        .setTitle("签到接口返回")
                        .setMessage((body.length() > 600 ? body.substring(0, 600) : body)
                                + "\n\n若提示验证码 / 校验失败，请改用面板签到。")
                        .setPositiveButton("刷新状态", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                refreshSign(false);
                            }
                        })
                        .setNeutralButton("打开面板", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                openSignPanel();
                            }
                        })
                        .setNegativeButton("关闭", null).show();
            }
        });
    }

    private void signSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 6);
        box.setPadding(pad, pad, pad, 0);

        final EditText base = Ui.edit(this, "v1 接口地址，如 https://xxx/", 1, false, false);
        base.setText(sp.getString("signV1Base", ""));
        final EditText ep = Ui.edit(this, "执行签到端点（官方未公开，建议留空）", 1, false, false);
        ep.setText(sp.getString("signDoEndpoint", ""));
        final EditText uid = Ui.edit(this, "用户 ID（留空自动从 /userinfo 获取）", 1, false, false);
        uid.setText(sp.getString("signUserId", ""));

        TextView h1 = Ui.label(this, "① v1 接口地址（查今日签到状态）");
        TextView h2 = Ui.label(this, "② 执行签到端点（不建议填，见下方说明）");
        TextView h3 = Ui.label(this, "③ 用户 ID");
        TextView warn = new TextView(this);
        warn.setText("⚠ 若把浏览器地址栏的 https://panel.chmlfrp.net/home 整条粘进来，"
                + "那是控制台网页不是接口；保存时会自动按域名归一，探测时也会尝试 /api 前缀。");
        warn.setTextSize(10);
        warn.setTextColor(Ui.WARN);
        warn.setLineSpacing(Ui.dp(this, 2), 1f);
        TextView cap = new TextView(this);
        cap.setText("⚠ " + Sign.CAPTCHA_NOTE);
        cap.setTextSize(10);
        cap.setTextColor(Ui.MUTED);
        cap.setLineSpacing(Ui.dp(this, 2), 1f);
        cap.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), 0, 0);

        box.addView(h1); box.addView(base, Ui.gap(this, 6));
        box.addView(h2); box.addView(ep, Ui.gap(this, 6));
        box.addView(h3); box.addView(uid, Ui.gap(this, 8));
        box.addView(warn);
        box.addView(cap);

        new GlassDialog.Builder(this)
                .setTitle("签到接口设置")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String raw = base.getText().toString().trim();
                        String nb = Sign.normalizeBase(raw);
                        String saved = nb.length() > 0 ? nb : raw;
                        sp.edit().putString("signV1Base", saved)
                                .putString("signDoEndpoint", ep.getText().toString().trim())
                                .putString("signUserId", uid.getText().toString().trim())
                                .commit();
                        if (Sign.isPanelPageUrl(raw) && raw.contains("/home")) {
                            toast("已把面板网页地址归一为 " + saved);
                        } else {
                            toast("已保存");
                        }
                        log("[签到] v1 地址 = " + saved);
                        refreshSign(true);
                    }
                })
                .setNeutralButton("自动探测", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final String token = sp.getString("token", "").trim();
                        if (token.length() == 0) { toast("请先登录"); return; }
                        toast("正在探测 v1 地址…");
                        bg(new Job() {
                            @Override public String work() throws Exception {
                                String u = sp.getString("signUserId", "").trim();
                                if (u.length() == 0) {
                                    try { u = Sign.userInfo(token).id; } catch (Exception ignored) {}
                                }
                                return Sign.probeBase(token, u,
                                        sp.getString("signV1Base", ""));
                            }
                            @Override public void done(String r) {
                                if (r.length() == 0) {
                                    toast("未探测到可用地址，签到状态请以面板为准");
                                    log("[签到] v1 探测失败：" + Sign.diagnose(MainActivity.this));
                                } else {
                                    sp.edit().putString("signV1Base", r).commit();
                                    toast("已找到：" + r);
                                    log("[签到] v1 地址 = " + r);
                                    refreshSign(true);
                                }
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void toast(String s) {
        String t = s == null ? "" : s;
        if (t.length() > 42) t = t.substring(0, 42) + "…";
        Toast to = Toast.makeText(this, t, Toast.LENGTH_SHORT);
        // 系统默认 Toast 在底部，会正好压住「控制台/配置/日志/设置」四个 Tab
        try {
            to.setGravity(android.view.Gravity.TOP
                            | android.view.Gravity.CENTER_HORIZONTAL,
                    0, Ui.dp(this, 110));
        } catch (Exception ignored) {}
        to.show();
    }

    /** v3.0 一键自检：把散在各页的状态合成一段，写日志并弹窗。 */
    private void fullSelfCheck() {
        DeviceInfo.Info i = DeviceInfo.collect(this);
        Kernel.Info k = Kernel.cached();
        String cfg = Config.readCurrent(this);
        String verify = sp.getString("tokenVerify", "");
        StringBuilder b = new StringBuilder("一键自检 · v3.1.0\n\n");
        b.append("设备   ").append(i.brand).append(' ').append(i.model)
         .append(" · Android ").append(i.android).append('\n')
         .append("ABI   ").append(i.primaryAbi)
         .append(" · ROM ").append(RomInfo.detect(this).name).append('\n')
         .append("内核   ").append(k.probed ? k.version : "未探测（启动后自动识别）")
         .append("  ·  配置 ").append(Config.format(cfg))
         .append(" / ").append(Config.proxyCount(cfg)).append(" 段\n")
         .append("令牌   ").append("ok".equals(verify) ? "面板校验通过"
                 : "stale".equals(verify) ? "面板校验未通过（本机令牌仍可启动）" : "未校验")
         .append('\n')
         .append("守护   ").append(sp.getBoolean("watchdog", true) ? "开启" : "关闭")
         .append(" · 运行 ").append(FrpService.stateLabel()).append('\n')
         .append("隧道缓存 ").append(TunnelStore.load(this).size()).append(" 条\n")
         .append("QQ 加好友页 ").append(sp.contains("qqLanding")
                 ? "已刷新缓存 · 点击直达" : "内置落地页 · 点击直达").append('\n')
         .append(QqHelper.diagnose(this)).append('\n');
        final String s = b.toString();
        log("[自检] " + s.replace('\n', ' '));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 4);
        box.setPadding(pad, pad, pad, pad);
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(Ui.TEXT);
        t.setLineSpacing(Ui.dp(this, 3), 1f);
        t.setTextIsSelectable(true);
        box.addView(t);
        new GlassDialog.Builder(this)
                .setTitle("🔎 一键自检")
                .setSubtitle("本机状态汇总 · 不含令牌与配置内容")
                .setView(box)
                .setPositiveButton("复制结论", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { copy(s); }
                })
                .setNegativeButton("关闭", null).show();
    }

    /** 复制可安全发给作者的脱敏运行诊断（不含令牌 / 配置 / 日志）。 */
    private void copyDiagnostics() {
        DeviceInfo.Info i = DeviceInfo.collect(this);
        Kernel.Info k = Kernel.cached();
        StringBuilder b = new StringBuilder("ChmlFrp 运行诊断（已脱敏）\n");
        b.append("版本   3.1.0\n")
         .append("设备   ").append(i.brand).append(' ').append(i.model).append('\n')
         .append("系统   Android ").append(i.android).append(" · API ").append(i.sdkInt).append('\n')
         .append("ABI    ").append(i.primaryAbi).append('\n')
         .append("ROM    ").append(RomInfo.detect(this).name).append('\n')
         .append("内核   ").append(k.probed ? k.version : "未探测（启动后自动识别）").append('\n')
         .append("隧道缓存   ").append(TunnelStore.load(this).size()).append(" 条\n")
         .append("配置代理段 ").append(Config.proxyCount(Config.readCurrent(this))).append(" 个\n")
         .append("运行状态   ").append(FrpService.stateLabel()).append('\n')
         .append(QqHelper.diagnose(this)).append('\n')
         .append("\n不含令牌、配置内容、日志与任何账号信息。");
        copy(b.toString());
        log("[诊断] 已复制脱敏运行诊断");
    }

    private void clearTunnelCache() {
        TunnelStore.save(this, "");
        toast("隧道列表缓存已清空");
        log("[隧道] 已清空本机隧道列表缓存");
    }
}
