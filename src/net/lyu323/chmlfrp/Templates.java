package net.lyu323.chmlfrp;

/** 内置配置模板。真机 arm64 是 ChmlFrp 0.51.2（只认 INI），故按内核分两套。 */
public final class Templates {
    private Templates() {}

    private static final String[] INI_NAMES = {
            "【INI】TCP（远程桌面 / SSH）",
            "【INI】HTTP 建站",
            "【INI】HTTPS 建站",
            "【INI】UDP（游戏 / 语音）"
    };

    private static final String[] INI_BODIES = {
            "# ChmlFrp 面板 INI · TCP 模板\n"
                    + "[common]\n"
                    + "server_addr = 节点域名或IP\n"
                    + "server_port = 7000\n"
                    + "user = 你的用户名\n"
                    + "token = 你的token\n"
                    + "tcp_mux = true\n"
                    + "protocol = tcp\n"
                    + "tls_enable = false\n\n"
                    + "[隧道名称]\n"
                    + "type = tcp\n"
                    + "local_ip = 127.0.0.1\n"
                    + "local_port = 22\n"
                    + "remote_port = 12345\n",

            "# ChmlFrp 面板 INI · HTTP 模板（国内节点需备案域名）\n"
                    + "[common]\n"
                    + "server_addr = 节点域名或IP\n"
                    + "server_port = 7000\n"
                    + "user = 你的用户名\n"
                    + "token = 你的token\n\n"
                    + "[隧道名称]\n"
                    + "type = http\n"
                    + "local_ip = 127.0.0.1\n"
                    + "local_port = 8080\n"
                    + "custom_domains = your.domain.com\n",

            "# ChmlFrp 面板 INI · HTTPS 模板\n"
                    + "[common]\n"
                    + "server_addr = 节点域名或IP\n"
                    + "server_port = 7000\n"
                    + "user = 你的用户名\n"
                    + "token = 你的token\n\n"
                    + "[隧道名称]\n"
                    + "type = https\n"
                    + "local_ip = 127.0.0.1\n"
                    + "local_port = 443\n"
                    + "custom_domains = your.domain.com\n",

            "# ChmlFrp 面板 INI · UDP 模板\n"
                    + "[common]\n"
                    + "server_addr = 节点域名或IP\n"
                    + "server_port = 7000\n"
                    + "user = 你的用户名\n"
                    + "token = 你的token\n\n"
                    + "[隧道名称]\n"
                    + "type = udp\n"
                    + "local_ip = 127.0.0.1\n"
                    + "local_port = 25565\n"
                    + "remote_port = 25565\n"
    };

    private static final String[] TOML_NAMES = {
            "【TOML】TCP（标准 frp 0.6x）",
            "【TOML】HTTP 建站",
            "【TOML】HTTPS 建站",
            "【TOML】UDP"
    };

    private static final String[] TOML_BODIES = {
            "# 标准 frp 0.6x TOML · TCP\n"
                    + "serverAddr = \"节点域名或IP\"\n"
                    + "serverPort = 7000\n"
                    + "user = \"你的用户名\"\n"
                    + "auth.token = \"你的token\"\n\n"
                    + "[[proxies]]\n"
                    + "name = \"my-tcp\"\n"
                    + "type = \"tcp\"\n"
                    + "localIP = \"127.0.0.1\"\n"
                    + "localPort = 22\n"
                    + "remotePort = 12345\n",

            "# 标准 frp 0.6x TOML · HTTP\n"
                    + "serverAddr = \"节点域名或IP\"\n"
                    + "serverPort = 7000\n"
                    + "auth.token = \"你的token\"\n\n"
                    + "[[proxies]]\n"
                    + "name = \"my-web\"\n"
                    + "type = \"http\"\n"
                    + "localIP = \"127.0.0.1\"\n"
                    + "localPort = 8080\n"
                    + "customDomains = [\"your.domain.com\"]\n",

            "# 标准 frp 0.6x TOML · HTTPS\n"
                    + "serverAddr = \"节点域名或IP\"\n"
                    + "serverPort = 7000\n"
                    + "auth.token = \"你的token\"\n\n"
                    + "[[proxies]]\n"
                    + "name = \"my-https\"\n"
                    + "type = \"https\"\n"
                    + "localIP = \"127.0.0.1\"\n"
                    + "localPort = 443\n"
                    + "customDomains = [\"your.domain.com\"]\n",

            "# 标准 frp 0.6x TOML · UDP\n"
                    + "serverAddr = \"节点域名或IP\"\n"
                    + "serverPort = 7000\n"
                    + "auth.token = \"你的token\"\n\n"
                    + "[[proxies]]\n"
                    + "name = \"my-udp\"\n"
                    + "type = \"udp\"\n"
                    + "localIP = \"127.0.0.1\"\n"
                    + "localPort = 25565\n"
                    + "remotePort = 25565\n"
    };

    /** 内核支持 TOML 时两套都给，否则只给 INI（避免用户插入跑不起来的配置）。 */
    public static String[] names(boolean tomlCapable) {
        if (!tomlCapable) return INI_NAMES.clone();
        String[] out = new String[INI_NAMES.length + TOML_NAMES.length];
        System.arraycopy(INI_NAMES, 0, out, 0, INI_NAMES.length);
        System.arraycopy(TOML_NAMES, 0, out, INI_NAMES.length, TOML_NAMES.length);
        return out;
    }

    public static String get(boolean tomlCapable, int i) {
        if (!tomlCapable) {
            return INI_BODIES[i >= 0 && i < INI_BODIES.length ? i : 0];
        }
        if (i < INI_BODIES.length) return INI_BODIES[i];
        int j = i - INI_BODIES.length;
        return TOML_BODIES[j >= 0 && j < TOML_BODIES.length ? j : 0];
    }
}
