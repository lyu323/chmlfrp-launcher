package net.chmlfrp.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

import java.net.URLEncoder;

/**
 * ChmlFrp 签到中心。
 *
 * 【事实边界，避免编造接口】
 * - 官方文档 docs.chmlfrp.net/API/v2/User_Actions/userinfo.html：
 *   GET/POST /userinfo，参数 token，返回 data.id / username / integral / usergroup / term 等。
 * - 官方文档 docs.chmlfrp.net/API/v1/User_Actions/qdxx.html：
 *   /qdxx.php，参数 userid，返回 is_signed_in_today / total_points /
 *   last_sign_in_time / total_sign_ins / count_of_matching_records。
 *   但文档【未公布 v1 接口域名】，所以 base 由用户配置/自动探测。
 * - 官网明确「可通过每日签到免费获取会员」，签到入口在管理面板。
 * - 官方公开文档【没有给出「执行签到」的端点】，因此一键签到默认走面板，
 *   同时允许用户填自己确认过的端点；App 不会假造一个签到接口。
 */
public final class Sign {
    private Sign() {}

    /** 用户实际在用的控制台首页（浏览器地址栏可见）。 */
    public static final String PANEL_URL = "https://panel.chmlfrp.net/home";
    public static final String PANEL_ALT_URL = "https://panel.chmlfrp.com/";
    public static final String DOCS_URL = "https://docs.chmlfrp.net/API/v1/User_Actions/qdxx.html";

    /**
     * 面板签到前置 QZHUA CAPTCHA 点选验证码（截图里「请依次点击：…」），
     * 控制台还显示「验证码验证[2/3]」。也就是说签到是有人机校验的交互流程，
     * 不是一次裸 GET/POST 能完成的。所以不猜端点、不绕过验证。
     */
    public static final String CAPTCHA_NOTE =
            "面板签到前有 QZHUA CAPTCHA 点选验证码（依次点击图中汉字），"
            + "控制台还提示「验证码验证」。这是人机校验，纯接口调用过不了，"
            + "App 不会也不应该绕过它。";

    /** 仅作为候选，未获官方文档确认；探测成功后会记住可用地址。 */
    public static final String[] V1_CANDIDATES = {
            "https://cf-v2.uapis.cn/",
            "https://panel.chmlfrp.net/",
            "https://panel.chmlfrp.com/",
            "https://cf-v1.uapis.cn/",
            "https://api.chmlfrp.com/",
            "https://www.chmlfrp.net/api/",
            "https://api.chmlfrp.net/",
    };

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("chmlfrp", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------- 用户信息

    public static final class User {
        public String id = "", name = "", group = "", term = "";
        public long integral = 0, up = 0, down = 0;
        public int tunnels = 0, tunnelMax = 0, conns = 0;
        public boolean ok = false;
    }

    /** v2 /userinfo：token 同时走 Bearer 头与 query 参数，兼容两种网关实现。 */
    public static User userInfo(String token) throws Exception {
        String url = Net.API + "userinfo?token=" + URLEncoder.encode(token, "UTF-8");
        String body = Net.request(url, token, null);
        JSONObject o = new JSONObject(body);
        JSONObject d = o.optJSONObject("data");
        User u = new User();
        if (d == null) return u;
        u.ok = true;
        u.id = str(d, "id", "userid", "uid");
        u.name = str(d, "username", "name", "user");
        u.group = str(d, "usergroup", "group", "user_group");
        u.term = str(d, "term", "expire", "vip_expire");
        u.integral = lng(d, "integral", "points", "score");
        u.up = lng(d, "total_upload", "upload");
        u.down = lng(d, "total_download", "download");
        u.tunnels = (int) lng(d, "tunnel", "tunnelstate");
        u.tunnelMax = (int) lng(d, "tunnnelCount", "tunnelCount", "tunnel_count");
        u.conns = (int) lng(d, "totalCurConns", "cur_conns");
        return u;
    }

    // ------------------------------------------------------------- 签到状态

    public static final class Status {
        public boolean ok = false, today = false;
        public long points = 0, todayCount = 0;
        public String last = "", total = "", raw = "", base = "";
    }

    private static String v1Base(Context c) {
        return p(c).getString("signV1Base", "").trim();
    }

    /** 是不是控制台网页地址（常见误填：把浏览器地址栏整条粘进来）。 */
    public static boolean isPanelPageUrl(String s) {
        if (s == null) return false;
        String t = s.toLowerCase();
        return t.contains("panel.chmlfrp")
                || t.contains("/home") || t.contains("/index")
                || t.contains("/user") || t.contains("/dashboard");
    }

    /** 把用户填的东西归一成可拼 qdxx.php 的 base；返回空串表示无法识别。 */
    public static String normalizeBase(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() == 0) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
        if (s.contains("qdxx.php")) s = s.substring(0, s.indexOf("qdxx.php"));
        try {
            java.net.URL u = new java.net.URL(s);
            String port = u.getPort() > 0 ? ":" + u.getPort() : "";
            String path = u.getPath() == null ? "" : u.getPath();
            // 保留 /api、/api/v1 这类前缀，丢掉 /home、/login 这类页面路径
            String keep = "";
            if (path.matches("(?i)/api(/v[0-9]+)?/?")) keep = path.replaceAll("/+$", "");
            String base = u.getProtocol() + "://" + u.getHost() + port + keep;
            return base.endsWith("/") ? base : base + "/";
        } catch (Exception e) { return ""; }
    }

