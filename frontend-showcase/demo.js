/* 独立静态站点：业务状态仅保存在访问者本机。 */
(function () {
  "use strict";

  const STORAGE_KEY = "canteen.showcase.demo.v1";
  const SESSION_KEY = "canteen.showcase.active.v1";
  const statusNames = { PLACED: "已下单", PREPARING: "制作中", COMPLETED: "已完成", CANCELLED: "已取消" };

  function initialState() {
    const state = {
      merchants: { m1: "青禾食堂", m2: "南苑小厨" },
      accounts: [
        { id: "U-STUDENT", name: "校园学生", role: "STUDENT" },
        { id: "U-M1", name: "青禾商家", role: "MERCHANT", merchantId: "m1" },
        { id: "U-M2", name: "南苑商家", role: "MERCHANT", merchantId: "m2" }
      ],
      nextAccount: 4,
      nextMerchant: 3,
      dishes: [
        { id: "D-1", merchantId: "m1", name: "番茄牛肉饭", cents: 2200, stock: 18, onSale: true, icon: "🍚" },
        { id: "D-2", merchantId: "m1", name: "香煎鸡肉能量碗", cents: 2400, stock: 12, onSale: true, icon: "🥗" },
        { id: "D-3", merchantId: "m1", name: "清爽柠檬茶", cents: 800, stock: 30, onSale: true, icon: "🥤" },
        { id: "D-4", merchantId: "m2", name: "菌菇鸡腿饭", cents: 2600, stock: 12, onSale: true, icon: "🍗" },
        { id: "D-5", merchantId: "m2", name: "鲜虾蔬菜卷", cents: 1900, stock: 8, onSale: true, icon: "🌯" },
        { id: "D-6", merchantId: "m2", name: "乌梅饮", cents: 700, stock: 24, onSale: true, icon: "🧃" }
      ],
      cart: {},
      orders: [
        { id: "订单-001", studentId: "U-STUDENT", merchantId: "m1", status: "COMPLETED", lines: [{ dishId: "D-1", name: "番茄牛肉饭", cents: 2200, qty: 2 }], totalCents: 4400, createdAt: "历史记录" },
        { id: "订单-002", studentId: "U-STUDENT", merchantId: "m2", status: "PLACED", lines: [{ dishId: "D-4", name: "菌菇鸡腿饭", cents: 2600, qty: 1 }], totalCents: 2600, createdAt: "历史记录" }
      ],
      nextOrder: 3,
      nextDish: 7
    };
    return upgradeSeedV3(upgradeSeed(state));
  }

  function upgradeSeed(state, today = new Date()) {
    if (state.seedVersion >= 2) return state;
    const originalStudent = state.accounts.find(account => account.id === "U-STUDENT");
    if (originalStudent && originalStudent.name === "演示学生") originalStudent.name = "校园学生";
    if (!state.accounts.some(account => account.id === "U-CLASSMATE")) state.accounts.push({ id: "U-CLASSMATE", name: "校园同学", role: "STUDENT" });
    const specs = [
      ["pumpkin", "m1", "烤南瓜藜麦饭", 1800, 21, true, "🥙"],
      ["noodle", "m1", "红烧牛腩面", 2800, 4, true, "🍜"],
      ["yogurt", "m1", "酸奶水果杯", 1200, 0, true, "🍨"],
      ["chicken", "m2", "黑椒鸡排饭", 2300, 16, true, "🍛"],
      ["soup", "m2", "鲜蔬豆腐汤", 900, 3, true, "🥣"],
      ["cake", "m2", "桂花米糕", 600, 0, true, "🍡"],
      ["sesame", "m2", "麻酱拌面", 1700, 10, false, "🍝"]
    ];
    const seededDishes = new Map();
    for (const [key, merchantId, name, cents, stock, onSale, icon] of specs) {
      while (state.dishes.some(dish => dish.id === "D-" + state.nextDish)) state.nextDish++;
      const dish = { id: "D-" + state.nextDish++, merchantId, name, cents, stock, onSale, icon };
      state.dishes.push(dish);
      seededDishes.set(key, dish);
    }
    const dateAt = days => {
      const date = new Date(today.getFullYear(), today.getMonth(), today.getDate() - days, 12, 0, 0);
      return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")} 12:00:00`;
    };
    const makeOrder = (merchantId, studentId, status, days, items) => {
      while (state.orders.some(order => order.id === "订单-" + String(state.nextOrder).padStart(3, "0"))) state.nextOrder++;
      const lines = items.map(([key, qty]) => {
        const dish = seededDishes.get(key);
        return { dishId: dish.id, name: dish.name, cents: dish.cents, qty };
      });
      return { id: "订单-" + String(state.nextOrder++).padStart(3, "0"), studentId, merchantId, status, lines,
        totalCents: lines.reduce((sum, line) => sum + line.cents * line.qty, 0), createdAt: dateAt(days) };
    };
    const additions = [
      makeOrder("m1", "U-STUDENT", "PLACED", 0, [["pumpkin", 2], ["noodle", 1]]),
      makeOrder("m2", "U-CLASSMATE", "PREPARING", 0, [["chicken", 1], ["soup", 2]]),
      makeOrder("m1", "U-STUDENT", "COMPLETED", 1, [["pumpkin", 1], ["yogurt", 2]]),
      makeOrder("m2", "U-STUDENT", "COMPLETED", 2, [["chicken", 2], ["cake", 2]]),
      makeOrder("m1", "U-CLASSMATE", "CANCELLED", 3, [["noodle", 1], ["pumpkin", 1]]),
      makeOrder("m2", "U-CLASSMATE", "COMPLETED", 4, [["soup", 1], ["chicken", 1]]),
      makeOrder("m1", "U-STUDENT", "COMPLETED", 5, [["noodle", 2]]),
      makeOrder("m2", "U-STUDENT", "PLACED", 6, [["chicken", 1], ["cake", 1]]),
      makeOrder("m1", "U-CLASSMATE", "COMPLETED", 7, [["pumpkin", 1], ["yogurt", 1]]),
      makeOrder("m2", "U-CLASSMATE", "CANCELLED", 8, [["soup", 1]])
    ];
    state.orders = [...state.orders, ...additions].sort((a, b) => {
      const aDate = Date.parse(a.createdAt) || 0;
      const bDate = Date.parse(b.createdAt) || 0;
      return bDate - aDate;
    });
    state.seedVersion = 2;
    return state;
  }

  function upgradeSeedV3(state, today = new Date()) {
    if (state.seedVersion >= 3) return state;
    if (!state.accounts.some(account => account.id === "U-LUNCH")) state.accounts.push({ id: "U-LUNCH", name: "午餐同学", role: "STUDENT" });
    const knownCategories = {
      "D-1": "主食", "D-2": "主食", "D-3": "饮品", "D-4": "主食", "D-5": "小吃", "D-6": "饮品"
    };
    for (const dish of state.dishes) {
      if (!dish.category) dish.category = knownCategories[dish.id] || (/汤/.test(dish.name) ? "汤品" : /面|粥/.test(dish.name) ? "面食" : /茶|饮|水|豆乳/.test(dish.name) ? "饮品" : /饭|碗/.test(dish.name) ? "主食" : "小吃");
    }
    const specs = [
      ["m1", "鸡排咖喱饭", 2500, 32, true, "🍛", "主食"],
      ["m1", "青椒肉丝盖饭", 2100, 18, true, "🍚", "主食"],
      ["m1", "香菇鸡肉粥", 1600, 9, true, "🥣", "面食"],
      ["m1", "虾仁炒面", 2300, 5, true, "🍜", "面食"],
      ["m1", "紫菜蛋花汤", 700, 24, true, "🥣", "汤品"],
      ["m1", "凉拌黄瓜", 900, 12, true, "🥒", "小吃"],
      ["m1", "红豆豆乳", 1100, 7, true, "🥛", "饮品"],
      ["m1", "甜橙气泡水", 900, 0, true, "🍊", "饮品"],
      ["m1", "脆皮鸡块", 1400, 20, false, "🍗", "小吃"],
      ["m2", "麻辣香锅", 3000, 27, true, "🍲", "主食"],
      ["m2", "番茄鸡蛋面", 1800, 14, true, "🍜", "面食"],
      ["m2", "照烧猪排饭", 2600, 11, true, "🍚", "主食"],
      ["m2", "南瓜浓汤", 1000, 17, true, "🥣", "汤品"],
      ["m2", "玉米沙拉", 1200, 8, true, "🥗", "小吃"],
      ["m2", "冰柠檬水", 800, 35, true, "🍋", "饮品"],
      ["m2", "鲜奶布丁", 1100, 0, true, "🍮", "小吃"],
      ["m2", "鸡蛋三明治", 1500, 19, true, "🥪", "主食"],
      ["m2", "香葱肉饼", 1300, 6, false, "🥮", "小吃"]
    ];
    const pools = { m1: [], m2: [] };
    const soldQty = new Map();
    for (const [merchantId, name, cents, targetStock, onSale, icon, category] of specs) {
      while (state.dishes.some(dish => dish.id === "D-" + state.nextDish)) state.nextDish++;
      const dish = { id: "D-" + state.nextDish++, merchantId, name, cents, stock: targetStock, onSale, icon, category };
      state.dishes.push(dish);
      pools[merchantId].push(dish);
      soldQty.set(dish.id, 0);
    }
    const additions = [];
    let sequence = 0;
    for (let days = 29; days >= 0; days--) {
      const date = new Date(today.getFullYear(), today.getMonth(), today.getDate() - days);
      const weekend = date.getDay() === 0 || date.getDay() === 6;
      const count = weekend ? 3 : 6;
      for (let slot = 0; slot < count; slot++, sequence++) {
        const merchantId = (sequence + days) % 2 ? "m1" : "m2";
        const pool = pools[merchantId];
        const first = pool[(sequence * 3 + days) % 4];
        const second = pool[4 + (sequence + days) % 5];
        const third = pool[(sequence + 2) % 4];
        const choices = [[first, sequence % 7 === 0 ? 2 : 1], [second, 1]];
        if (sequence % 3 === 0 && third.id !== first.id) choices.push([third, 1]);
        const lines = choices.map(([dish, qty]) => ({ dishId: dish.id, name: dish.name, cents: dish.cents, qty }));
        const status = days >= 2 ? sequence % 21 === 0 ? "CANCELLED" : "COMPLETED"
          : sequence % 17 === 0 ? "CANCELLED" : slot % 3 === 0 ? "PLACED" : slot % 3 === 1 ? "PREPARING" : "COMPLETED";
        const hour = slot < Math.ceil(count / 2) ? 11 + Math.floor(slot / 2) : 17 + Math.floor((slot - Math.ceil(count / 2)) / 2);
        const minute = [15, 40, 5, 20, 45, 10][slot];
        const createdAt = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")} ${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}:00`;
        while (state.orders.some(order => order.id === "订单-" + String(state.nextOrder).padStart(3, "0"))) state.nextOrder++;
        const order = { id: "订单-" + String(state.nextOrder++).padStart(3, "0"),
          studentId: ["U-STUDENT", "U-CLASSMATE", "U-LUNCH"][sequence % 3], merchantId, status, lines,
          totalCents: lines.reduce((sum, line) => sum + line.cents * line.qty, 0), createdAt };
        additions.push(order);
        if (status !== "CANCELLED") for (const line of lines) soldQty.set(line.dishId, soldQty.get(line.dishId) + line.qty);
      }
    }
    for (const pool of Object.values(pools)) for (const dish of pool) dish.seedOpeningStock = dish.stock + soldQty.get(dish.id);
    state.orders = [...state.orders, ...additions].sort((a, b) => (Date.parse(b.createdAt) || 0) - (Date.parse(a.createdAt) || 0));
    state.seedVersion = 3;
    return state;
  }

  function money(cents) { return "¥" + (cents / 100).toFixed(2); }
  function displayOrderId(id) {
    const match = /^(?:DEMO-|订单-)(\d+)$/i.exec(String(id));
    return match ? "订单-" + match[1].padStart(3, "0") : String(id);
  }
  function findDish(state, id) { return state.dishes.find(d => d.id === id); }
  function cartLines(state) {
    return Object.entries(state.cart).filter(([, qty]) => Number.isInteger(qty) && qty > 0).map(([id, qty]) => ({ dish: findDish(state, id), qty })).filter(line => line.dish);
  }
  function addToCart(state, id) {
    const dish = findDish(state, id);
    if (!dish || !dish.onSale || dish.stock < 1) throw new Error("这道菜暂不可加入餐篮。");
    const lines = cartLines(state);
    if (lines.some(line => line.dish.merchantId !== dish.merchantId)) throw new Error("一份订单只能选择同一家商家的菜品。请先清空当前餐篮。");
    if ((state.cart[id] || 0) >= dish.stock) throw new Error("餐篮数量不能超过当前库存。");
    state.cart[id] = (state.cart[id] || 0) + 1;
  }
  function changeCart(state, id, delta) {
    if (delta > 0) return addToCart(state, id);
    if (!state.cart[id]) return;
    state.cart[id] -= 1;
    if (state.cart[id] <= 0) delete state.cart[id];
  }
  function previewOrder(state) {
    const lines = cartLines(state);
    if (!lines.length) throw new Error("餐篮还是空的，请先选择菜品。");
    const merchantId = lines[0].dish.merchantId;
    for (const { dish, qty } of lines) {
      if (dish.merchantId !== merchantId || !dish.onSale || qty > dish.stock) throw new Error("菜品或库存已变化，请调整餐篮后重试。");
    }
    return { merchantId, lines: lines.map(({ dish, qty }) => ({ dishId: dish.id, name: dish.name, cents: dish.cents, qty })), totalCents: lines.reduce((sum, { dish, qty }) => sum + dish.cents * qty, 0) };
  }
  function checkout(state, studentId = "U-STUDENT") {
    const preview = previewOrder(state);
    const orderLines = preview.lines;
    const order = {
      id: "订单-" + String(state.nextOrder).padStart(3, "0"), studentId, merchantId: preview.merchantId, status: "PLACED", lines: orderLines,
      totalCents: preview.totalCents,
      createdAt: new Date().toLocaleString("zh-CN", { hour12: false })
    };
    for (const line of orderLines) findDish(state, line.dishId).stock -= line.qty;
    state.nextOrder += 1;
    state.orders.unshift(order);
    state.cart = {};
    return order;
  }
  function cancelOrder(state, id, studentId) {
    const order = state.orders.find(item => item.id === id);
    if (!order || (studentId && order.studentId !== studentId) || order.status !== "PLACED") throw new Error("只有本人“已下单”的订单可以取消。");
    for (const line of order.lines) {
      const dish = findDish(state, line.dishId);
      if (dish) dish.stock += line.qty;
    }
    order.status = "CANCELLED";
  }
  function advanceOrder(state, id, merchantId) {
    const order = state.orders.find(item => item.id === id && item.merchantId === merchantId);
    if (!order || !["PLACED", "PREPARING"].includes(order.status)) throw new Error("这张订单当前不能继续处理。");
    order.status = order.status === "PLACED" ? "PREPARING" : "COMPLETED";
  }
  function adjustStock(state, id, merchantId, delta) {
    const dish = findDish(state, id);
    if (!dish || dish.merchantId !== merchantId || dish.stock + delta < 0) throw new Error("库存不能小于 0。");
    dish.stock += delta;
  }
  function toggleSale(state, id, merchantId) {
    const dish = findDish(state, id);
    if (!dish || dish.merchantId !== merchantId) throw new Error("无法修改这道菜。");
    dish.onSale = !dish.onSale;
  }
  function saveDish(state, merchantId, id, name, cents, stock, category = "主食") {
    name = String(name).trim();
    if (!name || name.length > 40 || !Number.isInteger(cents) || cents < 1 || cents > 999900 || !Number.isInteger(stock) || stock < 0 || stock > 9999 || !["主食", "面食", "汤品", "小吃", "饮品"].includes(category)) throw new Error("请填写有效的菜名、分类、单价和库存。");
    if (id) {
      const dish = findDish(state, id);
      if (!dish || dish.merchantId !== merchantId) throw new Error("无法编辑这道菜。");
      dish.name = name; dish.cents = cents; dish.stock = stock; dish.category = category;
    } else {
      state.dishes.push({ id: "D-" + state.nextDish++, merchantId, name, cents, stock, category, onSale: true, icon: "🍽️" });
    }
  }
  function registerAccount(state, name, role, storeName) {
    name = String(name).trim();
    storeName = String(storeName || "").trim();
    if (name.length < 2 || name.length > 20 || state.accounts.some(account => account.name.toLocaleLowerCase() === name.toLocaleLowerCase())) throw new Error("昵称需为 2–20 字，且不能与已有账号重复。");
    if (!["STUDENT", "MERCHANT"].includes(role)) throw new Error("请选择身份。");
    if (role === "MERCHANT" && (storeName.length < 2 || storeName.length > 30)) throw new Error("示例店铺名称需为 2–30 字。");
    const account = { id: "U-" + state.nextAccount++, name, role };
    if (role === "MERCHANT") {
      account.merchantId = "m" + state.nextMerchant++;
      state.merchants[account.merchantId] = storeName;
    }
    state.accounts.push(account);
    return account;
  }
  function orderDay(order) {
    const match = /^(\d{4})[-/](\d{1,2})[-/](\d{1,2})/.exec(order.createdAt || "");
    return match ? Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])) : null;
  }
  function merchantOrders(state, merchantId, period = "all", now = new Date()) {
    const cutoff = period === "all" ? -Infinity : Date.UTC(now.getFullYear(), now.getMonth(), now.getDate() - Number(period) + 1);
    return state.orders.filter(order => order.merchantId === merchantId && (period === "all" || orderDay(order) !== null && orderDay(order) >= cutoff));
  }
  function metrics(state, merchantId, period = "all", now = new Date()) {
    const allOrders = merchantOrders(state, merchantId, period, now);
    const orders = allOrders.filter(order => order.status !== "CANCELLED");
    const dishes = state.dishes.filter(dish => dish.merchantId === merchantId);
    const salesCents = orders.reduce((sum, order) => sum + order.totalCents, 0);
    return { salesCents, orderCount: orders.length, totalOrderCount: allOrders.length,
      salesQty: orders.reduce((sum, order) => sum + order.lines.reduce((n, line) => n + line.qty, 0), 0),
      averageCents: orders.length ? Math.round(salesCents / orders.length) : 0,
      cancelledCount: allOrders.length - orders.length,
      cancellationPercent: allOrders.length ? Math.round((allOrders.length - orders.length) / allOrders.length * 100) : 0,
      onSaleCount: dishes.filter(dish => dish.onSale).length,
      lowStockCount: dishes.filter(dish => dish.stock > 0 && dish.stock < 15).length,
      outOfStockCount: dishes.filter(dish => dish.stock === 0).length };
  }
  function dailySales(state, merchantId) {
    const totals = new Map();
    for (const order of state.orders.filter(item => item.merchantId === merchantId && item.status !== "CANCELLED")) {
      const match = /^(\d{4})[-/](\d{1,2})[-/](\d{1,2})/.exec(order.createdAt || "");
      const date = match ? `${match[1]}-${match[2].padStart(2, "0")}-${match[3].padStart(2, "0")}` : "预置样例";
      totals.set(date, (totals.get(date) || 0) + order.totalCents);
    }
    return [...totals].sort((a, b) => a[0].localeCompare(b[0], "zh-CN")).slice(-7);
  }

  function csvOrders(orders, merchants) {
    const cell = value => {
      let string = String(value == null ? "" : value);
      if (/^[=+@\-\t\r]/.test(string)) string = "'" + string;
      return '"' + string.replace(/"/g, '""') + '"';
    };
    const rows = [["order_id", "student_id", "merchant", "status", "created_at", "dish_id", "dish_name", "unit_price", "quantity", "line_total", "order_total"]];
    for (const order of orders) for (const line of order.lines) rows.push([order.id, order.studentId, merchants[order.merchantId] || "", order.status, order.createdAt, line.dishId, line.name, (line.cents / 100).toFixed(2), line.qty, (line.cents * line.qty / 100).toFixed(2), (order.totalCents / 100).toFixed(2)]);
    return "\ufeff" + rows.map(row => row.map(cell).join(",")).join("\r\n") + "\r\n";
  }
  function dashboardSummary(state, merchantId, period = 7, now = new Date()) {
    const allOrders = merchantOrders(state, merchantId, period, now);
    const valid = allOrders.filter(order => order.status !== "CANCELLED");
    const statuses = { PLACED: 0, PREPARING: 0, COMPLETED: 0, CANCELLED: 0 };
    for (const order of allOrders) if (order.status in statuses) statuses[order.status]++;
    const sold = new Map();
    for (const order of valid) for (const line of order.lines) {
      const value = sold.get(line.dishId) || { name: line.name, qty: 0, cents: 0 };
      value.qty += line.qty; value.cents += line.cents * line.qty; sold.set(line.dishId, value);
    }
    const topDishes = [...sold.values()].sort((a, b) => b.qty - a.qty || b.cents - a.cents).slice(0, 5);
    const span = Number(period);
    const bucketSize = span === 30 ? 5 : 1;
    const bucketCount = span / bucketSize;
    const start = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate() - span + 1);
    const dayLabel = timestamp => { const date = new Date(timestamp); return `${date.getUTCMonth() + 1}/${date.getUTCDate()}`; };
    const trend = Array.from({ length: bucketCount }, (_, index) => {
      const first = start + index * bucketSize * 86400000;
      const last = first + (bucketSize - 1) * 86400000;
      return { label: bucketSize === 1 ? dayLabel(first) : `${dayLabel(first)}–${dayLabel(last)}`, salesCents: 0, orderCount: 0 };
    });
    for (const order of valid) {
      const day = orderDay(order);
      if (day === null) continue;
      const index = Math.floor((day - start) / 86400000 / bucketSize);
      if (index >= 0 && index < trend.length) { trend[index].salesCents += order.totalCents; trend[index].orderCount++; }
    }
    const timeSlots = [
      { key: "LUNCH", label: "午餐", hint: "10:00–14:59", orderCount: 0, salesCents: 0 },
      { key: "DINNER", label: "晚餐", hint: "16:00–20:59", orderCount: 0, salesCents: 0 },
      { key: "OTHER", label: "其他", hint: "其他时段", orderCount: 0, salesCents: 0 }
    ];
    for (const order of valid) {
      const match = /\s(\d{1,2}):/.exec(order.createdAt || "");
      const hour = match ? Number(match[1]) : -1;
      const slot = hour >= 10 && hour < 15 ? timeSlots[0] : hour >= 16 && hour < 21 ? timeSlots[1] : timeSlots[2];
      slot.orderCount++; slot.salesCents += order.totalCents;
    }
    const merchantDishes = state.dishes.filter(dish => dish.merchantId === merchantId);
    const inventory = {
      normal: merchantDishes.filter(dish => dish.stock >= 15).length,
      low: merchantDishes.filter(dish => dish.stock > 0 && dish.stock < 15).length,
      out: merchantDishes.filter(dish => dish.stock === 0).length,
      alerts: merchantDishes.filter(dish => dish.stock < 15).sort((a, b) => a.stock - b.stock || a.name.localeCompare(b.name, "zh-CN"))
    };
    return { metrics: metrics(state, merchantId, period, now), statuses, topDishes, trend, timeSlots, inventory,
      recent: [...allOrders].sort((a, b) => (orderDay(b) || 0) - (orderDay(a) || 0)).slice(0, 5) };
  }

  function dashboardChartMarkup(summary) {
    const safe = value => String(value).replace(/[&<>"']/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);
    const trendHasData = summary.trend.some(bucket => bucket.salesCents || bucket.orderCount);
    let trend = '<p class="demo-empty">所选时间暂无有效订单，趋势图将在产生订单后显示。</p>';
    if (trendHasData) {
      const maxSales = Math.max(1, ...summary.trend.map(bucket => bucket.salesCents));
      const maxOrders = Math.max(1, ...summary.trend.map(bucket => bucket.orderCount));
      const width = 640, left = 48, right = 18, top = 18, bottom = 174;
      const step = summary.trend.length > 1 ? (width - left - right) / (summary.trend.length - 1) : 0;
      const points = summary.trend.map((bucket, index) => ({
        ...bucket, x: left + step * index, y: bottom - bucket.salesCents / maxSales * (bottom - top),
        barHeight: bucket.orderCount / maxOrders * 54
      }));
      const line = points.map(point => `${point.x.toFixed(1)},${point.y.toFixed(1)}`).join(" ");
      const area = `M ${points[0].x.toFixed(1)} ${bottom} L ${line.replaceAll(" ", " L ")} L ${points.at(-1).x.toFixed(1)} ${bottom} Z`;
      const bars = points.map(point => `<rect x="${(point.x - 9).toFixed(1)}" y="${(bottom - point.barHeight).toFixed(1)}" width="18" height="${point.barHeight.toFixed(1)}" rx="4"><title>${safe(point.label)}：${point.orderCount} 个有效订单</title></rect>`).join("");
      const dots = points.map(point => `<circle cx="${point.x.toFixed(1)}" cy="${point.y.toFixed(1)}" r="4"><title>${safe(point.label)}：${money(point.salesCents)}</title></circle>`).join("");
      trend = `<div class="demo-chart-legend"><span><i class="legend-sales"></i>销售额（元）</span><span><i class="legend-orders"></i>有效订单（单）</span></div><svg class="demo-combo-chart" viewBox="0 0 640 210" role="img" aria-label="销售额面积折线与有效订单柱形趋势"><line x1="48" y1="174" x2="622" y2="174"></line><path class="chart-area" d="${area}"></path><g class="chart-bars">${bars}</g><polyline class="chart-line" points="${line}"></polyline><g class="chart-dots">${dots}</g></svg><div class="demo-trend-values">${points.map(point => `<span><small>${safe(point.label)}</small><b>${money(point.salesCents)}</b><em>${point.orderCount} 单</em></span>`).join("")}</div>`;
    }
    const statusEntries = Object.entries(summary.statuses);
    const statusTotal = statusEntries.reduce((sum, [, count]) => sum + count, 0);
    const statusColors = { PLACED: "#0072b2", PREPARING: "#e69f00", COMPLETED: "#009e73", CANCELLED: "#cc79a7" };
    let cursor = 0;
    const slices = statusEntries.map(([status, count]) => { const start = cursor; cursor += statusTotal ? count / statusTotal * 360 : 0; return `${statusColors[status]} ${start.toFixed(1)}deg ${cursor.toFixed(1)}deg`; }).join(",");
    const status = statusTotal ? `<div class="demo-donut-wrap"><div class="demo-donut" role="img" aria-label="订单状态构成，共 ${statusTotal} 单" style="--segments:conic-gradient(${slices})"><span><b>${statusTotal}</b><small>全部订单</small></span></div><div class="demo-chart-list">${statusEntries.map(([key, count]) => `<div><span><i style="--swatch:${statusColors[key]}"></i>${statusNames[key]}</span><b>${count} 单</b><small>${Math.round(count / statusTotal * 100)}%</small></div>`).join("")}</div></div>` : '<p class="demo-empty">所选时间暂无订单。</p>';
    const topMax = Math.max(1, ...summary.topDishes.map(dish => dish.qty));
    const top = summary.topDishes.length ? summary.topDishes.map((dish, index) => `<div class="demo-rank-row"><span class="rank-number">${index + 1}</span><div><span><b>${safe(dish.name)}</b><small>${money(dish.cents)}</small></span><i><em style="width:${Math.round(dish.qty / topMax * 100)}%"></em></i></div><strong>${dish.qty} 份</strong></div>`).join("") : '<p class="demo-empty">所选时间暂无有效销售。</p>';
    const slotMax = Math.max(1, ...summary.timeSlots.map(slot => slot.orderCount));
    const time = summary.timeSlots.some(slot => slot.orderCount) ? summary.timeSlots.map(slot => `<div class="demo-slot-row"><span><b>${safe(slot.label)}</b><small>${safe(slot.hint)}</small></span><i><em style="width:${Math.round(slot.orderCount / slotMax * 100)}%"></em></i><strong>${slot.orderCount} 单<small>${money(slot.salesCents)}</small></strong></div>`).join("") : '<p class="demo-empty">所选时间暂无有效订单。</p>';
    const inventoryTotal = summary.inventory.normal + summary.inventory.low + summary.inventory.out;
    const inventory = inventoryTotal ? `<div class="demo-stock-bar" role="img" aria-label="库存健康：正常 ${summary.inventory.normal}，低库存 ${summary.inventory.low}，售罄 ${summary.inventory.out}"><i class="stock-normal" style="width:${summary.inventory.normal / inventoryTotal * 100}%"></i><i class="stock-low" style="width:${summary.inventory.low / inventoryTotal * 100}%"></i><i class="stock-out" style="width:${summary.inventory.out / inventoryTotal * 100}%"></i></div><div class="demo-stock-legend"><span><i class="stock-normal"></i>正常 <b>${summary.inventory.normal}</b></span><span><i class="stock-low"></i>低库存 <b>${summary.inventory.low}</b></span><span><i class="stock-out"></i>售罄 <b>${summary.inventory.out}</b></span></div><div class="demo-stock-alerts">${summary.inventory.alerts.slice(0, 5).map(dish => `<div><span>${safe(dish.name)}</span><b>${dish.stock === 0 ? "售罄" : `${dish.stock} 份`}</b></div>`).join("") || '<p class="demo-empty">当前没有库存提醒。</p>'}</div>` : '<p class="demo-empty">当前商家还没有菜品。</p>';
    return { trend, status, top, time, inventory };
  }

  const core = { initialState, upgradeSeed, upgradeSeedV3, displayOrderId, addToCart, changeCart, previewOrder, checkout, cancelOrder, advanceOrder, adjustStock, toggleSale, saveDish, registerAccount, metrics, dailySales, dashboardSummary, dashboardChartMarkup, csvOrders };
  if (typeof module !== "undefined" && module.exports) module.exports = core;
  if (typeof document === "undefined") return;

  const $ = id => document.getElementById(id);
  const esc = value => String(value).replace(/[&<>"']/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);
  let state;
  let storageAvailable = true;
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    state = raw ? JSON.parse(raw) : initialState();
    if (!state || !Array.isArray(state.dishes) || !Array.isArray(state.orders) || !state.cart || !Number.isInteger(state.nextOrder) || !Number.isInteger(state.nextDish)) state = initialState();
  } catch (_) { state = initialState(); storageAvailable = false; }
  if (!state.merchants || !Array.isArray(state.accounts)) {
    const seed = initialState();
    state.merchants = seed.merchants;
    state.accounts = seed.accounts;
    state.nextAccount = seed.nextAccount;
    state.nextMerchant = seed.nextMerchant;
  }
  if (!Number.isInteger(state.nextAccount)) state.nextAccount = state.accounts.length + 1;
  if (!Number.isInteger(state.nextMerchant)) state.nextMerchant = Object.keys(state.merchants).length + 1;
  for (const order of state.orders) if (!order.studentId) order.studentId = "U-STUDENT";
  const seedUpgradeNeeded = state.seedVersion !== 3;
  upgradeSeed(state);
  upgradeSeedV3(state);
  let desiredRole = "STUDENT";
  let currentUserId = null;
  try { currentUserId = sessionStorage.getItem(SESSION_KEY); } catch (_) { /* Session storage can be unavailable. */ }
  if (!state.accounts.some(account => account.id === currentUserId)) currentUserId = null;
  let merchantId = "m1";
  let preview = null;
  let dashboardPeriod = 7;
  let menuMerchant = "all";
  let menuCategory = "all";
  let menuQuery = "";
  let showAllStudentOrders = false;
  let merchantOrderStatus = "all";
  let showAllMerchantOrders = false;
  function currentUser() { return state.accounts.find(account => account.id === currentUserId) || null; }
  function requireRole(role) {
    const account = currentUser();
    if (!account || account.role !== role) throw new Error(role === "STUDENT" ? "请先进入学生账号。" : "请先进入商家账号。");
    return account;
  }
  if (currentUser()) {
    desiredRole = currentUser().role;
    merchantId = currentUser().merchantId || "m1";
  }
  function setUser(account) {
    const previous = currentUserId;
    currentUserId = account ? account.id : null;
    if (previous !== currentUserId) { state.cart = {}; preview = null; showAllStudentOrders = false; showAllMerchantOrders = false; clearEdit(); }
    if (account) { desiredRole = account.role; merchantId = account.merchantId || "m1"; }
    try {
      if (account) sessionStorage.setItem(SESSION_KEY, account.id);
      else sessionStorage.removeItem(SESSION_KEY);
    } catch (_) { /* In-memory session still works. */ }
    persist();
    render();
  }

  function persist() {
    if (!storageAvailable) return;
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state)); }
    catch (_) { storageAvailable = false; notice("本机存储不可用；刷新页面后当前操作可能丢失。", true); }
  }
  function notice(message, error = false) {
    const box = $("demo-notice");
    box.textContent = message;
    box.classList.toggle("is-error", error);
    box.hidden = false;
  }
  function act(callback, successMessage) {
    try { callback(); persist(); render(); notice((typeof successMessage === "function" ? successMessage() : successMessage) + (storageAvailable ? "" : " 本地存储不可用，刷新后可能丢失。")); }
    catch (error) { notice(error.message || "操作未完成。", true); }
  }
  function orderMarkup(order, merchantView = false) {
    const items = order.lines.map(line => esc(line.name) + " × " + line.qty).join("、");
    const details = order.lines.map(line => `<li>${esc(line.name)} × ${line.qty} · ${money(line.cents)} / 份 · 小计 ${money(line.cents * line.qty)}</li>`).join("");
    const next = order.status === "PLACED" ? "开始制作" : "标记完成";
    const action = merchantView && ["PLACED", "PREPARING"].includes(order.status)
      ? `<button type="button" data-action="advance" data-id="${esc(order.id)}">${next}</button>`
      : !merchantView && order.status === "PLACED" ? `<button type="button" data-action="cancel-order" data-id="${esc(order.id)}">取消订单</button>` : "";
    return `<div class="demo-order"><div><strong>${esc(displayOrderId(order.id))} · ${esc(state.merchants[order.merchantId] || "示例商家")}</strong><p>${items}</p><small>${esc(order.createdAt)}</small><details class="demo-order-details"><summary>查看明细</summary><ul>${details}</ul></details></div><div class="demo-order-right"><strong>${money(order.totalCents)}</strong><span class="demo-status ${order.status === "CANCELLED" ? "cancelled" : order.status === "PREPARING" ? "preparing" : ""}">${statusNames[order.status] || "未知"}</span>${action}</div></div>`;
  }
  function renderStudent() {
    const storeSelect = $("demo-store-filter");
    storeSelect.innerHTML = '<option value="all">全部商家</option>' + Object.entries(state.merchants).map(([id, name]) => `<option value="${esc(id)}">${esc(name)}</option>`).join("");
    if (![...storeSelect.options].some(option => option.value === menuMerchant)) menuMerchant = "all";
    storeSelect.value = menuMerchant;
    const categories = [...new Set(state.dishes.filter(dish => dish.onSale).map(dish => dish.category || "主食"))];
    const categorySelect = $("demo-category-filter");
    categorySelect.innerHTML = '<option value="all">全部分类</option>' + categories.map(category => `<option value="${esc(category)}">${esc(category)}</option>`).join("");
    if (![...categorySelect.options].some(option => option.value === menuCategory)) menuCategory = "all";
    categorySelect.value = menuCategory;
    const visibleDishes = state.dishes.filter(dish => dish.onSale && (menuMerchant === "all" || dish.merchantId === menuMerchant) && (menuCategory === "all" || dish.category === menuCategory) && dish.name.toLocaleLowerCase().includes(menuQuery.toLocaleLowerCase()));
    $("demo-menu-count").textContent = `找到 ${visibleDishes.length} 道菜`;
    $("demo-menu").innerHTML = visibleDishes.map(dish => `<article class="demo-dish"><span class="icon" aria-hidden="true">${esc(dish.icon || "🍽️")}</span><h4>${esc(dish.name)}</h4><p>${esc(state.merchants[dish.merchantId] || "示例商家")} · ${esc(dish.category || "主食")}</p><div class="demo-dish-bottom"><span><strong>${money(dish.cents)}</strong><small>库存 ${dish.stock} 份</small></span><button type="button" data-action="add" data-id="${esc(dish.id)}" ${dish.stock < 1 ? "disabled" : ""}>${dish.stock < 1 ? "已售罄" : "加入餐篮"}</button></div></article>`).join("") || '<p class="demo-empty">没有符合条件的菜品。</p>';
    const lines = cartLines(state);
    $("demo-cart-lines").innerHTML = lines.length ? lines.map(({ dish, qty }) => `<div class="demo-cart-line"><span><strong>${esc(dish.name)}</strong><small>${money(dish.cents)} × ${qty}</small></span><span class="demo-qty"><button type="button" aria-label="减少${esc(dish.name)}" data-action="minus" data-id="${esc(dish.id)}">−</button>${qty}<button type="button" aria-label="增加${esc(dish.name)}" data-action="plus" data-id="${esc(dish.id)}">+</button></span></div>`).join("") : '<p class="demo-empty">餐篮还是空的。</p>';
    $("demo-cart-total").innerHTML = `<span>合计</span><strong>${money(lines.reduce((sum, line) => sum + line.dish.cents * line.qty, 0))}</strong>`;
    $("demo-clear-cart").disabled = !lines.length;
    $("demo-preview").disabled = !lines.length;
    $("demo-preview-box").hidden = !preview;
    $("demo-checkout").hidden = !preview;
    $("demo-checkout").disabled = !lines.length;
    if (preview) $("demo-preview-box").textContent = `核对：${state.merchants[preview.merchantId]} · ${preview.lines.map(line => `${line.name} × ${line.qty}`).join("、")}，合计 ${money(preview.totalCents)}。确认后提交订单。`;
    const studentOrders = state.orders.filter(order => order.studentId === currentUserId);
    $("demo-student-count").textContent = `${studentOrders.length} 单`;
    $("demo-student-orders").innerHTML = (showAllStudentOrders ? studentOrders : studentOrders.slice(0, 8)).map(order => orderMarkup(order)).join("") || '<p class="demo-empty">暂无订单。</p>';
    $("demo-student-more").hidden = studentOrders.length <= 8;
    $("demo-student-more").textContent = showAllStudentOrders ? "收起订单" : `查看全部 ${studentOrders.length} 单`;
  }
  function renderMerchant() {
    $("demo-merchant-label").textContent = state.merchants[merchantId] || "示例店铺";
    const summary = dashboardSummary(state, merchantId, dashboardPeriod);
    const m = summary.metrics;
    for (const days of [7, 30]) {
      const button = $("demo-period-" + days);
      button.classList.toggle("is-active", dashboardPeriod === days);
      button.setAttribute("aria-pressed", dashboardPeriod === days);
    }
    $("demo-metrics").innerHTML = [["有效销售额", money(m.salesCents)], ["有效 / 总订单", `${m.orderCount} / ${m.totalOrderCount}`], ["售出份数", m.salesQty], ["客单价", money(m.averageCents)], ["取消订单", `${m.cancelledCount} · ${m.cancellationPercent}%`], ["在售菜品", m.onSaleCount], ["低库存", m.lowStockCount], ["已缺货", m.outOfStockCount]].map(([label, value]) => `<div class="demo-metric"><small>${label}</small><strong>${value}</strong></div>`).join("");
    const charts = dashboardChartMarkup(summary);
    $("demo-daily-sales").innerHTML = charts.trend;
    $("demo-inventory").innerHTML = charts.inventory;
    $("demo-top-dishes").innerHTML = charts.top;
    $("demo-status-breakdown").innerHTML = charts.status;
    $("demo-time-slots").innerHTML = charts.time;
    $("demo-recent-orders").innerHTML = summary.recent.length ? summary.recent.map(order => `<div class="demo-insight-row"><span>${esc(displayOrderId(order.id))}<small>${esc(state.accounts.find(account => account.id === order.studentId)?.name || "学生")} · ${esc(order.createdAt)}</small></span><b>${statusNames[order.status]} · ${money(order.totalCents)}</b></div>`).join("") : '<p class="demo-empty">所选时间暂无订单。</p>';
    $("demo-merchant-dishes").innerHTML = state.dishes.filter(dish => dish.merchantId === merchantId).map(dish => `<div class="demo-manage-row"><span><strong>${esc(dish.name)}</strong><small>${esc(dish.category || "主食")} · ${money(dish.cents)} · 库存 ${dish.stock} · ${dish.onSale ? "在售" : "已下架"}</small></span><span class="demo-dish-controls"><button type="button" data-action="stock-minus" data-id="${esc(dish.id)}" ${dish.stock < 1 ? "disabled" : ""}>−1</button><button type="button" data-action="stock-plus" data-id="${esc(dish.id)}">+1</button><button type="button" data-action="edit" data-id="${esc(dish.id)}">编辑</button><button type="button" data-action="toggle" data-id="${esc(dish.id)}">${dish.onSale ? "下架" : "上架"}</button></span></div>`).join("") || '<p class="demo-empty">暂无菜品。</p>';
    $("demo-order-status").value = merchantOrderStatus;
    const merchantOrders = state.orders.filter(order => order.merchantId === merchantId && (merchantOrderStatus === "all" || order.status === merchantOrderStatus));
    $("demo-order-count").textContent = `符合条件 ${merchantOrders.length} 单`;
    $("demo-merchant-orders").innerHTML = (showAllMerchantOrders ? merchantOrders : merchantOrders.slice(0, 10)).map(order => orderMarkup(order, true)).join("") || '<p class="demo-empty">当前条件下暂无订单。</p>';
    $("demo-order-more").hidden = merchantOrders.length <= 10;
    $("demo-order-more").textContent = showAllMerchantOrders ? "收起订单" : `查看全部 ${merchantOrders.length} 单`;
  }
  function renderAnalysis() {
    const account = currentUser();
    const orders = account ? state.orders.filter(order => account.role === "STUDENT" ? order.studentId === account.id : order.merchantId === account.merchantId) : [];
    const dishes = account && account.role === "MERCHANT" ? state.dishes.filter(dish => dish.merchantId === account.merchantId) : state.dishes;
    const valid = orders.filter(order => order.status !== "CANCELLED");
    const sales = new Map();
    for (const order of valid) for (const line of order.lines) {
      const entry = sales.get(line.dishId) || { name: line.name, qty: 0, cents: 0 };
      entry.qty += line.qty; entry.cents += line.cents * line.qty; sales.set(line.dishId, entry);
    }
    const top = [...sales.values()].sort((a, b) => b.qty - a.qty || b.cents - a.cents).slice(0, 5);
    const reminders = orders.slice(0, 4).map(order => `<li>${esc(displayOrderId(order.id))}：${statusNames[order.status] || "未知"}${order.status === "COMPLETED" ? " · 可取餐" : ""}</li>`).join("");
    const low = dishes.filter(dish => dish.stock < 15).sort((a, b) => a.stock - b.stock).slice(0, 5);
    const totals = new Map();
    for (const order of valid) {
      const match = /^(\d{4})[-/](\d{1,2})[-/](\d{1,2})/.exec(order.createdAt || "");
      const day = match ? `${match[1]}-${match[2].padStart(2, "0")}-${match[3].padStart(2, "0")}` : "历史记录";
      totals.set(day, (totals.get(day) || 0) + order.totalCents);
    }
    const daily = [...totals].sort((a, b) => a[0].localeCompare(b[0], "zh-CN")).slice(-7);
    const max = Math.max(0, ...daily.map(([, cents]) => cents));
    $("demo-export-csv").disabled = !account || !orders.length;
    $("demo-analysis-output").innerHTML = `<div class="demo-analysis-card"><h3>热销菜品</h3>${top.length ? `<ul>${top.map(item => `<li>${esc(item.name)}：${item.qty} 份 · ${money(item.cents)}</li>`).join("")}</ul>` : "<p>进入账号并下单后，这里会显示销量。</p>"}</div><div class="demo-analysis-card"><h3>销售额趋势</h3>${daily.length ? `<div class="analysis-chart">${daily.map(([day, cents]) => `<div class="analysis-chart-row"><span>${esc(day === "历史记录" ? day : day.slice(5))}</span><span class="analysis-chart-track"><i style="width:${Math.round(cents / max * 100)}%"></i></span><b>${money(cents)}</b></div>`).join("")}</div>` : "<p>暂无有效销售记录。</p>"}</div><div class="demo-analysis-card"><h3>状态与库存</h3>${reminders || low.length ? `<ul>${reminders}${low.map(dish => `<li>${dish.stock === 0 ? "缺货" : "低库存"}：${esc(dish.name)} 剩余 ${dish.stock} 份</li>`).join("")}</ul>` : "<p>暂无新提醒。</p>"}</div>`;
  }
  function render() {
    const account = currentUser();
    $("demo-auth").hidden = !!account;
    $("demo-student").hidden = !account || account.role !== "STUDENT";
    $("demo-merchant").hidden = !account || account.role !== "MERCHANT";
    $("demo-logout").hidden = !account;
    $("demo-current-user").textContent = account ? `${account.name} · ${account.role === "STUDENT" ? "学生" : state.merchants[account.merchantId]}` : "选择账号进入";
    $("demo-student-tab").classList.toggle("is-active", desiredRole === "STUDENT");
    $("demo-merchant-tab").classList.toggle("is-active", desiredRole === "MERCHANT");
    $("demo-student-tab").setAttribute("aria-pressed", desiredRole === "STUDENT");
    $("demo-merchant-tab").setAttribute("aria-pressed", desiredRole === "MERCHANT");
    const select = $("demo-account-select");
    const selected = select.value;
    select.innerHTML = state.accounts.filter(item => item.role === desiredRole).map(item => `<option value="${esc(item.id)}">${esc(item.name)}${item.merchantId ? ` · ${esc(state.merchants[item.merchantId])}` : ""}</option>`).join("");
    if ([...select.options].some(option => option.value === selected)) select.value = selected;
    $("demo-account-role").value = desiredRole;
    $("demo-store-field").hidden = desiredRole !== "MERCHANT";
    $("demo-store-name").required = desiredRole === "MERCHANT";
    renderStudent(); renderMerchant(); renderAnalysis();
  }
  function clearEdit() {
    $("demo-dish-form").reset();
    $("demo-edit-id").value = "";
    $("demo-form-title").textContent = "添加菜品";
    $("demo-cancel-edit").hidden = true;
  }
  function chooseRole(role) {
    if (currentUser() && currentUser().role !== role) setUser(null);
    desiredRole = role;
    render();
  }
  $("demo-student-tab").addEventListener("click", () => chooseRole("STUDENT"));
  $("demo-merchant-tab").addEventListener("click", () => chooseRole("MERCHANT"));
  $("demo-store-filter").addEventListener("change", event => { menuMerchant = event.target.value; renderStudent(); });
  $("demo-category-filter").addEventListener("change", event => { menuCategory = event.target.value; renderStudent(); });
  $("demo-menu-search").addEventListener("input", event => { menuQuery = event.target.value.trim(); renderStudent(); });
  $("demo-student-more").addEventListener("click", () => { showAllStudentOrders = !showAllStudentOrders; renderStudent(); });
  $("demo-order-status").addEventListener("change", event => { merchantOrderStatus = event.target.value; showAllMerchantOrders = false; renderMerchant(); });
  $("demo-order-more").addEventListener("click", () => { showAllMerchantOrders = !showAllMerchantOrders; renderMerchant(); });
  for (const days of [7, 30]) $("demo-period-" + days).addEventListener("click", () => { dashboardPeriod = days; renderMerchant(); });
  $("demo-account-role").addEventListener("change", event => { desiredRole = event.target.value; render(); });
  $("demo-login-form").addEventListener("submit", event => {
    event.preventDefault();
    const account = state.accounts.find(item => item.id === $("demo-account-select").value && item.role === desiredRole);
    if (!account) return notice("请选择一个账号。", true);
    setUser(account);
    notice(`已进入 ${account.name} 的工作台。`);
  });
  $("demo-register-form").addEventListener("submit", event => {
    event.preventDefault();
    try {
      const account = registerAccount(state, $("demo-account-name").value, $("demo-account-role").value, $("demo-store-name").value);
      desiredRole = account.role;
      $("demo-register-form").reset();
      persist(); render();
      $("demo-account-select").value = account.id;
      notice(`${account.name} 已创建。请点击“进入系统”。`);
    } catch (error) { notice(error.message, true); }
  });
  $("demo-logout").addEventListener("click", () => { setUser(null); notice("已退出账号。"); });
  $("demo-cancel-edit").addEventListener("click", clearEdit);
  $("demo-export-csv").addEventListener("click", () => {
    const account = currentUser();
    if (!account) return notice("请先进入账号。", true);
    const orders = state.orders.filter(order => account.role === "STUDENT" ? order.studentId === account.id : order.merchantId === account.merchantId);
    if (!orders.length) return notice("目前没有可导出的订单。", true);
    const file = new Blob([csvOrders(orders, state.merchants)], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(file);
    const link = document.createElement("a");
    link.href = url; link.download = "canteen-orders.csv"; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
  $("demo-reset").addEventListener("click", () => {
    if (!window.confirm("要重置本设备上的全部样例数据吗？")) return;
    state = initialState(); preview = null; clearEdit(); setUser(null); notice("样例数据已重置。");
  });
  $("demo-clear-cart").addEventListener("click", () => act(() => { requireRole("STUDENT"); state.cart = {}; preview = null; }, "餐篮已清空。"));
  $("demo-preview").addEventListener("click", () => act(() => { requireRole("STUDENT"); preview = previewOrder(state); }, "订单已核对，请确认后下单。"));
  $("demo-checkout").addEventListener("click", () => { let orderId = ""; act(() => { if (!currentUser() || currentUser().role !== "STUDENT" || !preview) throw new Error("请先使用学生账号核对订单。"); orderId = checkout(state, currentUserId).id; preview = null; }, () => `订单 ${displayOrderId(orderId)} 已创建，库存已扣减。`); });
  $("demo-dish-form").addEventListener("submit", event => {
    event.preventDefault();
    const price = Number($("demo-dish-price").value);
    const stock = Number($("demo-dish-stock").value);
    act(() => {
      requireRole("MERCHANT");
      saveDish(state, merchantId, $("demo-edit-id").value, $("demo-dish-name").value, Math.round(price * 100), stock, $("demo-dish-category").value);
      preview = null;
      clearEdit();
    }, "菜品已保存。");
  });
  $("demo").addEventListener("click", event => {
    const button = event.target.closest("button[data-action]");
    if (!button) return;
    const id = button.dataset.id;
    switch (button.dataset.action) {
      case "add": case "plus": act(() => { requireRole("STUDENT"); addToCart(state, id); preview = null; }, "已加入餐篮。"); break;
      case "minus": act(() => { requireRole("STUDENT"); changeCart(state, id, -1); preview = null; }, "餐篮已更新。"); break;
      case "cancel-order": act(() => { requireRole("STUDENT"); cancelOrder(state, id, currentUserId); }, "订单已取消，库存已恢复。"); break;
      case "advance": act(() => { requireRole("MERCHANT"); advanceOrder(state, id, merchantId); }, "订单状态已更新。"); break;
      case "stock-minus": act(() => { requireRole("MERCHANT"); adjustStock(state, id, merchantId, -1); preview = null; }, "库存已更新。"); break;
      case "stock-plus": act(() => { requireRole("MERCHANT"); adjustStock(state, id, merchantId, 1); preview = null; }, "库存已更新。"); break;
      case "toggle": act(() => { requireRole("MERCHANT"); toggleSale(state, id, merchantId); preview = null; }, "菜品销售状态已更新。"); break;
      case "edit": {
        try { requireRole("MERCHANT"); } catch (error) { notice(error.message, true); return; }
        const dish = findDish(state, id);
        if (!dish || dish.merchantId !== merchantId) return;
        $("demo-edit-id").value = dish.id;
        $("demo-dish-name").value = dish.name;
        $("demo-dish-category").value = dish.category || "主食";
        $("demo-dish-price").value = (dish.cents / 100).toFixed(2);
        $("demo-dish-stock").value = dish.stock;
        $("demo-form-title").textContent = "编辑菜品";
        $("demo-cancel-edit").hidden = false;
        $("demo-dish-name").focus();
        break;
      }
    }
  });
  if (seedUpgradeNeeded) persist();
  if (!storageAvailable) notice("本机存储不可用；刷新页面后当前操作可能丢失。", true);
  render();
})();
