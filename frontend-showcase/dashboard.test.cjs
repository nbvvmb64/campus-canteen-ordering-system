const test = require('node:test');
const assert = require('node:assert/strict');
const core = require('./demo.js');

test('7/30 天看板使用同一商家与同一订单口径', () => {
  const state = core.initialState();
  const before = JSON.stringify(state);
  const now = new Date();
  const seven = core.dashboardSummary(state, 'm1', 7, now);
  const thirty = core.dashboardSummary(state, 'm1', 30, now);
  const other = core.dashboardSummary(state, 'm2', 7, now);

  assert.ok(seven.metrics.totalOrderCount < thirty.metrics.totalOrderCount);
  assert.equal(Object.values(seven.statuses).reduce((a, b) => a + b, 0), seven.metrics.totalOrderCount);
  assert.equal(seven.trend.reduce((sum, row) => sum + row.orderCount, 0), seven.metrics.orderCount);
  assert.equal(seven.timeSlots.reduce((sum, row) => sum + row.orderCount, 0), seven.metrics.orderCount);
  assert.equal(seven.timeSlots.reduce((sum, row) => sum + row.salesCents, 0), seven.metrics.salesCents);
  assert.notDeepEqual(seven.topDishes, other.topDishes);
  assert.equal(JSON.stringify(state), before, 'summary must remain a pure read');
});

test('图表渲染包含可访问名称、单位和可读数值', () => {
  const summary = core.dashboardSummary(core.initialState(), 'm1', 7, new Date());
  const charts = core.dashboardChartMarkup(summary);
  assert.match(charts.trend, /<svg[^>]+role="img"/);
  assert.match(charts.trend, /销售额（元）/);
  assert.match(charts.trend, /有效订单（单）/);
  assert.match(charts.status, /订单状态构成/);
  assert.match(charts.status, /已完成/);
  assert.match(charts.top, /份/);
  assert.match(charts.time, /午餐/);
  assert.match(charts.inventory, /库存健康/);
  assert.doesNotMatch(Object.values(charts).join(''), /NaN|undefined/);
});

test('下单、取消与库存调整立即反映到同一看板模型', () => {
  const state = core.initialState();
  const now = new Date();
  const dish = state.dishes.find(item => item.merchantId === 'm1' && item.onSale && item.stock >= 2);
  const openingStock = dish.stock;
  const before = core.dashboardSummary(state, 'm1', 7, now);

  core.addToCart(state, dish.id);
  const order = core.checkout(state, 'U-STUDENT');
  const afterCheckout = core.dashboardSummary(state, 'm1', 7, now);
  assert.equal(afterCheckout.metrics.orderCount, before.metrics.orderCount + 1);
  assert.equal(afterCheckout.metrics.totalOrderCount, before.metrics.totalOrderCount + 1);
  assert.equal(afterCheckout.metrics.salesCents, before.metrics.salesCents + order.totalCents);
  assert.equal(dish.stock, openingStock - 1);

  core.cancelOrder(state, order.id, 'U-STUDENT');
  const afterCancel = core.dashboardSummary(state, 'm1', 7, now);
  assert.equal(afterCancel.metrics.orderCount, before.metrics.orderCount);
  assert.equal(afterCancel.metrics.totalOrderCount, before.metrics.totalOrderCount + 1);
  assert.equal(afterCancel.metrics.cancelledCount, before.metrics.cancelledCount + 1);
  assert.equal(afterCancel.metrics.salesCents, before.metrics.salesCents);
  assert.equal(dish.stock, openingStock);

  const wasNormal = openingStock >= 15;
  core.adjustStock(state, dish.id, 'm1', -dish.stock);
  const afterStock = core.dashboardSummary(state, 'm1', 7, now);
  assert.equal(afterStock.inventory.out, before.inventory.out + 1);
  if (wasNormal) assert.equal(afterStock.inventory.normal, before.inventory.normal - 1);
});

test('无订单和无菜品时图表提供明确空状态', () => {
  const state = core.initialState();
  state.orders = [];
  state.dishes = state.dishes.filter(dish => dish.merchantId !== 'm1');
  const charts = core.dashboardChartMarkup(core.dashboardSummary(state, 'm1', 7, new Date()));
  assert.match(charts.trend, /暂无有效订单/);
  assert.match(charts.status, /暂无订单/);
  assert.match(charts.inventory, /还没有菜品/);
});
