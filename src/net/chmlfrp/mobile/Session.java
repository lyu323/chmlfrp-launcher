package net.chmlfrp.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * 登录会话：登录一次，之后自动续期，不用再点 OAuth。
 *
 * - access_token / refresh_token / 到期时间都持久化到本机
 * - 每次 App 启动调 ensure()：没过期直接用，快过期或已过期就用 refresh_token
 *   静默换新；新响应里带回新的 refresh_token 就轮换保存
 * - 用户手动填写的令牌（没有 refresh_token）保持原样，不尝试刷新
 */
public final class Session {
    private Session() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("chmlfrp", Context.MODE_PRIVATE);
    }

    /** 保存一次 OAuth 结果；expiresIn 单位秒，缺省按 30 天。 */
    public static void save(Context c, JSONObject o) {
        String at = o.optString("access_token", "");
        if (at.length() == 0) return;
        String rt = o.optString("refresh_token", "");
        long expires = o.optLong("expires_in", 0L);
        if (expires <= 0) expires = 2592000L;   // 服务端没给就按 30 天
        long expiryAt = System.currentTimeMillis() + expires * 1000L;
        String oldRt = p(c).getString("refreshToken", "");
        p(c).edit()
             .putString("accessToken", at)
             .putString("refreshToken", rt.length() > 0 ? rt : oldRt)
             .putLong("tokenExpiry", expiryAt)
             .putString("tokenType", o.optString("token_type", "Bearer"))
             .putBoolean("oauthSession", true)
             .commit();
    }

    /** 手动填的令牌：不参与自动续期，但仍持久化。 */
    public static void saveManual(Context c, String token) {
        p(c).edit().putString("accessToken", token)
             .remove("refreshToken").remove("tokenExpiry")
             .putBoolean("oauthSession", false).commit();
    }

    public static String token(Context c) {
        return p(c).getString("accessToken", "");
    }

    public static String refreshToken(Context c) {
        return p(c).getString("refreshToken", "");
    }

    public static boolean isOauth(Context c) {
        return p(c).getBoolean("oauthSession", false);
    }

    public static long expiry(Context c) {
        return p(c).getLong("tokenExpiry", 0L);
    }

    /** 提前 5 分钟续期，避免边界时刻刚好过期。 */
    public static boolean needsRefresh(Context c) {
        if (!isOauth(c) || refreshToken(c).length() == 0) return false;
        long exp = expiry(c);
        return exp <= 0 || System.currentTimeMillis() > exp - 300_000L;
    }

    public static boolean loggedIn(Context c) {
        return token(c).trim().length() > 0;
    }

    /** 返回 {是否可用, 状态文案}；静默刷新失败时保留旧 token 继续尝试。 */
    public static String[] ensure(Context c) {
        String t = token(c);
        if (t.length() == 0) return new String[]{"false", "未登录"};
        if (!needsRefresh(c)) {
            return new String[]{"true", isOauth(c) ? "已登录 · 自动续期" : "手动令牌"};
        }
        try {
            String r = Net.refreshAccess(refreshToken(c));
            JSONObject o = new JSONObject(r);
            String at = o.optString("access_token", "");
            if (at.length() == 0) {
                return new String[]{"true", "续期未返回新令牌，沿用现有登录"};
            }
            save(c, o);
            return new String[]{"true", "已自动续期，无需重新登录"};
        } catch (Exception e) {
            // 旧 token 可能还有效，不清除；只标记需要留意
            return new String[]{"true", "自动续期失败，沿用现有登录：" + shortMsg(e)};
        }
    }

    private static String shortMsg(Exception e) {
        String m = String.valueOf(e.getMessage());
        return m.length() > 60 ? m.substring(0, 60) + "…" : m;
    }

    public static void clear(Context c) {
        p(c).edit().remove("accessToken").remove("refreshToken")
             .remove("tokenExpiry").remove("tokenType")
             .putBoolean("oauthSession", false)
             .remove("token").remove("verifier").remove("state").commit();
    }

    /** 给用户看的到期描述。 */
    public static String expiryLabel(Context c) {
        long exp = expiry(c);
        if (!isOauth(c) || exp <= 0) return "";
        long d = exp - System.currentTimeMillis();
        if (d <= 0) return "已到期（将自动续期）";
        long days = d / 86400000L;
        if (days >= 1) return "有效期约 " + days + " 天";
        long hours = d / 3600000L;
        return hours >= 1 ? "有效期约 " + hours + " 小时" : "有效期不足 1 小时";
    }
}
