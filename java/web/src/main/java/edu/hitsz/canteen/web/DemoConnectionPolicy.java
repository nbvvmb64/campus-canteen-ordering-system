package edu.hitsz.canteen.web;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Cloud demonstration connections never reuse the local formal database. */
final class DemoConnectionPolicy {
    private DemoConnectionPolicy() { }

    record Settings(String jdbcUrl, String username, String password) { }

    static Settings fromEnvironment(Map<String,String> env) throws Exception {
        return fromEnvironment(env, Path.of("").toAbsolutePath());
    }

    static Settings fromEnvironment(Map<String,String> env, Path workdir) throws Exception {
        String host = required(env,"CANTEEN_DEMO_DB_HOST");
        String username = required(env,"CANTEEN_DEMO_DB_USER");
        String password = env.get("CANTEEN_DEMO_DB_PASSWORD");
        if (password == null) throw new IllegalStateException("CANTEEN_DEMO_DB_PASSWORD 未配置");
        if (password.length() < 16) throw new IllegalStateException("演示数据库密码至少需要16个字符");
        if (!host.matches("[A-Za-z0-9.-]{1,253}") || host.startsWith("-") || host.endsWith("-"))
            throw new IllegalStateException("演示数据库主机名无效");
        if (!username.matches("[A-Za-z_][A-Za-z0-9_]{0,31}")
                || username.equalsIgnoreCase("root") || username.equalsIgnoreCase("canteen_app"))
            throw new IllegalStateException("演示数据库必须使用独立非 root 应用账号");
        int port;
        try { port = Integer.parseInt(env.getOrDefault("CANTEEN_DEMO_DB_PORT","3306")); }
        catch (NumberFormatException error) { throw new IllegalStateException("演示数据库端口无效",error); }
        if (port < 1 || port > 65535 || port == 13306)
            throw new IllegalStateException("演示数据库不能使用正式本机端口");
        boolean isolated = "1".equals(env.get("CANTEEN_DEMO_TEST"));
        if (isolated && !ownedIsolation(workdir))
            throw new IllegalStateException("本地明文连接仅允许受控隔离测试目录");
        boolean railwayWireGuard = "railway-wireguard".equals(env.get("CANTEEN_DEMO_DB_TRANSPORT"));
        if (railwayWireGuard && !railwayTransportAllowed(host,env,isolated))
            throw new IllegalStateException("WireGuard 模式仅允许 Railway 同环境私网主机");
        InetAddress[] addresses;
        try { addresses = InetAddress.getAllByName(host); }
        catch (UnknownHostException error) { throw new IllegalStateException("演示数据库主机无法解析",error); }
        if (addresses.length == 0) throw new IllegalStateException("演示数据库主机无法解析");
        for (InetAddress address : addresses) {
            boolean privateAddress = isPrivateAddress(address);
            boolean localTest = isolated && port == 13307 && address.isLoopbackAddress();
            if (!privateAddress && !localTest)
                throw new IllegalStateException("演示数据库必须使用私网地址");
        }
        String tls = isolated && port == 13307 || railwayWireGuard ? "DISABLED" : "VERIFY_IDENTITY";
        return new Settings("jdbc:mysql://" + host + ":" + port + "/canteen_demo"
                + "?sslMode=" + tls
                + ("DISABLED".equals(tls) ? "&allowPublicKeyRetrieval=true" : "")
                + "&connectionTimeZone=LOCAL&useUnicode=true&characterEncoding=UTF-8",
                username,password);
    }

    static boolean isPrivateAddress(InetAddress address) {
        if (address instanceof Inet4Address) return address.isSiteLocalAddress();
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc; // IPv6 unique local fc00::/7
    }

    static boolean railwayTransportAllowed(String host, Map<String,String> env, boolean isolated) {
        return !isolated && host.endsWith(".railway.internal")
                && host.length() > ".railway.internal".length()
                && !blank(env.get("RAILWAY_PROJECT_ID"))
                && !blank(env.get("RAILWAY_ENVIRONMENT_ID"));
    }

    private static String required(Map<String,String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " 未配置");
        return value.trim();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static boolean ownedIsolation(Path workdir) throws Exception {
        if (Files.isSymbolicLink(workdir)) return false;
        Path root = workdir.toRealPath();
        Path parent = root.getParent();
        if (parent == null || !root.getFileName().toString().startsWith("demo-isolated-")
                || !parent.endsWith(Path.of("java/web/target")) || Files.isSymbolicLink(root)) return false;
        Path owner = root.resolve(".demo-isolation-owner");
        return Files.isRegularFile(owner) && !Files.isSymbolicLink(owner)
                && "canteen-demo-isolated-v1".equals(Files.readString(owner).trim());
    }
}
