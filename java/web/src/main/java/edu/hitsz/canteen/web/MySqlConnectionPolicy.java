package edu.hitsz.canteen.web;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

final class MySqlConnectionPolicy {
    private static final String ISOLATION_OWNER = "canteen-candidate-isolated-v1";

    private MySqlConnectionPolicy() { }

    static int port(ProjectPaths paths, int isolatedPort, boolean isolatedTestEnabled) throws IOException {
        Path installation = installationRoot();
        Path root = paths.root().toRealPath();
        Path rootPom = root.resolve("java/web/pom.xml");
        if (root.equals(installation) || (Files.isRegularFile(rootPom)
                && Files.isSameFile(rootPom, installation.resolve("java/web/pom.xml")))) {
            if (isolatedPort != 0) throw new IllegalStateException("正式项目根目录拒绝隔离 MySQL 端口参数");
            return 13306;
        }
        if (!isolatedTestEnabled || isolatedPort < 1024 || isolatedPort > 65535 || isolatedPort == 13306)
            throw new IllegalStateException("隔离 MySQL 须显式启用测试并指定非正式端口");
        Path target = installation.resolve("java/web/target").toRealPath();
        if (!target.startsWith(installation) || root.getParent() == null
                || !Files.isSameFile(target, root.getParent())
                || !root.getFileName().toString().startsWith("multiuser-mysql-"))
            throw new IllegalStateException("隔离 MySQL 项目根不在受控测试目录");
        Path owner = root.resolve("data/.candidate-isolation-owner");
        if (!Files.isRegularFile(owner) || Files.isSymbolicLink(owner)
                || !ISOLATION_OWNER.equals(Files.readString(owner).trim()))
            throw new IllegalStateException("隔离 MySQL 项目根缺少专用归属标记");
        return isolatedPort;
    }

    private static Path installationRoot() throws IOException {
        try {
            Path code = Path.of(MySqlConnectionPolicy.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toRealPath();
            Path found = findInstallation(code);
            if (found != null) return found;
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            // Spring Boot nested classes can have a jar: URL instead of a filesystem URL.
        }
        for (String entry : System.getProperty("java.class.path", "").split(
                Pattern.quote(System.getProperty("path.separator")))) {
            try {
                Path code = Path.of(entry).toRealPath();
                if (!Files.isRegularFile(code)
                        || !code.getFileName().toString().equals("canteen-web-1.0.0.jar")) continue;
                Path found = findInstallation(code);
                if (found != null) return found;
            } catch (IOException | IllegalArgumentException ignored) {
                // Other classpath entries do not identify this installation.
            }
        }
        throw new IllegalStateException("无法从网页程序位置确定正式项目根目录");
    }

    private static Path findInstallation(Path code) throws IOException {
        for (Path parent = Files.isDirectory(code) ? code : code.getParent(); parent != null;
             parent = parent.getParent()) {
            if (Files.isRegularFile(parent.resolve("java/web/pom.xml"))
                    && Files.isRegularFile(parent.resolve("README.md"))) return parent.toRealPath();
        }
        return null;
    }
}
