package edu.hitsz.canteen.web.data;

import edu.hitsz.canteen.web.ProjectPaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

/** Builds a new isolated H2 rollback candidate from all current MySQL state. */
@Service
public class MySqlToH2Rollback {
    private final ProjectPaths paths;
    private final DataSource mysql;
    private final String targetPath;

    public MySqlToH2Rollback(ProjectPaths paths, DataSource mysql,
            @Value("${canteen.h2-rollback-target:${CANTEEN_H2_ROLLBACK_TARGET:}}") String targetPath) {
        this.paths = paths;
        this.mysql = mysql;
        this.targetPath = targetPath;
    }

    public Path prepare() throws Exception {
        Path root = paths.root().toRealPath();
        Path owner = root.resolve("data/.isolation-owner");
        boolean isolated = Files.isRegularFile(owner)
                && "canteen-mysql-isolated-v1".equals(Files.readString(owner).trim());
        Path engine = paths.engineMarker();
        boolean published = Files.isRegularFile(engine)
                && "MYSQL".equals(Files.readString(engine).trim());
        if (!isolated && !published)
            throw new IllegalStateException("反向同步要求受控隔离根目录或已发布的正式 MySQL 标记");
        Path destination = targetPath.isBlank()
                ? paths.webVar().resolve("h2-rollback/canteen").toAbsolutePath().normalize()
                : Path.of(targetPath).toAbsolutePath().normalize();
        if (!destination.startsWith(root) || destination.equals(paths.database().toAbsolutePath().normalize())
                || Files.exists(Path.of(destination + ".mv.db")))
            throw new IllegalStateException("隔离 H2 目标已存在或路径不安全，拒绝覆盖");
        Files.createDirectories(destination.getParent());
        String url = "jdbc:h2:file:" + destination.toString().replace('\\', '/');
        DriverManagerDataSource h2 = new DriverManagerDataSource(url, "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(h2);

        try (Connection from = mysql.getConnection(); Connection to = h2.getConnection()) {
            from.setAutoCommit(false);
            to.setAutoCommit(false);
            try {
                try (var lock = from.createStatement(); var row = lock.executeQuery(
                        "SELECT revision FROM business_revision WHERE id=1 FOR UPDATE")) {
                    if (!row.next()) throw new IllegalStateException("MySQL 缺少业务修订行");
                }
                try (var removeSeed = to.createStatement()) {
                    removeSeed.executeUpdate("DELETE FROM business_revision WHERE id=1");
                }
                for (String table : H2ToMySqlImport.TABLES) {
                    if (H2ToMySqlImport.count(to, table) != 0)
                        throw new IllegalStateException("隔离 H2 目标表非空: " + table);
                }
                H2ToMySqlImport.transferAndCompare(from, to);
                to.commit();
                from.rollback();
                return destination;
            } catch (Exception error) {
                to.rollback();
                from.rollback();
                throw error;
            }
        }
    }
}
