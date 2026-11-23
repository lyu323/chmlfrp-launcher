package net.chmlfrp.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 多配置管理：所有配置文件放在 filesDir/configs/，服务永远执行 current.conf。 */
public final class Config {
    public static final String DEFAULT = "default";

    private Config() {}

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "configs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 服务实际读取的文件，扩展名 .conf 不影响内核按内容识别 INI / TOML / JSON / YAML。 */
    public static File current(Context c) {
        return new File(c.getFilesDir(), "current.conf");
    }

    public static File file(Context c, String name) {
        return new File(dir(c), name + ".conf");
    }

    public static List<String> names(Context c) {
        List<String> out = new ArrayList<String>();
        File[] fs = dir(c).listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile() && f.getName().endsWith(".conf")) {
                    out.add(f.getName().substring(0, f.getName().length() - 5));
                }
            }
        }
        Collections.sort(out);
        if (out.isEmpty()) out.add(DEFAULT);
        return out;
    }

    public static String read(Context c, String name) {
        try {
            File f = file(c, name);
            if (!f.exists()) return "";
            return readAll(f);
        } catch (Exception e) {
            return "";
        }
    }

    /** 写配置，并同步一份到 current.conf（服务读它）。 */
    public static void write(Context c, String name, String text) throws Exception {
        FileOutputStream os = new FileOutputStream(file(c, name));
        os.write(text.getBytes("UTF-8"));
        os.close();
        FileOutputStream os2 = new FileOutputStream(current(c));
        os2.write(text.getBytes("UTF-8"));
        os2.close();
    }

    public static void delete(Context c, String name) {
        File f = file(c, name);
        if (f.exists()) f.delete();
        if (names(c).isEmpty()) {
            try { write(c, DEFAULT, ""); } catch (Exception ignored) {}
        }
    }

    public static boolean create(Context c, String name, String text) {
        if (name == null || name.trim().length() == 0) return false;
        name = name.trim().replace('/', '_').replace('\\', '_');
        if (names(c).contains(name) && new File(dir(c), name + ".conf").exists()) return false;
        try {
            write(c, name, text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 兼容旧版：把上一版的 frpc.toml 迁移为 default 配置。 */
    public static void migrate(Context c) {
        File old = new File(c.getFilesDir(), "frpc.toml");
        if (!old.exists()) return;
        if (names(c).size() == 1 && !file(c, DEFAULT).exists()) {
            try {
                write(c, DEFAULT, readAll(old));
                FrpService.append(c, "[配置] 已迁移旧版 frpc.toml 为 default 配置");
            } catch (Exception ignored) {}
        }
    }

    private static String readAll(File f) throws Exception {
        FileInputStream is = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** 只识别格式，不评价兼容性 —— 是否支持由真实内核能力决定。 */
    public static String format(String text) {
        if (text == null || text.trim().length() == 0) return "空";
        String s = text.trim();
        String first = "";
        for (String line : s.split("\\r?\\n")) {
            String t = line.trim();
            if (t.length() > 0 && !t.startsWith("#") && !t.startsWith(";")) {
                first = t;
                break;
            }
        }
        if (first.equalsIgnoreCase("[common]") || s.contains("[common]")) return "INI 格式";
        if (s.contains("[[proxies]]") || s.contains("serverAddr")) return "TOML 格式";
        if (first.startsWith("{")) return "JSON 格式";
        if (s.contains("serverAddr:")) return "YAML 格式";
        return "未识别（启动时自检）";
    }

    public static boolean isToml(String text) {
        return text != null && (text.contains("[[proxies]]") || text.contains("serverAddr"));
    }

    /** 从任意格式配置里提取服务器地址与端口，用于连通性测试。 */
    public static String[] server(String text) {
        String addr = "", port = "7000";
        if (text == null) return new String[]{addr, port};
        java.util.regex.Matcher a = java.util.regex.Pattern
                .compile("(?im)^\\s*(?:server_addr|serverAddr)\\s*=\\s*[\"']?([^\"'\\s#;]+)")
                .matcher(text);
        java.util.regex.Matcher p = java.util.regex.Pattern
                .compile("(?im)^\\s*(?:server_port|serverPort)\\s*=\\s*[\"']?([0-9]+)")
                .matcher(text);
        java.util.regex.Matcher ay = java.util.regex.Pattern
                .compile("(?im)^\\s*serverAddr\\s*:\\s*[\"']?([^\"'\\s#;]+)").matcher(text);
        if (a.find()) addr = a.group(1);
        else if (ay.find()) addr = ay.group(1);
        if (p.find()) port = p.group(1);
        return new String[]{addr, port};
    }

    /** 收集所有匹配项的 group(1)。 */
    private static List<String> all(String text, String regex) {
        List<String> out = new ArrayList<String>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
        while (m.find()) {
            String v = m.group(1).trim();
            if (v.length() > 0) out.add(v);
        }
        return out;
    }

    private static String stripValue(String v) {
        String s = v.trim();
        int hash = s.indexOf(" #");
        if (hash > 0) s = s.substring(0, hash).trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1);
        }
        return s.trim();
    }

    private static List<String> domains(String raw) {
        List<String> out = new ArrayList<String>();
        String s = raw.trim();
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        for (String part : s.split(",")) {
            String d = stripValue(part).replace("\"", "").replace("'", "").trim();
            if (d.length() > 0 && !d.startsWith("#")) out.add(d);
        }
        return out;
    }

    /**
     * 从配置里解析「连接地址」列表：
     * - INI: custom_domains / subdomain / remote_port
     * - TOML: customDomains / subdomain / remotePort
     * http/https 会带 scheme；tcp/udp 返回 节点:端口。
     */
    public static String[] endpoints(String text) {
        if (text == null || text.trim().length() == 0) return new String[0];
        String[] sv = server(text);
        String addr = sv[0], port = sv[1];
        List<String> out = new ArrayList<String>();

        List<String> types = all(text,
                "(?im)^\\s*type\\s*=\\s*[\"']?([a-z0-9_-]+)");
        boolean https = false, http = false;
        for (String t : types) {
            String x = stripValue(t).toLowerCase();
            if (x.equals("https")) https = true;
            if (x.equals("http")) http = true;
        }

        for (String raw : all(text,
                "(?im)^\\s*(?:custom_domains|customDomains)\\s*=\\s*(.+)$")) {
            for (String d : domains(raw)) {
                String e = https ? "https://" + d : http ? "http://" + d : d;
                if (!out.contains(e)) out.add(e);
            }
        }
        for (String raw : all(text,
                "(?im)^\\s*subdomain\\s*=\\s*[\"']?([^\"'\\s,;#]+)")) {
            String sub = stripValue(raw);
            String parent = addr;
            int dot = addr.indexOf('.');
            if (dot > 0) parent = addr.substring(dot + 1);
            String e = (https ? "https://" : "http://") + sub + "." + parent;
            if (!out.contains(e) && parent.length() > 0) out.add(e);
        }
        for (String raw : all(text,
                "(?im)^\\s*(?:remote_port|remotePort)\\s*=\\s*[\"']?([0-9]+)")) {
            String rp = stripValue(raw);
            if (addr.length() > 0) {
                String e = addr + ":" + rp;
                if (!out.contains(e)) out.add(e);
            }
        }
        if (out.isEmpty() && addr.length() > 0) {
            out.add(addr + ":" + port);
        }
        return out.toArray(new String[0]);
    }

    public static String displayHost(String endpoint) {
        if (endpoint == null) return "";
        String s = endpoint.trim();
        if (s.startsWith("https://")) s = s.substring(8);
        else if (s.startsWith("http://")) s = s.substring(7);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static boolean isWeb(String endpoint) {
        return endpoint != null && (endpoint.startsWith("http://")
                || endpoint.startsWith("https://"));
    }

    public static String urlOf(String endpoint) {
        if (endpoint == null) return "";
        return isWeb(endpoint) ? endpoint : "http://" + endpoint;
    }

    // ------------------------------------------------ 协议 / 本地地址 / 地址解析

    /** 配置里第一个代理 type（https / http / tcp / udp）。 */
    public static String proxyType(String text) {
        if (text == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?im)^\\s*type\\s*=\\s*[\"']?([a-z0-9_-]+)")
                .matcher(text);
        while (m.find()) {
            String v = stripValue(m.group(1)).toLowerCase();
            if (v.length() > 0) return v;
        }
        return "";
    }

    /** 本地服务地址 {ip, port}，兼容 INI 与 TOML 字段名。 */
    public static String[] localAddr(String text) {
        String ip = "127.0.0.1", port = "";
        if (text != null) {
            java.util.regex.Matcher a = java.util.regex.Pattern
                    .compile("(?im)^\\s*(?:local_ip|localIP|localAddr)\\s*=\\s*[\"']?([^\"'\\s,;#]+)")
                    .matcher(text);
            java.util.regex.Matcher p = java.util.regex.Pattern
                    .compile("(?im)^\\s*(?:local_port|localPort)\\s*=\\s*[\"']?([0-9]+)")
                    .matcher(text);
            if (a.find()) ip = stripValue(a.group(1));
            if (p.find()) port = stripValue(p.group(1));
        }
        return new String[]{ip, port};
    }

    /** 服务实际读取的配置文件内容。 */
    public static String readCurrent(Context c) {
        return readAllSafe(current(c));
    }

    private static String readAllSafe(File f) {
        try { return readAll(f); } catch (Exception e) { return ""; }
    }

    private static boolean looksLikeDomain(String host) {
        if (host == null || host.length() == 0) return false;
        if (host.indexOf(':') >= 0) return false;
        if (!host.contains(".")) return false;
        return !host.matches("[0-9.]+");
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("chmlfrp", Context.MODE_PRIVATE);
    }

    /** 纯函数版协议规范化，便于离线测试。 */
    public static String applySchemeRaw(String raw, String mode, String proxyType) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() == 0) return "";
        String host = displayHost(s);
        if ("http".equals(mode)) return "http://" + host;
        if ("https".equals(mode)) return "https://" + host;
        if (isWeb(s)) return s;
        if (!looksLikeDomain(host)) return s;      // 节点:端口 之类保持原样
        return ("https".equalsIgnoreCase(proxyType) ? "https://" : "http://") + host;
    }

    /** 按用户设置的协议覆盖，把裸地址规范化成带 scheme 的地址。 */
    public static String applyScheme(Context c, String raw) {
        String mode = prefs(c).getString("endpointScheme", "auto");
        return applySchemeRaw(raw, mode, proxyType(readCurrent(c)));
    }

    /**
     * 统一入口：手动自定义地址 > 面板保存地址 > 配置解析出的第一条。
     * MainActivity 与通知栏共用，保证两处显示一致。
     */
    public static String resolveEndpoint(Context c) {
        SharedPreferences sp = prefs(c);
        String custom = sp.getString("customEndpoint", "").trim();
        String raw = custom.length() > 0 ? custom : sp.getString("tunnelDomain", "").trim();
        if (raw.length() == 0) {
            String[] eps = endpoints(readCurrent(c));
            if (eps.length > 0) raw = eps[0];
        }
        if (raw.length() == 0) return "";
        return applyScheme(c, raw);
    }

    /**
     * 配置里代理段数量（除 [common] 外）。
     * 官方 tunnel_config 支持 tunnel_names=A,B 一次返回多段，
     * 一个 frpc 进程天然能带多个 proxy —— 这就是「启动多条隧道」的实现基础。
     */
    public static int proxyCount(String text) {
        if (text == null) return 0;
        int n = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^\\s*\\[([^\\]]+)\\]\\s*$").matcher(text);
        while (m.find()) {
            String s = m.group(1).trim().toLowerCase();
            if (!s.equals("common") && !s.startsWith("common.")) n++;
        }
        return n;
    }

    /** 配置里所有代理段名，给「多隧道」通知与列表用。 */
    public static String[] proxyNames(String text) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        if (text == null) return new String[0];
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^\\s*\\[([^\\]]+)\\]\\s*$").matcher(text);
        while (m.find()) {
            String s = m.group(1).trim();
            if (!s.toLowerCase().equals("common") && !s.toLowerCase().startsWith("common."))
                out.add(s);
        }
        return out.toArray(new String[0]);
    }

    /** 通知栏用的隧道摘要信息。 */
    public static String summary(Context c) {
        SharedPreferences sp = prefs(c);
        String name = sp.getString("tunnelName", "").trim();
        if (name.length() == 0) name = sp.getString("tunnel", "").trim();
        return name;
    }
}