    /** 给「未取得」一个说人话的原因，而不是一句笼统提示。 */
    public static String diagnose(Context c) {
        String raw = v1Base(c);
        if (raw.length() == 0) {
            return "还没配置 v1 接口地址。可点「签到接口设置 → 自动探测」，"
                    + "或直接打开面板看签到信息。";
        }
        if (isPanelPageUrl(raw) && raw.contains("/home")) {
            return "当前填的是控制台网页地址（" + raw + "），不是 API 地址。"
                    + "已自动按域名推导去探测 qdxx.php；若仍失败，说明该域名下没有公开这个接口。";
        }
        String n = normalizeBase(raw);
        return "已按 " + (n.length() > 0 ? n : raw)
                + "qdxx.php 查询但未拿到签到字段。官方 v1 文档只给了路径、没给域名，"
                + "这个端点可能已下线或需要面板会话 Cookie。积分与账号信息仍以 v2 /userinfo 为准。";
    }

    public static String signInfoUrl(Context c, String userId) throws Exception {
        String raw = v1Base(c);
        if (raw.length() == 0) throw new IllegalStateException("未配置 v1 接口地址");
        String base = normalizeBase(raw);
        if (base.length() == 0) throw new IllegalStateException("接口地址格式无法识别：" + raw);
        return base + "qdxx.php?userid=" + URLEncoder.encode(userId, "UTF-8");
    }

    /** 从 query 尝试读取签到状态；若配置了自定义执行端点也一并暴露。 */
    public static Status signInfo(Context c, String token, String userId) throws Exception {
        if (userId.length() == 0) {
            User u = userInfo(token);
            userId = u.id;
        }
        Status s = new Status();
        s.base = v1Base(c);
        String body = Net.request(signInfoUrl(c, userId), token, null);
        return parse(body, s);
    }

    public static Status parse(String body, Status s) {
        s.raw = body == null ? "" : body;
        try {
            JSONObject o = new JSONObject(s.raw);
            // 必须是真的签到字段；404 / 网关错误体也是合法 JSON，不能算「已取得」
            boolean has = o.has("is_signed_in_today") || o.has("total_points")
                    || o.has("last_sign_in_time") || o.has("total_sign_ins")
                    || o.has("count_of_matching_records");
            if (has) {
                s.ok = true;
                s.today = o.optBoolean("is_signed_in_today", false)
                        || o.optBoolean("isSignedInToday", false)
                        || o.optInt("is_signed_in_today", 0) == 1;
                s.points = lng(o, "total_points", "integral", "points");
                s.todayCount = lng(o, "count_of_matching_records", "today_count");
                s.last = str(o, "last_sign_in_time", "lastSignInTime");
                s.total = str(o, "total_sign_ins", "totalSignIns");
            } else {
                s.ok = false;
            }
        } catch (Exception e) {
            s.ok = false;
        }
        if (!s.ok) parseLoose(s);   // v1 接口可能返回非严格 JSON，正则兜底
        return s;
    }

