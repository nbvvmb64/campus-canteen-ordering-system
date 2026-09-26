package edu.hitsz.canteen.web;

import edu.hitsz.canteen.exception.BusinessException;
import edu.hitsz.canteen.model.Role;
import edu.hitsz.canteen.web.data.DbStore;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DemoPolicyTest {
    private static Map<String,String> environment() {
        Map<String,String> env = new HashMap<>();
        env.put("CANTEEN_DEMO_DB_HOST","10.20.30.40");
        env.put("CANTEEN_DEMO_DB_PORT","3306");
        env.put("CANTEEN_DEMO_DB_USER","demo_app");
        env.put("CANTEEN_DEMO_DB_PASSWORD","isolation-Only-Secret-123");
        return env;
    }

    @Test
    void privateDemoDatabaseCannotSilentlyBecomeFormalOrPublic() throws Exception {
        Map<String,String> env = environment();
        var settings = DemoConnectionPolicy.fromEnvironment(env);
        assertTrue(settings.jdbcUrl().contains("/canteen_demo?sslMode=VERIFY_IDENTITY"));
        assertEquals("demo_app",settings.username());
        env.put("CANTEEN_DEMO_DB_HOST","8.8.8.8");
        assertThrows(IllegalStateException.class,() -> DemoConnectionPolicy.fromEnvironment(env));
        env.put("CANTEEN_DEMO_DB_HOST","127.0.0.1");
        assertThrows(IllegalStateException.class,() -> DemoConnectionPolicy.fromEnvironment(env));
        env.put("CANTEEN_DEMO_DB_HOST","10.20.30.40");
        env.put("CANTEEN_DEMO_DB_PORT","13306");
        assertThrows(IllegalStateException.class,() -> DemoConnectionPolicy.fromEnvironment(env));
        env.put("CANTEEN_DEMO_DB_PORT","3306");
        env.put("CANTEEN_DEMO_DB_USER","root");
        assertThrows(IllegalStateException.class,() -> DemoConnectionPolicy.fromEnvironment(env));
        env.put("CANTEEN_DEMO_DB_USER","canteen_app");
        assertThrows(IllegalStateException.class,() -> DemoConnectionPolicy.fromEnvironment(env));
        env.put("CANTEEN_DEMO_DB_USER","demo_app");
        env.put("CANTEEN_DEMO_DB_PASSWORD","short");
        assertThrows(IllegalStateException.class,() -> DemoConnectionPolicy.fromEnvironment(env));
    }

    @Test
    void privateDualStackDnsIsAllowedButPublicIpv6IsRejected() throws Exception {
        assertTrue(DemoConnectionPolicy.isPrivateAddress(InetAddress.getByName("10.1.2.3")));
        assertTrue(DemoConnectionPolicy.isPrivateAddress(InetAddress.getByName("fd12::42")));
        assertFalse(DemoConnectionPolicy.isPrivateAddress(InetAddress.getByName("2001:4860:4860::8888")));
        assertFalse(DemoConnectionPolicy.isPrivateAddress(InetAddress.getByName("::1")));
    }

    @Test
    void railwayWireGuardExceptionRequiresPlatformMarkersAndInternalHost() {
        Map<String,String> env = environment();
        env.put("RAILWAY_PROJECT_ID","project-marker");
        env.put("RAILWAY_ENVIRONMENT_ID","environment-marker");
        assertTrue(DemoConnectionPolicy.railwayTransportAllowed("mysql.railway.internal",env,false));
        assertFalse(DemoConnectionPolicy.railwayTransportAllowed("mysql.railway.internal",env,true));
        assertFalse(DemoConnectionPolicy.railwayTransportAllowed("127.0.0.1",env,false));
        assertFalse(DemoConnectionPolicy.railwayTransportAllowed("mysql.example.com",env,false));
        env.remove("RAILWAY_ENVIRONMENT_ID");
        assertFalse(DemoConnectionPolicy.railwayTransportAllowed("mysql.railway.internal",env,false));
    }

    @Test
    void loopbackDatabaseExceptionNeedsOwnedLocalIsolation() throws Exception {
        Map<String,String> env = environment();
        env.put("CANTEEN_DEMO_DB_HOST","127.0.0.1");
        env.put("CANTEEN_DEMO_DB_PORT","13307");
        env.put("CANTEEN_DEMO_TEST","1");
        Path target = Path.of("target").toAbsolutePath();
        Path root = Files.createDirectories(target.resolve("demo-isolated-" + UUID.randomUUID()));
        try {
            assertThrows(IllegalStateException.class,
                    () -> DemoConnectionPolicy.fromEnvironment(env,root));
            Files.writeString(root.resolve(".demo-isolation-owner"),"canteen-demo-isolated-v1\n");
            assertTrue(DemoConnectionPolicy.fromEnvironment(env,root).jdbcUrl()
                    .contains("sslMode=DISABLED"));
            assertTrue(DemoConnectionPolicy.fromEnvironment(env,root).jdbcUrl()
                    .contains("allowPublicKeyRetrieval=true"));
        } finally {
            Files.deleteIfExists(root.resolve(".demo-isolation-owner"));
            Files.deleteIfExists(root);
        }
    }

    @Test
    void demoRejectsPublicMerchantRegistration() {
        ApiController controller = new ApiController(null,null,null,true);
        assertThrows(BusinessException.class, () -> controller.register(
                new ApiController.Register("merchant","StrongPass123456",Role.MERCHANT)));
    }

    @Test
    void proxyTokenHttpsAndWriteCapAreRequired() throws Exception {
        DemoIngressFilter filter = new DemoIngressFilter("proxy-only-secret-at-least-thirty-two-chars");
        MockHttpServletRequest request = new MockHttpServletRequest("GET","/");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(request,response,(req,res) -> reached.set(true));
        assertEquals(403,response.getStatus());
        assertFalse(reached.get());

        request = new MockHttpServletRequest("GET","/");
        request.addHeader("X-Canteen-Proxy-Secret","proxy-only-secret-at-least-thirty-two-chars");
        response = new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res) -> reached.set(true));
        assertEquals(403,response.getStatus());
        assertFalse(reached.get());

        request = new MockHttpServletRequest("GET","/");
        request.setSecure(true);
        request.addHeader("X-Canteen-Proxy-Secret","proxy-only-secret-at-least-thirty-two-chars");
        response = new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res) -> reached.set(true));
        assertEquals(200,response.getStatus());
        assertTrue(reached.get());

        for (int i=0;i<121;i++) {
            MockHttpServletRequest write = new MockHttpServletRequest("POST","/api/v1/orders");
            write.setSecure(true);
            write.addHeader("X-Canteen-Proxy-Secret","proxy-only-secret-at-least-thirty-two-chars");
            MockHttpServletResponse result = new MockHttpServletResponse();
            filter.doFilter(write,result,(req,res) -> { });
            assertEquals(i < 120 ? 200 : 429,result.getStatus());
        }
    }

    @Test
    void syntheticSeedIsRepeatableAndTagged() {
        var datasource = new DriverManagerDataSource("jdbc:h2:mem:demo_" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(datasource);
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        jdbc.update("DELETE FROM business_revision");
        DbStore store = new DbStore(jdbc);
        DemoSeed seed = new DemoSeed(store,new BusinessService(store),
                new TransactionTemplate(new DataSourceTransactionManager(datasource)));
        seed.seed("Only-Synthetic-Merchant-Secret-123");
        seed.seed("Only-Synthetic-Merchant-Secret-123");
        assertEquals(1,store.users().size());
        assertEquals(3,store.dishes().size());
        assertEquals(Role.MERCHANT,store.username("demo_merchant").orElseThrow().getRole());
        assertEquals("DEMO_SYNTHETIC_V1",jdbc.queryForObject(
                "SELECT source_digest FROM business_revision WHERE id=1",String.class));
        assertEquals(4L,store.currentRevision());
        jdbc.update("UPDATE business_revision SET source_digest='OTHER' WHERE id=1");
        assertThrows(IllegalStateException.class,() -> seed.seed("Only-Synthetic-Merchant-Secret-123"));
    }
}
