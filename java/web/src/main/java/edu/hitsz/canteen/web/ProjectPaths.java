package edu.hitsz.canteen.web;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ProjectPaths {
    private final Path root;

    public ProjectPaths(String configured) {
        if (configured != null && !configured.isBlank()) {
            root = Path.of(configured).toAbsolutePath().normalize();
        } else {
            Path found = Path.of("").toAbsolutePath().normalize();
            while (found != null && !(Files.isRegularFile(found.resolve("README.md"))
                    && Files.isRegularFile(found.resolve("java/web/pom.xml")))) {
                found = found.getParent();
            }
            if (found == null) throw new IllegalStateException("找不到项目根目录，请设置 CANTEEN_PROJECT_ROOT");
            root = found;
        }
    }

    public Path root() { return root; }
    public Path data() { return root.resolve("data"); }
    public Path webVar() { return root.resolve("java/web/var"); }
    public Path database() { return webVar().resolve("canteen"); }
    public Path mysqlVar() { return webVar().resolve("mysql"); }
    public Path mysqlData() { return mysqlVar().resolve("data"); }
    public Path engineMarker() { return data().resolve(".db-engine"); }
    public Path marker() { return data().resolve(".web-mode"); }
    public Path pointer() { return data().resolve("current_export.json"); }
    public Path exports() { return data().resolve("exports"); }
}
