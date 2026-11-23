package net.lyu323.chmlfrp;

import android.os.Build;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 连接诊断：从客户端复现浏览器访问失败的原因。
 *
 * 重点识别 ERR_SSL_UNRECOGNIZED_NAME_ALERT（TLS alert 112）：
 * 服务端没有为 ClientHello 里的 SNI 名字配置站点，握手在发证书之前就断了。
 * 这属于节点/面板侧配置，客户端无法"修好"，但能给出可执行的排查方向。
 */
public final class Diagnose {
    private Diagnose() {}

    public static final class Result {
        public boolean ok;
        public boolean unrecognizedName;
        public String report = "";
        public String advice = "";
    }

    public static Result check(String endpoint, String nodeAddr) {
        Result r = new Result();
        StringBuilder sb = new StringBuilder();
        String host = "", scheme = "https";
        int port = 443;
        try {
            String s = endpoint == null ? "" : endpoint.trim();
            if (s.startsWith("https://")) { scheme = "https"; s = s.substring(8); }
            else if (s.startsWith("http://")) { scheme = "http"; port = 80; s = s.substring(7); }
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            int colon = s.lastIndexOf(':');
            if (colon > 0 && s.substring(colon + 1).matches("[0-9]+")) {
                port = Integer.parseInt(s.substring(colon + 1));
                s = s.substring(0, colon);
            }
            host = s;
        } catch (Exception e) {
            r.report = "地址解析失败：" + e;
            r.advice = "请检查连接地址格式。";
            return r;
        }
        if (host.length() == 0) {
            r.report = "连接地址为空";
            r.advice = "先拉取配置，或在设置里手动填连接地址。";
            return r;
        }

        sb.append("目标   ").append(scheme).append("://").append(host)
          .append(':').append(port).append('\n');

        // 1) DNS
        List<String> ips = new ArrayList<String>();
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            for (InetAddress a : all) ips.add(a.getHostAddress());
            sb.append("DNS   ✓ 解析到 ").append(ips.size()).append(" 个地址\n");
            for (int i = 0; i < Math.min(ips.size(), 3); i++) {
                sb.append("      ").append(ips.get(i)).append('\n');
            }
        } catch (Exception e) {
            sb.append("DNS   ✗ 解析失败：").append(e.getMessage()).append('\n');
            r.advice = "域名解析不到：检查面板里自定义域名是否填对、DNS 是否已生效。";
            r.report = sb.toString();
            return r;
        }

        // 2) TCP
        Socket probe = new Socket();
        try {
            probe.connect(new InetSocketAddress(host, port), 4000);
            sb.append("TCP   ✓ :").append(port).append(" 可连接\n");
        } catch (Exception e) {
            sb.append("TCP   ✗ :").append(port).append(" 连不上：")
              .append(e.getClass().getSimpleName()).append('\n');
            r.advice = "端口不通：确认隧道在面板显示在线，或换一个节点。";
            r.report = sb.toString();
            try { probe.close(); } catch (Exception ignored) {}
            return r;
        } finally {
            try { probe.close(); } catch (Exception ignored) {}
        }

