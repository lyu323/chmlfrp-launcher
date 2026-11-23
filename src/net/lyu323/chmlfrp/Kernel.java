package net.lyu323.chmlfrp;

import android.content.Context;

/**
 * 内核能力探测。
 *
 * 打包进去的 lib/&lt;abi&gt;/libfrpc.so 并不统一：arm64 是 ChmlFrp 官方魔改
 * 0.51.2_240715（只认 INI、有 -u/-p、没有 --strict-config），其余 ABI 是
 * fatedier frp 0.61.1（支持 TOML、有 --strict-config）。
 * 所以启动参数、配置格式提示、模板都必须按真实内核动态决定，不能写死。
 */
public final class Kernel {
    private Kernel() {}

    public static final class Info {
        public String version = "未知";
        public String help = "";
        public boolean probed;
        public boolean chmlfrp;       // ChmlFrp 官方魔改内核
        public boolean strictConfig;  // 支持 --strict-config
        public boolean toml;          // 支持 TOML / YAML / JSON
        public boolean idToken;       // 支持 -u / -p 官方参数启动
        public boolean ini;           // 支持 INI（两者都支持）
        public String configDirFlag = "";

        public String typeLabel() {
            return chmlfrp ? "ChmlFrp 官方魔改内核" : "fatedier frp 标准内核";
        }

        /** 配置格式提示，避免对 0.51.2 误导用户去转 TOML。 */
        public String formatHint(String fmt) {
            if (!probed) return fmt + "（内核检测中…）";
            if (fmt.startsWith("INI")) {
                return chmlfrp ? "INI · 当前内核原生格式，无需转换"
                        : "INI · 兼容，但官方已标记弃用";
            }
            if (fmt.startsWith("TOML")) {
                return chmlfrp ? "TOML · 当前内核不支持，请换回 INI！"
                        : "TOML · 当前内核推荐格式";
            }
            return fmt;
        }

        public boolean configCompatible(String text) {
            boolean isToml = text != null
                    && (text.contains("[[proxies]]") || text.contains("serverAddr"));
            return !(isToml && !toml);
        }
    }

    private static volatile Info cached;

    public static Info cached() {
        return cached == null ? new Info() : cached;
    }

    /** 纯文本判定，便于离线验证（真机拿不到二进制时也能核对）。 */
    public static Info parse(String version, String help) {
        Info i = new Info();
        i.version = version == null ? "未知" : version.trim().split("\n")[0].trim();
        i.help = help == null ? "" : help;
        String v = i.version.toLowerCase();
        String h = i.help;
        i.chmlfrp = v.contains("chmlfrp") || h.contains("ProxyID of ChmlFrp")
                || h.contains("The Token of ChmlFrp");
        i.strictConfig = h.contains("--strict-config");
        i.toml = !i.chmlfrp
                && (v.startsWith("0.6") || v.startsWith("0.7") || v.startsWith("0.8")
                    || v.startsWith("0.9"));
        i.idToken = h.contains("--id") && h.contains("--token")
                && h.contains("ChmlFrp");
        i.ini = true;
        i.configDirFlag = h.contains("--config_dir") ? "--config_dir"
                : h.contains("--config-dir") ? "--config-dir" : "";
        i.probed = true;
        return i;
    }

    /** 同步执行 -v 与 --help，结果进程内缓存。 */
    public static synchronized Info probe(Context c) {
        if (cached != null) return cached;
        String v = "未知", h = "";
        try { v = FrpService.exec(c, "-v"); } catch (Exception ignored) {}
        try { h = FrpService.exec(c, "--help"); } catch (Exception ignored) {}
        cached = parse(v, h);
        return cached;
    }

    /** 构造启动命令。两个内核都只认 -c，绝不传 0.51.2 不认识的 flag。 */
    public static String[] command(String configPath) {
        return new String[]{"-c", configPath};
    }
}