    /** 纯正则解析，不依赖 org.json；字段缺失时按「有该字段即算识别成功」处理。 */
    public static Status parseLoose(Status s) {
        String t = s.raw;
        if (t == null) t = "";
        boolean found = false;
        java.util.regex.Matcher m;
        m = java.util.regex.Pattern.compile(
                "(?i)\"is_signed_in_today\"\\s*:\\s*(true|false|1|0)")
                .matcher(t);
        if (m.find()) {
            found = true;
            s.today = "true".equalsIgnoreCase(m.group(1)) || "1".equals(m.group(1));
        }
        m = java.util.regex.Pattern.compile(
                "(?i)\"total_points\"\\s*:\\s*\"?([0-9]+)").matcher(t);
        if (m.find()) { found = true; s.points = num(m.group(1)); }
        m = java.util.regex.Pattern.compile(
                "(?i)\"count_of_matching_records\"\\s*:\\s*\"?([0-9]+)").matcher(t);
        if (m.find()) { found = true; s.todayCount = num(m.group(1)); }
        m = java.util.regex.Pattern.compile(
                "(?i)\"last_sign_in_time\"\\s*:\\s*\"([^\"]*)\"").matcher(t);
        if (m.find()) { found = true; s.last = m.group(1); }
        m = java.util.regex.Pattern.compile(
                "(?i)\"total_sign_ins\"\\s*:\\s*\"?([^\",}]+)").matcher(t);
        if (m.find()) { found = true; s.total = m.group(1).trim(); }
        s.ok = found;
        return s;
    }

