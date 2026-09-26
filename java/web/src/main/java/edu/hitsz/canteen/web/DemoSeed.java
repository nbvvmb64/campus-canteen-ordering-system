package edu.hitsz.canteen.web;

import edu.hitsz.canteen.model.Role;
import edu.hitsz.canteen.web.data.DbStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Arrays;

/** Creates synthetic demonstration data only in the separately named demo schema. */
@Component
final class DemoSeed {
    private static final String TAG = "DEMO_SYNTHETIC_V1";
    private final DbStore store;
    private final BusinessService business;
    private final TransactionTemplate transactions;

    DemoSeed(DbStore store, BusinessService business, TransactionTemplate transactions) {
        this.store = store;
        this.business = business;
        this.transactions = transactions;
    }

    void seed() {
        seed(System.getenv("CANTEEN_DEMO_MERCHANT_PASSWORD"));
    }

    void seed(String password) {
        if (password == null || password.length() < 16)
            throw new IllegalStateException("演示商家密码须由运行时 secret 提供，至少16个字符");
        char[] secret = password.toCharArray();
        try {
            transactions.executeWithoutResult(status -> {
                JdbcTemplate jdbc = store.jdbc();
                int users = jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class);
                if (users == 0) {
                    for (String table : new String[]{"dishes","orders","order_items","order_idempotency",
                            "business_revision","id_counters"}) {
                        if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table,Integer.class) != 0)
                            throw new IllegalStateException("演示数据库非空且未初始化，拒绝覆盖");
                    }
                    jdbc.update("INSERT INTO business_revision(id,revision,imported_at,source_digest,last_export_revision) "
                            + "VALUES (1,0,CURRENT_TIMESTAMP(6),?,-1)",TAG);
                    for (String prefix : new String[]{"USR","DSH","ORD","ITM"})
                        jdbc.update("INSERT INTO id_counters(prefix,next_number) VALUES (?,1)",prefix);
                    var merchant = business.register("demo_merchant",secret,Role.MERCHANT);
                    business.addDish(merchant.getUserId(),"番茄鸡蛋饭",new BigDecimal("12.00"),30);
                    business.addDish(merchant.getUserId(),"香菇鸡腿饭",new BigDecimal("18.50"),24);
                    business.addDish(merchant.getUserId(),"紫菜蛋花汤",new BigDecimal("4.00"),40);
                }
                String tag = jdbc.queryForObject("SELECT source_digest FROM business_revision WHERE id=1",String.class);
                if (!TAG.equals(tag) || store.username("demo_merchant")
                        .filter(user -> user.getRole() == Role.MERCHANT).isEmpty())
                    throw new IllegalStateException("连接的数据库不是已标记的合成演示库");
            });
        } finally { Arrays.fill(secret,'\0'); }
    }
}
