package edu.hitsz.canteen.service;

import edu.hitsz.canteen.exception.BusinessException;
import edu.hitsz.canteen.model.Dish;
import edu.hitsz.canteen.model.Role;
import edu.hitsz.canteen.model.SaleStatus;
import edu.hitsz.canteen.model.User;
import edu.hitsz.canteen.persistence.CsvDatabase;
import edu.hitsz.canteen.util.DateTimes;
import edu.hitsz.canteen.util.Rules;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

public final class DishService {
    private final CsvDatabase database;

    public DishService(CsvDatabase database) {
        this.database = database;
    }

    public Dish addDish(
            User merchant, String dishName, BigDecimal unitPrice, int stock) {
        User actor = AccessControl.requireRole(database, merchant, Role.MERCHANT);
        String normalizedName = Rules.requireTrimmedText(dishName, "菜品名称", 1, 50);
        BigDecimal normalizedPrice = requirePrice(unitPrice);
        requireStock(stock);

        return database.transaction(() -> {
            AccessControl.requireRole(database, actor, Role.MERCHANT);
            ensureNoActiveDuplicate(actor.getUserId(), normalizedName, null);
            var now = DateTimes.now();
            Dish dish = new Dish(
                    database.nextDishId(),
                    actor.getUserId(),
                    normalizedName,
                    normalizedPrice,
                    stock,
                    SaleStatus.ON_SALE,
                    now,
                    now);
            database.addDish(dish);
            return dish;
        });
    }

    public Dish updateDish(
            User merchant,
            String dishId,
            String dishName,
            BigDecimal unitPrice,
            int stock) {
        User actor = AccessControl.requireRole(database, merchant, Role.MERCHANT);
        String normalizedName = Rules.requireTrimmedText(dishName, "菜品名称", 1, 50);
        BigDecimal normalizedPrice = requirePrice(unitPrice);
        requireStock(stock);

        return database.transaction(() -> {
            Dish dish = requireOwnedDish(actor, dishId);
            if (dish.getSaleStatus() == SaleStatus.ON_SALE) {
                ensureNoActiveDuplicate(actor.getUserId(), normalizedName, dish.getDishId());
            }
            dish.update(normalizedName, normalizedPrice, stock, DateTimes.now());
            return dish;
        });
    }

    public Dish setSaleStatus(User merchant, String dishId, SaleStatus saleStatus) {
        User actor = AccessControl.requireRole(database, merchant, Role.MERCHANT);
        if (saleStatus == null) {
            throw new BusinessException("销售状态不能为空");
        }
        return database.transaction(() -> {
            Dish dish = requireOwnedDish(actor, dishId);
            if (saleStatus == SaleStatus.ON_SALE) {
                ensureNoActiveDuplicate(
                        actor.getUserId(), dish.getDishName(), dish.getDishId());
            }
            dish.setSaleStatus(saleStatus, DateTimes.now());
            return dish;
        });
    }

    public Dish deleteDish(User merchant, String dishId) {
        User actor = AccessControl.requireRole(database, merchant, Role.MERCHANT);
        return database.transaction(() -> {
            Dish dish = requireOwnedDish(actor, dishId);
            if (dish.getSaleStatus() == SaleStatus.OFF_SALE) {
                throw new BusinessException("菜品已经下架，不能重复删除");
            }
            // 公共接口要求历史订单引用的菜品不得物理删除。统一采用逻辑删除，
            // 同时保留编号，保证任何已使用ID都不会被复用。
            dish.setSaleStatus(SaleStatus.OFF_SALE, DateTimes.now());
            return dish;
        });
    }

    public List<Dish> listMerchantDishes(User merchant) {
        User actor = AccessControl.requireRole(database, merchant, Role.MERCHANT);
        return database.dishes().stream()
                .filter(dish -> dish.getMerchantId().equals(actor.getUserId()))
                .sorted(Comparator.comparing(Dish::getDishId))
                .toList();
    }

    public List<Dish> listAvailableDishes(User student) {
        AccessControl.requireRole(database, student, Role.STUDENT);
        return database.dishes().stream()
                .filter(dish -> dish.getSaleStatus() == SaleStatus.ON_SALE)
                .sorted(Comparator.comparing(Dish::getMerchantId)
                        .thenComparing(Dish::getDishId))
                .toList();
    }

    public List<Dish> listLowStock(User merchant, int threshold) {
        if (threshold < 0) {
            throw new BusinessException("低库存阈值不能为负数");
        }
        return listMerchantDishes(merchant).stream()
                .filter(dish -> dish.getStock() <= threshold)
                .toList();
    }

    private Dish requireOwnedDish(User merchant, String dishId) {
        Dish dish = database.findDish(dishId)
                .orElseThrow(() -> new BusinessException("菜品不存在"));
        if (!dish.getMerchantId().equals(merchant.getUserId())) {
            throw new BusinessException("不能管理其他商家的菜品");
        }
        return dish;
    }

    private void ensureNoActiveDuplicate(
            String merchantId, String dishName, String excludedDishId) {
        boolean duplicate = database.dishes().stream()
                .filter(dish -> dish.getMerchantId().equals(merchantId))
                .filter(dish -> dish.getSaleStatus() == SaleStatus.ON_SALE)
                .filter(dish -> excludedDishId == null
                        || !dish.getDishId().equals(excludedDishId))
                .anyMatch(dish -> dish.getDishName().equals(dishName));
        if (duplicate) {
            throw new BusinessException("同一商家不能存在完全同名的在售菜品");
        }
    }

    private BigDecimal requirePrice(BigDecimal unitPrice) {
        if (unitPrice == null
                || unitPrice.scale() > 2
                || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("价格必须大于0.00且最多保留两位小数");
        }
        return Rules.money(unitPrice);
    }

    private void requireStock(int stock) {
        if (stock < 0) {
            throw new BusinessException("库存不能为负数");
        }
    }
}