        // 3) TLS / HTTP
        if ("http".equals(scheme)) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(endpoint).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(6000);
                c.setInstanceFollowRedirects(false);
                int code = c.getResponseCode();
                sb.append("HTTP  ✓ 返回 ").append(code).append('\n');
                r.ok = code < 500;
                if (code == 404 || code == 502 || code == 503) {
                    r.advice = "端口通但返回 " + code
                            + "：隧道大概率已连上，检查本地 8080 服务是否启动、"
                            + "以及面板域名是否绑定到这条隧道。";
                } else if (!r.ok) {
                    r.advice = "服务端返回 " + code + "，属于节点侧错误，可换节点再试。";
                } else {
                    r.advice = "HTTP 访问正常。";
                }
            } catch (Exception e) {
                sb.append("HTTP  ✗ ").append(e.getClass().getSimpleName())
                  .append(' ').append(e.getMessage()).append('\n');
                r.advice = "HTTP 请求失败，请看上方错误或换节点。";
            } finally { if (c != null) c.disconnect(); }
            r.report = sb.toString();
            return r;
        }

        // HTTPS：TLS 握手（自带 SNI = host）
        SSLSocket s = null;
        try {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            s = (SSLSocket) f.createSocket(host, port);
            s.setSoTimeout(6000);
            s.startHandshake();
            SSLSession ss = s.getSession();
            String peer = ss.getPeerHost();
            String proto = ss.getProtocol();
            String cipher = ss.getCipherSuite();
            sb.append("TLS   ✓ 握手成功 · ").append(proto).append('\n');
            sb.append("      SNI=").append(peer).append('\n');
            sb.append("      ").append(cipher).append('\n');
            try {
                X509Certificate cert = (X509Certificate) ss.getPeerCertificates()[0];
                sb.append("      证书 ").append(shortDn(cert.getSubjectX500Principal().getName()))
                  .append('\n');
            } catch (Exception ignored) {}
            r.ok = true;
            r.advice = "TLS 握手正常。若浏览器仍报错，多为证书不被信任（不是本错误）。";
        } catch (SSLHandshakeException e) {
            String msg = String.valueOf(e.getMessage());
            boolean unrec = msg.toLowerCase().contains("unrecognized_name")
                    || msg.toLowerCase().contains("unrecognized name")
                    || msg.contains("112");
            sb.append("TLS   ✗ 握手失败：").append(msg).append('\n');
            if (unrec) {
                r.unrecognizedName = true;
                sb.append("      = ERR_SSL_UNRECOGNIZED_NAME_ALERT (alert 112)\n");
            }
            r.advice = unrec ? unrecognizedAdvice(host, nodeAddr, ips)
                             : "TLS 握手失败：" + msg + "\n可换节点，或把隧道类型改成 http 再试。";
        } catch (Exception e) {
            sb.append("TLS   ✗ ").append(e.getClass().getSimpleName())
              .append(' ').append(e.getMessage()).append('\n');
            r.advice = "TLS 探测异常，可换节点或改用 http 打开。";
        } finally {
            if (s != null) try { s.close(); } catch (IOException ignored) {}
        }

        // 4) 对照：换一个 SNI 名试试，判断是"名字没配"还是"整台不认"
        if (r.unrecognizedName && nodeAddr != null && nodeAddr.trim().length() > 0
                && Build.VERSION.SDK_INT >= 24) {
            String alt = nodeAddr.trim();
            if (!alt.equalsIgnoreCase(host)) {
                sb.append("对照   用节点名 ").append(alt).append(" 作 SNI：")
                  .append(trySni(ips.isEmpty() ? host : ips.get(0), port, alt) ? "✓ 通" : "✗ 同样失败")
                  .append('\n');
            }
        }
        r.report = sb.toString();
        return r;
    }

    private static boolean trySni(String ip, int port, String sni) {
        SSLSocket s = null;
        try {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            s = (SSLSocket) f.createSocket();
            s.connect(new InetSocketAddress(ip, port), 4000);
            s.setSoTimeout(4000);
            if (Build.VERSION.SDK_INT >= 24) {
                // 反射设置 SNI，避免 minSdk 23 直接引用 API 24 的 SNIHostName
                try {
                    SSLParameters p = s.getSSLParameters();
                    Class<?> cls = Class.forName("javax.net.ssl.SNIHostName");
                    Object name = cls.getConstructor(String.class).newInstance(sni);
                    java.lang.reflect.Method m = SSLParameters.class
                            .getMethod("setServerNames", java.util.List.class);
                    m.invoke(p, java.util.Collections.singletonList(name));
                    s.setSSLParameters(p);
                } catch (Throwable ignored) {}
            }
            s.startHandshake();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static String shortDn(String dn) {
        int i = dn.indexOf("CN=");
        if (i >= 0) {
            String s = dn.substring(i + 3);
            int c = s.indexOf(',');
            return "CN=" + (c > 0 ? s.substring(0, c) : s);
        }
        return dn.length() > 45 ? dn.substring(0, 45) + "…" : dn;
    }

    private static String unrecognizedAdvice(String host, String nodeAddr, List<String> ips) {
        return "✗ 已复现 ERR_SSL_UNRECOGNIZED_NAME_ALERT（TLS alert 112）\n\n"
             + "含义：浏览器在 ClientHello 里带的 SNI = " + host + "，\n"
             + "节点上没有为这个名字配置 TLS 站点/证书，握手在「发证书之前」就被拒了。\n"
             + "这是 ChmlFrp 节点/面板侧的配置，不是启动器、也不是你手机的故障，App 无法单方面修好。\n\n"
             + "按顺序试：\n"
             + "1. 把隧道类型从 https 改成 http（由节点统一终止 HTTPS），\n"
             + "   面板重新生成配置 → App 里「拉取配置」→ 重启隧道\n"
             + "2. 本页点「用 HTTP 打开」，先确认 http:// 是否能通\n"
             + "3. 确认面板自定义域名 " + host + " 已绑定到这条隧道/节点\n"
             + "4. 若本地 8080 是纯 HTTP 服务，别选 type=https：\n"
             + "   frp 的 https 是 TLS 透传，证书要在本地服务配好\n"
             + "5. 仍不行就换个节点重建隧道\n\n"
             + "DNS 解析到：" + (ips.isEmpty() ? "—" : ips.get(0))
             + " · 节点：" + (nodeAddr == null ? "—" : nodeAddr);
    }
}
