package net.lyu323.chmlfrp;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** ChmlFrp v2 API + OAuth2 PKCE 网络工具，零第三方依赖。 */
public final class Net {
    public static final String API = "https://cf-v2.uapis.cn/";
    public static final String AUTHORIZE = "https://account-api.qzhua.net/oauth2/authorize";
    public static final String TOKEN_URL = "https://account-api.qzhua.net/oauth2/token";
    // ChmlFrp 官方 OAuth 的公开 client_id（出现在授权页 URL 中），不是密钥、不是令牌。
    public static final String CLIENT_ID = "019f548df473745dad7f7a0789f6625b";
    public static final String REDIRECT_URI = "chmlerp://oauth/callback";
    public static final String SCOPE = "openid profile email phone offline_access chmlfrp_api";

    private Net() {}

    /** 返回 {code_verifier, code_challenge}，S256。 */
    public static String[] pkce() throws Exception {
        byte[] r = new byte[32];
        new SecureRandom().nextBytes(r);
        String verifier = Base64.encodeToString(r, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII"));
        String challenge = Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        return new String[]{verifier, challenge};
    }

    public static String authorizeUrl(String challenge, String state) throws Exception {
        return AUTHORIZE + "?response_type=code"
                + "&client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8")
                + "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8")
                + "&scope=" + URLEncoder.encode(SCOPE, "UTF-8")
                + "&state=" + URLEncoder.encode(state, "UTF-8")
                + "&code_challenge=" + URLEncoder.encode(challenge, "UTF-8")
                + "&code_challenge_method=S256";
    }

    /** form 为 null 时发 GET，否则发 POST 表单。 */
    public static String request(String url, String bearer, String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "ChmlFrp-Mobile-Launcher/1.0");
        if (bearer != null && bearer.length() > 0) {
            c.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        if (form != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            OutputStream os = c.getOutputStream();
            os.write(form.getBytes("UTF-8"));
            os.close();
        } else {
            c.setRequestMethod("GET");
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = readAll(is);
        c.disconnect();
        if (code >= 400) throw new IllegalStateException("HTTP " + code + "  " + body);
        return body;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    public static String exchangeCode(String code, String verifier) throws Exception {
        String form = "grant_type=authorization_code"
                + "&code=" + URLEncoder.encode(code, "UTF-8")
                + "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8")
                + "&client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8")
                + "&code_verifier=" + URLEncoder.encode(verifier, "UTF-8");
        return request(TOKEN_URL, null, form);
    }

    /** 用 refresh_token 静默换新的 access_token，无需用户再点授权。 */
    public static String refreshAccess(String refreshToken) throws Exception {
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8")
                + "&client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8");
        return request(TOKEN_URL, null, form);
    }

    public static String configUrl(String node, String tunnels) throws Exception {
        String u = Net.API + "tunnel_config?node=" + URLEncoder.encode(node, "UTF-8");
        if (tunnels != null && tunnels.trim().length() > 0) {
            u += "&tunnel_names=" + URLEncoder.encode(tunnels.trim(), "UTF-8");
        }
        return u;
    }
}
