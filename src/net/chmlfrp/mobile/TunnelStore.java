package net.chmlfrp.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 全部隧道列表（v2.8）。
 *
 * 官方 /tunnel 返回的是数组，旧版只挑一条存进 sp，其余全丢。
 * 这里把整个列表留在本机，支持：
 *   · 显示所有隧道（名称 / 节点 / 类型 / 域名 / 在线状态 / 连接数）
 *   · 多选后用 tunnel_names=A,B 一次拉取合并配置 —— 官方 API 原生支持，
 *     一个 frpc 进程天然能带多个 proxy 段，不需要起多个进程。
 *
 * 只存本机，不上传，不改动任何网络端点。
 */
public final class TunnelStore {
    private TunnelStore() {}

    public static final class T {
        public String name = "", id = "", node = "", type = "";
        public String localIp = "", localPort = "", domain = "", conn = "";
        public String online = "", nodestate = "", time = "";
        public JSONObject raw;

        public String title() {
            return (name.length() > 0 ? name : "未命名隧道")
                    + (onlineLooksUp() ? "  ● 在线" : "  ○ 离线");
        }
        public boolean onlineLooksUp() {
            String s = (online + " " + nodestate).toLowerCase();
            return s.contains("online") || s.contains("true") || s.contains("1")
                    || s.contains("运行") || s.contains("正常");
        }
        public String sub() {
            StringBuilder b = new StringBuilder();
            b.append(node.length() > 0 ? node : "节点 —");
            if (type.length() > 0) b.append(" · ").append(type);
            if (localIp.length() > 0 && localPort.length() > 0)
                b.append(" · ").append(localIp).append(':').append(localPort);
            if (domain.length() > 0) b.append('\n').append("   ").append(domain);
            if (conn.length() > 0) b.append(" · 连接 ").append(conn);
            return b.toString();
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("chmlfrp", Context.MODE_PRIVATE);
    }

