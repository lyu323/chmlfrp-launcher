package net.chmlfrp.mobile;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 免责声明（v2.8）。
 *
 * 按事实写，不做「完全不联网」这类虚假声明：
 * 本 App 自己不提供/不托管/不中转任何服务器，但它必须连接
 *   · ChmlFrp 官方 API（OAuth 授权、隧道/配置查询、签到状态）
 *   · ChmlFrp 官方 frp 节点（隧道流量本身）
 * 否则隧道根本跑不起来。除了这两类官方地址，App 不向任何第三方
 * 服务器发送账号、令牌、配置、设备信息或日志。
 */
public final class Disclaimer {
    private Disclaimer() {}

    public static final String VERSION = "3.1";

    /** 首次启动弹窗用的短版，读一遍就能明白边界。 */
    public static final String SHORT =
        "本应用是第三方本地启动器，不是 ChmlFrp 官方客户端，与官方无隶属关系。\n\n"
      + "· App 本身：只在你手机本地运行，不提供、不托管、不自行中转任何服务器，"
      + "不代存到任何外部服务器、不上传你的数据。\n"
      + "· 网络行为：仅连接 ChmlFrp 官方 API（授权 / 隧道与配置查询 / 签到状态）"
      + "与官方 frp 节点（隧道流量）。除此之外不向任何第三方地址发送数据。\n"
      + "· 更新检查：仅在你手动点「检查更新」时访问 GitHub 公开 API 读取本项目 Release 版本信息，"
      + "只读取、不上传任何数据；不点不联网，不自动下载或安装。\n"
      + "· 本机数据：令牌、配置、日志、隧道列表、签到记录都只存在本机，"
      + "卸载即清除；不读 IMEI / MAC / 位置 / 通讯录 / 应用列表 / 剪贴板。\n"
      + "· 隧道风险：启动隧道等于把本机端口开放到公网，"
      + "账号、流量、服务可用性及由此产生的后果由你自行承担。\n"
      + "· 内核来自 ChmlFrp 官方客户端与 fatedier/frp（Apache-2.0）。";

    /** 完整版：把「本地 / 官方 API / 官方节点」三条边界逐条写清。 */
    public static String full() {
        StringBuilder b = new StringBuilder();
        b.append("一、应用性质\n")
         .append("本应用（下称「本启动器」）是第三方开发的 Android 本地工具，"
                 + "仅用于在用户设备本机启动 frpc 内核。本启动器不是 ChmlFrp 官方客户端，"
                 + "与 ChmlFrp / QZHUA 及其运营方无任何隶属、授权或合作关系。\n\n");

        b.append("二、本启动器只负责本地运行\n")
         .append("· 不提供、不部署、不托管、不租用任何服务器或节点，也不自行中转流量；\n")
         .append("· 不替代官方面板，不代管账号，不代签、不代刷任何积分；\n")
         .append("· 所有配置、令牌、日志、隧道列表、签到打点均保存在本机应用私有目录，"
                 + "不上传、不共享、不用于任何统计或分析；\n")
         .append("· 不申请定位、通讯录、电话、短信、存储、相机、麦克风等隐私权限，"
                 + "不读取 IMEI / 序列号 / MAC / 广告 ID / 位置 / 账号列表 / 应用列表 / 剪贴板。\n\n");

        b.append("三、唯一的外部连接：官方 API 与官方节点\n")
         .append("为使隧道可用，本启动器会且仅会连接以下两类官方地址：\n")
         .append("· 官方 v2 API 与 OAuth 授权服务：用于授权登录、刷新隧道列表、"
                 + "拉取隧道配置、查询账号信息与签到状态；\n")
         .append("· 官方 frp 节点：由 frpc 内核按你自己的配置建立隧道，"
                 + "流量在节点与你的本机服务之间转发。\n")
         .append("唯一的例外是手动触发的更新检查：点「检查更新」时访问 "
                 + "api.github.com 的公开 Release 接口，仅 GET 版本信息，不发送任何本机数据，"
                 + "不自动下载、不自动安装；不点不联网。\n")
         .append("除上述官方地址与手动更新检查外，本启动器不向任何第三方服务器发起请求，"
                 + "不含广告 SDK、不含统计 SDK、不含推送 SDK、无后台数据回传。"
                 + "你可在「设置 → 网络与隐私」里查看本版本实际使用的域名清单。\n\n");

        b.append("四、账号与凭据\n")
         .append("OAuth 授权在官方页面完成，本启动器只接收回调中的访问令牌并保存在本机；"
                 + "不会索取、记录或传输你的账号密码。若选择「粘贴令牌直登」，"
                 + "该令牌同样只存本机。请勿把令牌提供给任何第三方工具。\n\n");

        b.append("五、风险与责任\n")
         .append("· 启动隧道等同于将本机指定端口暴露到公网，请只映射必要服务、"
                 + "设置高强度密码、及时更新服务本身；\n")
         .append("· 节点可用性、带宽、延迟、账号状态、套餐与签到规则均由官方决定，"
                 + "本启动器不作任何可用性或稳定性承诺；\n")
         .append("· 因使用本启动器或第三方服务导致的账号异常、数据泄露、"
                 + "服务中断、流量费用、法律合规问题，由使用者自行承担；\n")
         .append("· 本启动器按「现状」提供，不附带任何明示或默示担保。\n\n");

        b.append("六、开源组件\n")
         .append("frpc 内核来自 ChmlFrp 官方客户端与 fatedier/frp（Apache-2.0）。"
                 + "请遵守对应许可证与官方服务条款。\n\n");

        b.append("继续使用即表示你已阅读并同意以上条款。");
        return b.toString();
    }

    /** 本版本实际会访问的域名清单（给「网络与隐私」页展示，可核对）。 */
    public static String[] hosts() {
        return new String[]{
            "cf-v2.uapis.cn         官方 v2 API（隧道 / 配置 / 账号）",
            "account-api.qzhua.net  官方 OAuth 授权与令牌",
            "panel.chmlfrp.net      官方管理面板（仅跳转浏览器）",
            "node.example.com       示例占位，实际节点以面板配置为准",
            "api.github.com          检查更新：仅手动点按钮时读取公开 Release 信息",
            "github.com              项目主页 / Releases 下载（仅浏览器跳转）",
            "（节点域名以你的配置为准，本启动器不内置任何节点）",
        };
    }

    public static boolean agreed(Context c) {
        return sp(c).getString("disclaimerVersion", "").equals(VERSION);
    }

    public static void agree(Context c) {
        sp(c).edit().putString("disclaimerVersion", VERSION)
                .putLong("disclaimerAt", System.currentTimeMillis()).commit();
    }

    public static void reset(Context c) {
        sp(c).edit().remove("disclaimerVersion").commit();
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("chmlfrp", Context.MODE_PRIVATE);
    }
}
