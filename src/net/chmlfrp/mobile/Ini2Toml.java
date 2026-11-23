package net.chmlfrp.mobile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 ChmlFrp 面板生成的 frpc.ini（frp 0.51 风格）转成 frp 0.6x 标准 TOML。
 *
 * frp 0.61 仍兼容 INI，但启动时会打印 "ini format is deprecated" 警告，
 * 未来版本会移除。转换是可选的「面向未来」功能，转换结果可用「配置自检」验证。
 */
public final class Ini2Toml {
    private Ini2Toml() {}

    /** 只认真正的 INI，避免把 TOML 的 [[proxies]] / serverAddr 误判进来。 */
    public static boolean looksLikeIni(String s) {
        if (s == null) return false;
        if (s.contains("[[proxies]]") || s.contains("serverAddr")
                || s.contains("serverAddr:")) return false;
        return s.contains("[common]") || s.contains("server_addr")
                || s.contains("local_ip") || s.contains("custom_domains")
                || s.contains("remote_port");
    }

    /** 返回 {toml文本, 警告说明}；非 INI 时原样返回。 */
    public static String[] convert(String src) {
        if (!looksLikeIni(src)) {
            return new String[]{src, "内容不是 INI，未做转换"};
        }
        List<String> warns = new ArrayList<String>();
        Map<String, Map<String, String>> sections =
                new LinkedHashMap<String, Map<String, String>>();
        String cur = "";
        for (String raw : src.replace("\r\n", "\n").split("\n")) {
            String line = raw.trim();
            if (line.length() == 0 || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                cur = line.substring(1, line.length() - 1).trim();
                // TOML 的 [[table]] 或 [table] 不是 INI 段名，跳过
                if (cur.startsWith("[") || cur.endsWith("]")) continue;
                if (!sections.containsKey(cur)) {
                    sections.put(cur, new LinkedHashMap<String, String>());
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            if (cur.length() == 0) cur = "common";
            if (!sections.containsKey(cur)) {
                sections.put(cur, new LinkedHashMap<String, String>());
            }
            sections.get(cur).put(line.substring(0, eq).trim(),
                    line.substring(eq + 1).trim());
        }

        StringBuilder out = new StringBuilder();
        out.append("# 由 INI 自动转换 · 建议用「配置自检」确认后再启动\n");
        Map<String, String> common = sections.get("common");
        if (common != null) {
            for (Map.Entry<String, String> e : common.entrySet()) {
                String k = e.getKey(), v = unquote(e.getValue());
                String mapped = mapCommon(k);
                if (mapped == null) { warns.add(k); continue; }
                out.append(mapped).append(" = ").append(value(mapped, v)).append('\n');
            }
        }
        for (Map.Entry<String, Map<String, String>> sec : sections.entrySet()) {
            if ("common".equalsIgnoreCase(sec.getKey())) continue;
            out.append("\n[[proxies]]\n");
            out.append("name = ").append(quote(sec.getKey())).append('\n');
            for (Map.Entry<String, String> e : sec.getValue().entrySet()) {
                String k = e.getKey(), v = unquote(e.getValue());
                String mapped = mapProxy(k);
                if (mapped == null) { warns.add(k); continue; }
                out.append(mapped).append(" = ").append(value(mapped, v)).append('\n');
            }
        }
        String tip = warns.isEmpty()
                ? "全部字段已映射"
                : "已忽略不兼容字段：" + join(warns);
        return new String[]{out.toString(), tip};
    }

    private static String mapCommon(String k) {
        if (eq(k, "server_addr")) return "serverAddr";
        if (eq(k, "server_port")) return "serverPort";
        if (eq(k, "token") || eq(k, "auth_token")) return "auth.token";
        if (eq(k, "user")) return "user";
        if (eq(k, "tls_enable")) return "transport.tls.enable";
        if (eq(k, "protocol")) return "transport.protocol";
        if (eq(k, "tls_trusted_ca_file")) return "transport.tls.trustedCaFile";
        if (eq(k, "tls_cert_file")) return "transport.tls.certFile";
        if (eq(k, "tls_key_file")) return "transport.tls.keyFile";
        if (eq(k, "pool_count")) return "transport.poolCount";
        if (eq(k, "heartbeat_interval")) return "transport.heartbeatInterval";
        if (eq(k, "heartbeat_timeout")) return "transport.heartbeatTimeout";
        // 新版已默认开启或不再支持，安全忽略
        if (eq(k, "tcp_mux") || eq(k, "dns_server") || eq(k, "login_fail_exit")
                || eq(k, "log_level") || eq(k, "log_file") || eq(k, "log_max_days")
                || eq(k, "admin_addr") || eq(k, "admin_port") || eq(k, "admin_user")
                || eq(k, "admin_pwd")) return null;
        return camel(k);
    }

    private static String mapProxy(String k) {
        if (eq(k, "type")) return "type";
        if (eq(k, "local_ip")) return "localIP";
        if (eq(k, "local_port")) return "localPort";
        if (eq(k, "remote_port")) return "remotePort";
        if (eq(k, "custom_domains")) return "customDomains";
        if (eq(k, "subdomain")) return "subdomain";
        if (eq(k, "use_encryption")) return "transport.useEncryption";
        if (eq(k, "use_compression")) return "transport.useCompression";
        if (eq(k, "bandwidth_limit")) return "transport.bandwidthLimit";
        if (eq(k, "bandwidth_limit_mode")) return "transport.bandwidthLimitMode";
        if (eq(k, "http_user")) return "httpUser";
        if (eq(k, "http_pwd")) return "httpPwd";
        if (eq(k, "locations")) return "locations";
        if (eq(k, "sk")) return "secretKey";
        if (eq(k, "server_name")) return "serverName";
        if (eq(k, "bind_addr")) return "bindAddr";
        if (eq(k, "bind_port")) return "bindPort";
        if (eq(k, "role")) return "role";
        if (eq(k, "host_header_rewrite")) return null; // 需 requestHeaders 表，交给用户手改
        return camel(k);
    }

    private static String value(String key, String v) {
        if (isIntKey(key)) {
            try { return String.valueOf(Integer.parseInt(v)); }
            catch (Exception e) { return quote(v); }
        }
        if (isBoolKey(key)) {
            return String.valueOf(v.equalsIgnoreCase("true") || v.equals("1"));
        }
        if (eq(key, "customDomains") || eq(key, "locations")) {
            String[] parts = v.split(",");
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < parts.length; i++) {
                String t = parts[i].trim();
                if (t.length() == 0) continue;
                if (sb.length() > 1) sb.append(", ");
                sb.append(quote(t));
            }
            return sb.append(']').toString();
        }
        return quote(v);
    }

    private static boolean isIntKey(String k) {
        return k.endsWith("Port") || eq(k, "serverPort") || eq(k, "poolCount")
                || eq(k, "remotePort") || eq(k, "localPort") || eq(k, "bindPort");
    }

    private static boolean isBoolKey(String k) {
        return k.endsWith(".enable") || k.endsWith("Encryption")
                || k.endsWith("Compression") || eq(k, "transport.tls.enable");
    }

    private static String camel(String s) {
        String[] p = s.split("_");
        StringBuilder sb = new StringBuilder(p[0]);
        for (int i = 1; i < p.length; i++) {
            if (p[i].length() == 0) continue;
            sb.append(Character.toUpperCase(p[i].charAt(0))).append(p[i].substring(1));
        }
        return sb.toString();
    }

    private static boolean eq(String a, String b) { return a.equalsIgnoreCase(b); }

    private static String unquote(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        int c = v.indexOf(" #");
        if (c > 0) v = v.substring(0, c).trim();
        return v;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) {
            if (sb.length() > 0) sb.append("、");
            sb.append(x);
        }
        return sb.toString();
    }
}