    public static String pick(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && v.length() > 0 && !"null".equals(v)) return v;
        }
        return "";
    }

    public static List<T> parse(String json) {
        List<T> out = new ArrayList<T>();
        if (json == null) return out;
        String j = json.trim();
        try {
            if (j.startsWith("[")) {
                // 顶层就是数组
                addArray(new JSONArray(j), out);
            } else {
                JSONObject root = new JSONObject(j);
                // ① 顶层任意数组字段：{tunnels:[...]} / {list:[...]} / {records:[...]}
                java.util.Iterator<String> it = root.keys();
                while (it.hasNext()) {
                    Object v = root.opt(it.next());
                    if (v instanceof JSONArray) addArray((JSONArray) v, out);
                }
                // ② data 是对象：{data:{list:[...],total:N}} —— 官方 v2 常见形态
                JSONObject d = root.optJSONObject("data");
                if (d != null) {
                    java.util.Iterator<String> it2 = d.keys();
                    while (it2.hasNext()) {
                        Object v = d.opt(it2.next());
                        if (v instanceof JSONArray) addArray((JSONArray) v, out);
                    }
                }
                // ③ data 里再嵌一层 data
                if (d != null) {
                    JSONObject d2 = d.optJSONObject("data");
                    if (d2 != null) {
                        java.util.Iterator<String> it3 = d2.keys();
                        while (it3.hasNext()) {
                            Object v = d2.opt(it3.next());
                            if (v instanceof JSONArray) addArray((JSONArray) v, out);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        // 非严格 JSON / org.json 不可用时用正则兜底
        if (out.isEmpty()) out = parseLoose(j);
        return dedup(out);
    }

    private static String regex1(String s, String re) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile(re, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
            return m.find() ? m.group(1).trim() : "";
        } catch (Exception e) { return ""; }
    }

    private static int count(String s, char ch) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == ch) n++;
        return n;
    }

    private static void addArray(JSONArray a, List<T> out) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) {
                T t = fromObject(o);
                if (t.name.length() > 0) out.add(t);
            } else {
                // 有些端点直接给名字数组 ["A","B"]
                String n = a.optString(i, "");
                if (n.length() > 0 && n.length() < 64) {
                    T t = new T();
                    t.name = n;
                    out.add(t);
                }
            }
        }
    }

    /** 多入口遍历可能重复，按名字去重，保持原顺序。 */
    private static List<T> dedup(List<T> in) {
        List<T> out = new ArrayList<T>();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for (T t : in) {
            if (seen.add(t.name)) out.add(t);
        }
        return out;
    }

    /**
     * 原始响应诊断：解析出 0 条时把接口到底返回了什么摊开给用户看，
     * 而不是丢一个「0 条」的空弹窗（v2.8 图21 的问题）。
     */
    public static String diagnose(String json) {
        if (json == null || json.trim().length() == 0)
            return "响应为空：接口没返回内容（检查网络 / Token / 节点状态）";
        String j = json.trim();
        StringBuilder b = new StringBuilder();
        // 纯正则：即使 JSON 解析失败 / org.json 不可用，也要把关键信息摊开
        String code = regex1(j, "\"code\"\\s*:\\s*\"?([^\\\",}\\s]+)\"?");
        String msg = regex1(j, "\"(?:msg|message)\"\\s*:\\s*\"([^\"]*)\"");
        if (msg.length() > 80) msg = msg.substring(0, 80) + "…";
        b.append("code  ").append(code.length() > 0 ? code : "(无)")
         .append("   msg  ").append(msg.length() > 0 ? msg : "(无)").append('\n');
        java.util.regex.Matcher dm = java.util.regex.Pattern
                .compile("\"data\"\\s*:\\s*([\\[\\{])").matcher(j);
        if (dm.find()) {
            if ("[".equals(dm.group(1))) {
                int objects = count(j, '{');
                b.append("data  数组 · 约 ").append(Math.max(0, objects - 1))
                 .append(" 个对象\\n");
            } else {
                b.append("data  对象（内嵌数组请向下看原文）\\n");
            }
        } else {
            b.append("data  未找到数组字段\\n");
        }
        b.append("长度  ").append(j.length()).append(" 字符")
         .append("   ·   含 name 字段：").append(j.contains("\"name\"") ? "是" : "否")
         .append('\n');
        b.append("解析结果  ").append(parse(j).size()).append(" 条隧道\n\n");
        b.append("原文预览（前 420 字）：\n")
         .append(j.substring(0, Math.min(420, j.length())));
        if (j.length() > 420) b.append("…");
        return b.toString();
    }

    private static T fromObject(JSONObject o) {
        T t = new T();
        t.raw = o;
        t.name = pick(o, "name", "tunnel_name", "tunnelname");
        t.id = pick(o, "id", "proxyid", "proxy_id", "tunnel_id", "tunnelid");
        t.node = pick(o, "node", "nodename", "node_name");
        t.type = pick(o, "type", "protocol");
        t.localIp = pick(o, "localip", "local_ip", "localIP");
        t.localPort = pick(o, "nport", "localport", "local_port", "localPort");
        t.domain = pick(o, "domain", "connectaddress", "connect_address",
                "connect", "url", "customdomain", "custom_domains", "customDomains");
        t.conn = pick(o, "conn", "connections", "connectnum",
                "connect_count", "connection");
        t.online = pick(o, "online", "state", "status", "isOnline");
        t.nodestate = pick(o, "nodestate");
        t.time = pick(o, "time", "lasttime", "createtime", "updated_at", "date");
        return t;
    }

    /** 纯字符串兜底：按 },{ 切分对象块，逐块取字段。不依赖 org.json。 */
    private static List<T> parseLoose(String json) {
        List<T> out = new ArrayList<T>();
        int s = json.indexOf('['), e = json.lastIndexOf(']');
        if (s < 0 || e <= s) return out;
        String body = json.substring(s + 1, e);
        // 纯字符串数组：{"data":["A","B","C"]} 或 ["A","B"]
        if (body.indexOf('{') < 0) {
            java.util.regex.Matcher sm = java.util.regex.Pattern
                    .compile("\"([^\"]{1,63})\"").matcher(body);
            while (sm.find()) {
                String nm = sm.group(1).trim();
                if (nm.length() == 0) continue;
                T t = new T();
                t.name = nm;
                out.add(t);
            }
            return dedup(out);
        }
        // 若数组里套了对象数组（如 {"list":[{...}]}），截到最内层对象块
        int inner = body.lastIndexOf("[{");
        if (inner >= 0) body = body.substring(inner + 1);
        for (String blk : body.split("\\}\\s*,\\s*\\{")) {
            String obj = blk.startsWith("{") ? blk : "{" + blk;
            if (!obj.endsWith("}")) obj = obj + "}";
            T t = new T();
            t.name = fld(obj, "name", "tunnel_name", "tunnelname");
            t.id = fld(obj, "id", "proxyid", "proxy_id", "tunnel_id");
            t.node = fld(obj, "node", "nodename", "node_name");
            t.type = fld(obj, "type", "protocol");
            t.localIp = fld(obj, "localip", "local_ip", "localIP");
            t.localPort = fld(obj, "nport", "localport", "local_port", "localPort");
            t.domain = fld(obj, "domain", "connectaddress", "connect_address",
                    "custom_domains", "customDomains");
            t.conn = fld(obj, "conn", "connections", "connect_count");
            t.online = fld(obj, "online", "state", "status");
            t.nodestate = fld(obj, "nodestate");
            t.time = fld(obj, "time", "lasttime", "createtime");
            if (t.name.length() > 0) out.add(t);
        }
        return out;
    }

    /** 从 JSON 对象文本里取字段，兼容 "k":"v" / "k":123 / "k":true。 */
    private static String fld(String obj, String... keys) {
        for (String k : keys) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "\"" + k + "\"\\s*:\\s*(?:\"([^\"]*)\"|([^,\\}\\s]+))",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(obj);
            if (m.find()) {
                String v = m.group(1) != null ? m.group(1) : m.group(2);
                if (v != null && !"null".equals(v) && v.length() > 0) return v.trim();
            }
        }
        return "";
    }

    public static void save(Context c, String json) {
        sp(c).edit().putString("tunnelListJson", json)
                .putLong("tunnelListAt", System.currentTimeMillis()).commit();
    }

    public static String raw(Context c) { return sp(c).getString("tunnelListJson", ""); }
    public static long savedAt(Context c) { return sp(c).getLong("tunnelListAt", 0); }

    public static List<T> load(Context c) { return parse(raw(c)); }

    /** 多选名字拼成官方 tunnel_names 参数。 */
    public static String namesParam(List<T> all, boolean[] picked) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < all.size() && i < picked.length; i++) {
            if (!picked[i] || all.get(i).name.length() == 0) continue;
            if (b.length() > 0) b.append(',');
            b.append(all.get(i).name);
        }
        return b.toString();
    }

    /** 多选里出现最多的节点（官方 configUrl 只接受一个 node）。 */
    public static String commonNode(List<T> all, boolean[] picked) {
        java.util.LinkedHashMap<String, Integer> m =
                new java.util.LinkedHashMap<String, Integer>();
        for (int i = 0; i < all.size() && i < picked.length; i++) {
            if (!picked[i]) continue;
            String n = all.get(i).node;
            if (n.length() == 0) continue;
            Integer v = m.get(n);
            m.put(n, v == null ? 1 : v + 1);
        }
        String best = "";
        int cnt = 0;
        for (java.util.Map.Entry<String, Integer> e : m.entrySet()) {
            if (e.getValue() > cnt) { cnt = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    /** 复制用的纯文本清单。 */
    public static String asText(List<T> all) {
        StringBuilder b = new StringBuilder("ChmlFrp 隧道清单（本机缓存）\n");
        if (all.isEmpty()) return b.append("（暂无）").toString();
        for (int i = 0; i < all.size(); i++) {
            T t = all.get(i);
            b.append(i + 1).append(". ").append(t.title()).append('\n')
             .append("   ").append(t.sub().replace('\n', ' ')).append('\n');
        }
        return b.toString();
    }
}
