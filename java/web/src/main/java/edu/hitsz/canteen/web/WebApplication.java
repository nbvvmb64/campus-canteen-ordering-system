package edu.hitsz.canteen.web;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.hitsz.canteen.web.data.CsvMigration;
import edu.hitsz.canteen.web.data.SnapshotExport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class WebApplication {
    public static void main(String[] args) {
        String mode = "serve";
        for (String arg : args) if (arg.startsWith("--canteen.mode=")) mode = arg.substring(15);
        SpringApplication application = new SpringApplication(WebApplication.class);
        boolean mysqlRequested = "mysql".equalsIgnoreCase(System.getenv("CANTEEN_DB_ENGINE"));
        for (String arg : args) if ("--canteen.db-engine=mysql".equalsIgnoreCase(arg)) mysqlRequested = true;
        if ("1".equals(System.getenv("CANTEEN_DEMO_MODE")))
            application.setAdditionalProfiles("demo", "mysql");
        else if (mysqlRequested)
            application.setAdditionalProfiles("mysql");
        if (!"serve".equals(mode)) application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }

    @Bean
    ProjectPaths projectPaths(@Value("${canteen.project-root:#{null}}") String root,
                              @Value("${canteen.demo.enabled:false}") boolean demo) {
        if (demo) return new ProjectPaths(Path.of("").toAbsolutePath().toString());
        return new ProjectPaths(root);
    }

    @Bean
    DataSource dataSource(ProjectPaths paths, @Value("${canteen.mode:serve}") String mode,
                          @Value("${canteen.db-engine:${CANTEEN_DB_ENGINE:h2}}") String engine,
                          @Value("${canteen.mysql-isolated-port:0}") int isolatedPort,
                          @Value("${canteen.demo.enabled:false}") boolean demo) throws Exception {
        if (demo) {
            if (!"1".equals(System.getenv("CANTEEN_DEMO_MODE")) || !engine.equals("mysql")
                    || !("serve".equals(mode) || "test".equals(mode)))
                throw new IllegalStateException("演示配置仅允许明确启用的 MySQL 网页服务");
            DemoConnectionPolicy.Settings settings = DemoConnectionPolicy.fromEnvironment(System.getenv());
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(settings.jdbcUrl());
            config.setUsername(settings.username());
            config.setPassword(settings.password());
            config.setMaximumPoolSize(8);
            config.setPoolName("canteen-demo");
            HikariDataSource source = new HikariDataSource(config);
            try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
                try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM business_revision")) {
                    if (!result.next()) throw new IllegalStateException("演示数据库 schema 尚未初始化");
                }
            } catch (Exception e) { source.close(); throw e; }
            return source;
        }
        if (!engine.equals("h2") && !engine.equals("mysql"))
            throw new IllegalStateException("未知数据库类型: " + engine);
        if ("serve".equals(mode) && (!Files.isRegularFile(paths.marker())
                || !"ACTIVE".equals(Files.readString(paths.marker()).trim()))) {
            throw new IllegalStateException("数据库迁移和切换尚未完成，网页服务拒绝启动");
        }
        boolean officialOperation = "serve".equals(mode) || "export".equals(mode) || "analyze".equals(mode);
        String selected = Files.isRegularFile(paths.engineMarker())
                ? Files.readString(paths.engineMarker()).trim() : "h2";
        if (officialOperation && !selected.equalsIgnoreCase(engine))
            throw new IllegalStateException("正式数据源标记与运行配置不一致，拒绝启动");
        if ("import-h2".equals(mode) && !engine.equals("mysql"))
            throw new IllegalStateException("H2 导入目标必须是 MySQL");
        if ("migrate".equals(mode) && !engine.equals("h2"))
            throw new IllegalStateException("原 CSV 迁移入口仅限 H2");
        HikariConfig config = new HikariConfig();
        boolean existingRequired = officialOperation;
        int mysqlPort = 0;
        if (engine.equals("h2")) {
            Files.createDirectories(paths.webVar());
            config.setJdbcUrl("jdbc:h2:file:" + paths.database().toString().replace('\\', '/')
                    + ";LOCK_TIMEOUT=10000" + (existingRequired ? ";IFEXISTS=TRUE" : ""));
            config.setUsername("sa");
        } else {
            mysqlPort = MySqlConnectionPolicy.port(paths, isolatedPort,
                    "1".equals(System.getenv("CANTEEN_MYSQL_TEST")));
            String password = System.getenv("CANTEEN_MYSQL_PASSWORD");
            String username = System.getenv("CANTEEN_MYSQL_USER");
            if (password == null || password.isEmpty() || username == null || username.isBlank())
                throw new IllegalStateException("MySQL 用户名和密码须由运行时环境提供");
            config.setJdbcUrl("jdbc:mysql://127.0.0.1:" + mysqlPort
                    + "/canteen?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=LOCAL");
            config.setUsername(username);
            config.setPassword(password);
        }
        config.setMaximumPoolSize(12);
        config.setPoolName("canteen-web");
        HikariDataSource source = new HikariDataSource(config);
        if (engine.equals("mysql")) {
            try (Connection connection = source.getConnection(); var statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT @@datadir, @@tmpdir, @@log_error, @@port, @@bind_address")) {
                if (!result.next()) throw new IllegalStateException("无法核对 MySQL 目录");
                Path data = Path.of(result.getString(1)).toRealPath();
                Path temp = Path.of(result.getString(2)).toRealPath();
                Path log = Path.of(result.getString(3)).toRealPath();
                if (!Files.isSameFile(data, paths.mysqlData())
                        || !Files.isSameFile(temp, paths.mysqlVar().resolve("tmp"))
                        || !Files.isSameFile(log.getParent(), paths.mysqlVar().resolve("logs"))
                        || result.getInt(4) != mysqlPort || !"127.0.0.1".equals(result.getString(5)))
                    throw new IllegalStateException("MySQL 数据、日志、临时目录或监听地址不符合项目专用实例约定");
            } catch (Exception e) { source.close(); throw e; }
        }
        if (existingRequired) {
            try (Connection connection = source.getConnection();
                 var statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT imported_at FROM business_revision WHERE id=1")) {
                if (!result.next() || result.getTimestamp(1) == null)
                    throw new IllegalStateException("数据库尚未完成原CSV迁移");
            } catch (Exception e) {
                source.close();
                throw e;
            }
        }
        return source;
    }

    @Bean
    ApplicationRunner commandRunner(@Value("${canteen.mode:serve}") String mode, ProjectPaths paths,
                                    CsvMigration migration, SnapshotExport exporter,
                                    edu.hitsz.canteen.web.data.H2ToMySqlImport importer,
                                    edu.hitsz.canteen.web.data.MySqlToH2Rollback rollback,
                                    @Value("${canteen.demo.enabled:false}") boolean demo,
                                    DemoSeed demoSeed) {
        return ignored -> {
            if (demo) {
                if (!"serve".equals(mode) && !"test".equals(mode))
                    throw new IllegalStateException("演示配置不允许正式数据迁移、导出或回退");
                demoSeed.seed();
                return;
            }
            switch (mode) {
                case "serve" -> {
                    if (!Files.isRegularFile(paths.marker())
                            || !"ACTIVE".equals(Files.readString(paths.marker()).trim())) {
                        throw new IllegalStateException("数据库迁移和切换尚未完成，网页服务拒绝启动");
                    }
                }
                case "migrate" -> migration.migrate();
                case "import-h2" -> importer.run();
                case "rollback-h2" -> System.out.println("已核验的 H2 回退候选: " + rollback.prepare());
                case "export" -> exporter.export();
                case "analyze" -> exporter.analyzeLatest();
                case "test" -> { }
                default -> throw new IllegalArgumentException("未知运行模式: " + mode);
            }
        };
    }
}
