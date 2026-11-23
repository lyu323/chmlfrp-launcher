package net.lyu323.chmlfrp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 多账号本地档案（v2.8「其他登录功能」）。
 *
 * 除 OAuth 授权外，提供：粘贴 Access Token 直登、从文本/配置导入令牌、
 * 多账号保存与切换、退出登录。所有档案只存在本机 SharedPreferences，
 * 不上传、不联网校验除官方 API 之外的任何地址。
 *
 * 注意：这里不实现「账号密码登录」—— ChmlFrp 官方未公开密码登录端点，
 * 猜端点去发账号密码既不安全也不负责任。
 */
public final class AccountStore {
    private AccountStore() {}

    public static final class A {
        public String label = "", token = "", refresh = "", userId = "", userName = "";
        public long addedAt = 0;
        public String shortToken() {
            return token.length() > 8 ? token.substring(0, 8) + "…" : token;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("chmlfrp", Context.MODE_PRIVATE);
    }

    public static List<A> list(Context c) {
        List<A> out = new ArrayList<A>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("accounts", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                A x = new A();
                x.label = o.optString("label", "");
                x.token = o.optString("token", "");
                x.refresh = o.optString("refresh", "");
                x.userId = o.optString("uid", "");
                x.userName = o.optString("uname", "");
                x.addedAt = o.optLong("at", 0);
                if (x.token.length() > 0) out.add(x);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void write(Context c, List<A> l) {
        JSONArray a = new JSONArray();
        for (A x : l) {
            try {
                JSONObject o = new JSONObject();
                o.put("label", x.label); o.put("token", x.token);
                o.put("refresh", x.refresh); o.put("uid", x.userId);
                o.put("uname", x.userName); o.put("at", x.addedAt);
                a.put(o);
            } catch (Exception ignored) {}
        }
        sp(c).edit().putString("accounts", a.toString()).commit();
    }

    /** 保存/更新当前档案；同 token 覆盖，避免重复。返回档案数。 */
    public static int saveCurrent(Context c, String label) {
        SharedPreferences p = sp(c);
        String token = p.getString("token", "").trim();
        if (token.length() == 0) return list(c).size();
        List<A> l = list(c);
        for (A x : l) {
            if (x.token.equals(token)) {
                x.label = label.length() > 0 ? label : x.label;
                x.refresh = p.getString("refreshToken", "");
                x.userId = p.getString("signUserId", "");
                x.userName = p.getString("signUserName", "");
                write(c, l);
                p.edit().putString("accountLabel", x.label).commit();
                return l.size();
            }
        }
        A x = new A();
        x.token = token;
        x.refresh = p.getString("refreshToken", "");
        x.userId = p.getString("signUserId", "");
        x.userName = p.getString("signUserName", "");
        x.addedAt = System.currentTimeMillis();
        x.label = label.length() > 0 ? label
                : (x.userName.length() > 0 ? x.userName : "账号 " + (l.size() + 1));
        l.add(x);
        write(c, l);
        p.edit().putString("accountLabel", x.label).commit();
        return l.size();
    }

    public static void remove(Context c, int idx) {
        List<A> l = list(c);
        if (idx < 0 || idx >= l.size()) return;
        l.remove(idx);
        write(c, l);
    }

    /** 切换到某个档案：把 token/refresh 写回当前会话字段。 */
    public static boolean switchTo(Context c, int idx) {
        List<A> l = list(c);
        if (idx < 0 || idx >= l.size()) return false;
        A x = l.get(idx);
        sp(c).edit().putString("token", x.token)
                .putString("refreshToken", x.refresh)
                .putString("signUserId", x.userId)
                .putString("signUserName", x.userName)
                .putString("accountLabel", x.label)
                .putString("tokenVerify", "")
                .putBoolean("refreshInvalid", false)
                .putLong("tokenExpires", 0).commit();
        return true;
    }

    /** 从任意文本里提取 token：支持纯 token / INI 的 token= / JSON 的 access_token。 */
    public static String extractToken(String s) {
        if (s == null) return "";
        String t = s.trim();
        // 纯正则，不依赖 org.json：JSON / INI / 裸令牌三种格式一次覆盖
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?im)(?:\\\"(?:access_)?token\\\"\\s*:\\s*\\\"|"
              + "^\\s*(?:access_)?token\\s*[=:]\\s*[\"']?)"
              + "([A-Za-z0-9._\\-]{8,})")
                .matcher(t);
        if (m.find()) return m.group(1).trim();
        // 整段就是令牌（去掉引号与空白）
        String bare = t.replaceAll("[\"'\\s]", "");
        return bare.matches("[A-Za-z0-9._\\-]{8,}") ? bare : "";
    }

    /** 退出登录：清当前会话，但保留档案方便切回。 */
    public static void logout(Context c) {
        sp(c).edit().putString("token", "").putString("refreshToken", "")
                .putString("tokenVerify", "").putBoolean("refreshInvalid", false)
                .putLong("tokenExpires", 0).putString("accountLabel", "").commit();
    }
}