    private static long num(String v) {
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return 0; }
    }

    /** 兼容旧调用：只探测内置候选。 */
    public static String probeBase(String token, String userId) throws Exception {
        return probeBase(token, userId, "");
    }

    /** 供 MainActivity 传入当前已存地址，做「先试用户填的，再试候选」的探测。 */
    public static String probeBase(String token, String userId, String currentBase) {
        java.util.LinkedHashSet<String> tries = new java.util.LinkedHashSet<String>();
        String n = normalizeBase(currentBase);
        if (n.length() > 0) {
            tries.add(n);
            // 面板网页地址额外推导 /api、/api/v1 前缀
            if (isPanelPageUrl(currentBase)) {
                try {
                    java.net.URL u = new java.net.URL(n);
                    String root = u.getProtocol() + "://" + u.getHost() + "/";
                    tries.add(root + "api/");
                    tries.add(root + "api/v1/");
                } catch (Exception ignored) {}
            }
        }
        for (String b : V1_CANDIDATES) {
            String nb = normalizeBase(b);
            if (nb.length() > 0) tries.add(nb);
        }
        if (userId.length() == 0) userId = "0";
        for (String b : tries) {
            try {
                String url = b + "qdxx.php?userid=" + URLEncoder.encode(userId, "UTF-8");
                String body = Net.request(url, token, null);
                if (looksLikeSignBody(body)) return b;
            } catch (Exception ignored) {}
        }
        return "";
    }

    private static boolean looksLikeSignBody(String b) {
        if (b == null) return false;
        String t = b.toLowerCase();
        return t.contains("is_signed_in_today") || t.contains("total_points")
                || t.contains("last_sign_in_time") || t.contains("total_sign_ins");
    }

    // ------------------------------------------------------------- 一键签到

    /** 用户自己确认的执行端点（官方未公开，默认留空）。 */
    public static String doSignEndpoint(Context c) {
        return p(c).getString("signDoEndpoint", "").trim();
    }

    /**
     * 执行签到：仅当用户填了自定义端点才发请求，否则返回 null 让调用方打开面板。
     * 不猜接口路径，避免误发请求。
     */
    public static String doSign(Context c, String token, String userId) throws Exception {
        String ep = doSignEndpoint(c);
        if (ep.length() == 0) return null;
        if (!ep.startsWith("http")) return null;
        String url = ep.replace("{userid}", URLEncoder.encode(userId, "UTF-8"))
                       .replace("{token}", URLEncoder.encode(token, "UTF-8"));
        return Net.request(url, token, null);
    }

    public static Uri panelUri() { return Uri.parse(PANEL_URL); }

    // ------------------------------------------------------------- 每日提醒

    private static String todayKey() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                "yyyy-MM-dd", java.util.Locale.CHINA);
        return f.format(new java.util.Date());
    }

    public static boolean shouldRemind(Context c) {
        if (!p(c).getBoolean("signRemind", true)) return false;
        return !todayKey().equals(p(c).getString("signLastRemind", ""));
    }

    public static void markReminded(Context c) {
        p(c).edit().putString("signLastRemind", todayKey()).commit();
    }

    // ------------------------------------------------------------- 本地趋势（v2.6）

    private static String dayKey(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                "yyyy-MM-dd", java.util.Locale.CHINA);
        return f.format(new java.util.Date(ms));
    }

    private static String shortDay(String k) {
        return k.length() >= 10 ? k.substring(5) : k;
    }

    /** 记录今天积分；同一天覆盖，只保留最近 14 天。 */
    public static void recordPoints(Context c, long points) {
        if (points <= 0) return;
        String k = dayKey(System.currentTimeMillis());
        SharedPreferences sp = p(c);
        String raw = sp.getString("signPointsHistory", "");
        java.util.LinkedHashMap<String, Long> map = parseHistory(raw);
        map.put(k, points);
        while (map.size() > 14) {
            map.remove(map.keySet().iterator().next());
        }
        sp.edit().putString("signPointsHistory", encodeHistory(map)).commit();
    }

    /**
     * 返回最近 7 天积分变化 + 一句基于积分变化的签到推断。
     * 明确标注「本地推断」：官方 v1 状态取不到时，这是个有用的旁证，不是签到状态本身。
     */
    public static String trend(Context c) {
        java.util.LinkedHashMap<String, Long> map = parseHistory(
                p(c).getString("signPointsHistory", ""));
        if (map.isEmpty()) return "本地积分记录：暂无（多查几次就会攒出趋势）";
        StringBuilder sb = new StringBuilder("──── 本地积分趋势 ────\n");
        java.util.ArrayList<String> keys = new java.util.ArrayList<String>(map.keySet());
        int from = Math.max(0, keys.size() - 7);
        long prev = -1;
        for (int i = from; i < keys.size(); i++) {
            String k = keys.get(i);
            long v = map.get(k);
            sb.append(shortDay(k)).append("  ").append(v);
            if (prev >= 0 && v != prev) {
                long d = v - prev;
                sb.append(d > 0 ? "  (+" + d + ")" : "  (" + d + ")");
            }
            sb.append('\n');
            prev = v;
        }
        if (keys.size() >= 2) {
            String today = dayKey(System.currentTimeMillis());
            Long t = map.get(today);
            String yk = keys.get(keys.size() - 2);
            Long y = map.get(yk);
            if (t != null && y != null && t > y) {
                sb.append("提示：今日积分较上次 +").append(t - y)
                  .append("，可能已完成签到（本地推断，非官方状态）。");
            } else if (t != null && y != null && t.equals(y)) {
                sb.append("提示：今日积分暂无变化，签到状态请以面板「签到信息」为准。");
            }
        }
        return sb.toString().trim();
    }

    /** 用户点过「去签到」就本地画一格，纯本机记录，不代表服务端已签到。 */
    public static void markVisit(Context c) {
        String k = dayKey(System.currentTimeMillis());
        SharedPreferences sp = p(c);
        String raw = sp.getString("signVisits", "");
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
        for (String s : raw.split(",")) if (s.trim().length() > 0) set.add(s.trim());
        set.add(k);
        while (set.size() > 14) set.remove(set.iterator().next());
        StringBuilder b = new StringBuilder();
        for (String s : set) { if (b.length() > 0) b.append(','); b.append(s); }
        sp.edit().putString("signVisits", b.toString()).commit();
    }

    public static String visitGrid(Context c) {
        String raw = p(c).getString("signVisits", "");
        java.util.HashSet<String> set = new java.util.HashSet<String>();
        for (String s : raw.split(",")) if (s.trim().length() > 0) set.add(s.trim());
        StringBuilder sb = new StringBuilder("最近 7 天打开签到页：");
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                "MM-dd", java.util.Locale.CHINA);
        for (int i = 6; i >= 0; i--) {
            String k = dayKey(System.currentTimeMillis() - i * 86400000L);
            sb.append(set.contains(k) ? "●" : "○");
            if (i == 0) sb.append(" 今天");
        }
        return sb.append("\n（本机打点，仅记录你点过「去签到」，不代表服务端已签到）")
                .toString();
    }

    private static java.util.LinkedHashMap<String, Long> parseHistory(String raw) {
        java.util.LinkedHashMap<String, Long> m = new java.util.LinkedHashMap<String, Long>();
        if (raw == null) return m;
        for (String seg : raw.split(",")) {
            if (seg.trim().length() == 0) continue;
            String[] kv = seg.split("=");
            if (kv.length != 2) continue;
            try { m.put(kv[0], Long.parseLong(kv[1])); } catch (Exception ignored) {}
        }
        return m;
    }

    private static String encodeHistory(java.util.LinkedHashMap<String, Long> m) {
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, Long> e : m.entrySet()) {
            if (b.length() > 0) b.append(',');
            b.append(e.getKey()).append('=').append(e.getValue());
        }
        return b.toString();
    }

    // ------------------------------------------------------------- 工具

    private static String str(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            String s = String.valueOf(v).trim();
            if (s.length() > 0 && !"null".equalsIgnoreCase(s)) return s;
        }
        return "";
    }

    private static long lng(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            try { return Long.parseLong(String.valueOf(v).trim()); }
            catch (Exception ignored) {}
        }
        return 0;
    }
}
