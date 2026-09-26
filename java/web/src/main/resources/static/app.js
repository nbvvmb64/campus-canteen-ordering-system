"use strict";

const API = "/api/v1";
const INTENT_STORAGE = "canteen.order-intent.v1";
const $ = (id) => document.getElementById(id);
const state = { user: null, csrf: null, tab: null, dishes: [], orders: [], merchantDishes: [], basket: new Map(), preview: null, orderSubmitting: false, orderIntent: null };
const statusText = { PLACED: "已下单", PREPARING: "制作中", COMPLETED: "已完成", CANCELLED: "已取消", ON_SALE: "在售", OFF_SALE: "已下架" };
const money = (value) => `¥${Number(value).toFixed(2)}`;
const escapeHtml = (value) => String(value ?? "").replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);
const empty = (message) => `<div class="empty-state">${escapeHtml(message)}</div>`;
const dateText = (value) => value ? String(value).replace("T", " ").slice(0, 19) : "—";

function notice(message, error = false) {
  const box = $("notice");
  box.textContent = message;
  box.classList.toggle("is-error", error);
  box.hidden = false;
}
function clearNotice() { $("notice").hidden = true; }
function confirmAction(message) {
  const dialog = $("confirm-dialog");
  $("confirm-message").textContent = message;
  return new Promise((resolve) => {
    const finish = (accepted) => { dialog.close(); resolve(accepted); };
    $("confirm-no").onclick = () => finish(false);
    $("confirm-yes").onclick = () => finish(true);
    dialog.oncancel = (event) => { event.preventDefault(); finish(false); };
    dialog.showModal();
    $("confirm-no").focus();
  });
}
async function csrf() {
  const response = await fetch(`${API}/auth/csrf`, { credentials: "same-origin" });
  if (!response.ok) throw new Error("无法获取安全令牌，请刷新页面重试");
  const payload = await response.json();
  state.csrf = payload.token;
  if (payload.demo === "true") document.querySelector('#register-form select[name="role"] option[value="MERCHANT"]')?.remove();
}
async function request(path, { method = "GET", body, headers = {} } = {}) {
  if (method !== "GET" && !state.csrf) await csrf();
  const response = await fetch(`${API}${path}`, {
    method, credentials: "same-origin",
    headers: { ...(body === undefined ? {} : { "Content-Type": "application/json" }), ...(method === "GET" ? {} : { "X-CSRF-TOKEN": state.csrf }), ...headers },
    ...(body === undefined ? {} : { body: JSON.stringify(body) })
  });
  const contentType = response.headers.get("content-type") || "";
  const result = contentType.includes("application/json") ? await response.json() : null;
  if (!response.ok) {
    if (response.status === 401 && state.user) showAuth();
    const error = new Error(result?.error?.includes("?") ? "请求被拒绝，请检查身份或安全令牌" : result?.error || (response.status === 403 ? "操作被拒绝，请检查身份或刷新页面" : `请求失败 (${response.status})`));
    error.status = response.status;
    throw error;
  }
  return result;
}
async function action(work, success) {
  try { clearNotice(); await work(); if (success) notice(success); }
  catch (error) { notice(error.message || "操作失败", true); }
}
function showAuth() {
  state.user = null; state.csrf = null; state.tab = null; state.basket.clear(); state.preview = null; state.orderIntent = null;
  $("workspace-view").hidden = true; $("auth-view").hidden = false;
  $("current-user").hidden = true; $("logout-button").hidden = true;
}
function authMode(mode) {
  $("login-form").hidden = mode !== "login";
  $("register-form").hidden = mode !== "register";
  for (const name of ["login", "register"]) {
    const button = $(`show-${name}`);
    button.classList.toggle("is-active", name === mode);
    if (name === mode) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  }
}
function showWorkspace(user) {
  state.user = user;
  if (user.role === "STUDENT") restoreOrderIntent(user.user_id);
  $("auth-view").hidden = true; $("workspace-view").hidden = false;
  $("current-user").textContent = `${user.username} · ${user.role === "STUDENT" ? "学生" : "商家"}`;
  $("current-user").hidden = false; $("logout-button").hidden = false;
  const student = user.role === "STUDENT";
  $("role-label").textContent = student ? "学生工作台" : "商家工作台";
  $("workspace-title").textContent = student ? "吃饭这件小事，交给我们" : "今天的经营，一目了然";
  $("workspace-subtitle").textContent = student ? "浏览菜单、核对订单，随时查看处理进度。" : "管理菜品与订单，查看当前商家的实时数据。";
  const tabs = student ? [["student-menu", "浏览菜品"], ["student-orders", "我的订单"]] : [["merchant-dashboard", "经营概览"], ["merchant-dishes", "菜品管理"], ["merchant-orders", "订单处理"]];
  $("workspace-nav").innerHTML = tabs.map(([id, label]) => `<button type="button" class="nav-button" data-tab="${id}">${label}</button>`).join("");
  setTab(tabs[0][0]);
}
function setTab(id) {
  state.tab = id;
  document.querySelectorAll(".workspace-panel").forEach((panel) => { panel.hidden = panel.id !== id; });
  document.querySelectorAll(".nav-button").forEach((button) => {
    const active = button.dataset.tab === id;
    button.classList.toggle("is-active", active);
    if (active) button.setAttribute("aria-current", "page"); else button.removeAttribute("aria-current");
  });
  return action(refresh);
}
async function refresh() {
  if (!state.user) return;
  if (state.tab === "student-menu") { state.dishes = await request("/dishes"); renderMenu(); renderBasket(); }
  if (state.tab === "student-orders") { state.orders = await request("/orders"); renderOrders(false); }
  if (state.tab === "merchant-dashboard") renderDashboard(await request("/merchants/me/dashboard"));
  if (state.tab === "merchant-dishes") { state.merchantDishes = await request("/merchant/dishes"); renderMerchantDishes(); }
  if (state.tab === "merchant-orders") { state.orders = await request("/merchant/orders"); renderOrders(true); }
}
function renderMenu() {
  $("menu-count").textContent = `${state.dishes.length} 道菜品`;
  $("menu-list").innerHTML = state.dishes.length ? state.dishes.map((dish) => `<article class="dish-card">
    <div class="dish-card-top"><div><h3>${escapeHtml(dish.dishName)}</h3><p>商家 ${escapeHtml(dish.merchantId)}</p></div><span class="dish-icon" aria-hidden="true">食</span></div>
    <div class="dish-card-bottom"><div><div class="price"><small>¥</small>${Number(dish.unitPrice).toFixed(2)}</div><div class="stock-note ${dish.stock === 0 ? "is-out" : dish.stock < 15 ? "is-low" : ""}">库存 ${dish.stock} 份</div></div>
    <div class="dish-add"><label>数量<input class="quantity-input" type="number" min="1" max="${dish.stock}" step="1" value="1" data-quantity="${dish.dishId}" ${dish.stock === 0 ? "disabled" : ""}></label><button class="button button-primary button-small" type="button" data-add="${dish.dishId}" ${dish.stock === 0 ? "disabled" : ""}>加入</button></div></div></article>`).join("") : empty("目前没有在售菜品。");
}
function hidePreview() { state.preview = null; $("basket-preview").hidden = true; $("submit-order").hidden = true; }
function invalidatePreview() { hidePreview(); clearOrderIntent(); }
function renderBasket() {
  const rows = [...state.basket.entries()].map(([id, quantity]) => ({ dish: state.dishes.find((dish) => dish.dishId === id), quantity })).filter((row) => row.dish);
  const total = rows.reduce((sum, row) => sum + Number(row.dish.unitPrice) * row.quantity, 0);
  $("basket-list").innerHTML = rows.length ? rows.map(({ dish, quantity }) => `<div class="basket-row"><div><strong>${escapeHtml(dish.dishName)}</strong><small>${money(dish.unitPrice)} × ${quantity}</small></div><div class="basket-row-actions"><button class="round-button" type="button" data-decrease="${dish.dishId}" aria-label="减少 ${escapeHtml(dish.dishName)}">−</button><button class="round-button" type="button" data-increase="${dish.dishId}" aria-label="增加 ${escapeHtml(dish.dishName)}">+</button></div></div>`).join("") + `<div class="basket-total"><span>预计合计</span><strong>${money(total)}</strong></div>` : `<div class="basket-empty">餐篮还是空的，先选一道喜欢的菜吧。</div>`;
  $("preview-order").disabled = !rows.length;
  $("clear-basket").disabled = !rows.length;
  if (state.orderIntent?.needsReview && !state.preview && rows.length) {
    $("basket-preview").textContent = "上次下单结果尚不明确，请先查看“我的订单”；若确认未生成订单，可重新核对并提交当前餐篮。";
    $("basket-preview").hidden = false;
  }
}
function changeBasket(id, delta) {
  if (state.orderSubmitting) throw new Error("订单正在提交，请稍候");
  const dish = state.dishes.find((item) => item.dishId === id);
  if (!dish) throw new Error("菜品已变化，请刷新菜单");
  const existing = [...state.basket.keys()].find((key) => key !== id);
  const first = existing && state.dishes.find((item) => item.dishId === existing);
  if (delta > 0 && first && first.merchantId !== dish.merchantId) throw new Error("一张订单只能选择同一家商家的菜品，请先清空餐篮");
  const next = (state.basket.get(id) || 0) + delta;
  if (next > dish.stock) throw new Error(`「${dish.dishName}」库存只有 ${dish.stock} 份`);
  if (next > 0) state.basket.set(id, next); else state.basket.delete(id);
  invalidatePreview(); renderBasket();
}
function orderLines() { return [...state.basket].map(([dishId, quantity]) => ({ dishId, quantity })); }
function sameLines(a, b) {
  const canonical = (lines) => JSON.stringify(lines.map((line) => [line.dishId, line.quantity]).sort((x, y) => x[0].localeCompare(y[0])));
  return canonical(a) === canonical(b);
}
function storeOrderIntent(intent) {
  try { sessionStorage.setItem(INTENT_STORAGE, JSON.stringify(intent)); }
  catch (_) { throw new Error("浏览器无法保存下单标识，请允许此页面使用会话存储后重试"); }
  state.orderIntent = intent;
}
function clearOrderIntent() {
  const key = state.orderIntent?.key;
  state.orderIntent = null;
  if (!key) return;
  try {
    const stored = JSON.parse(sessionStorage.getItem(INTENT_STORAGE));
    if (stored?.key === key) sessionStorage.removeItem(INTENT_STORAGE);
  } catch (_) { /* 会话存储可能已被浏览器禁用 */ }
}
function restoreOrderIntent(userId) {
  state.orderIntent = null;
  try {
    const saved = JSON.parse(sessionStorage.getItem(INTENT_STORAGE));
    if (saved?.userId !== userId || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(saved.key)
        || !Array.isArray(saved.lines) || !saved.lines.length
        || !saved.lines.every((line) => typeof line.dishId === "string" && Number.isSafeInteger(line.quantity) && line.quantity > 0)) return;
    state.orderIntent = saved;
    state.basket = new Map(saved.lines.map((line) => [line.dishId, line.quantity]));
  } catch (_) { /* 无有效的待核对下单意图 */ }
}
function newUuidV4() {
  if (!globalThis.crypto?.getRandomValues) throw new Error("浏览器无法安全生成下单标识");
  const bytes = new Uint8Array(16);
  globalThis.crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
function orderIntent() {
  const lines = orderLines();
  if (state.orderIntent?.userId === state.user?.user_id && sameLines(state.orderIntent.lines, lines)) return state.orderIntent;
  const intent = { key: newUuidV4(), userId: state.user.user_id, lines, needsReview: false };
  storeOrderIntent(intent);
  return intent;
}
function markNeedsReview(intent) {
  intent.needsReview = true;
  try { storeOrderIntent(intent); } catch (_) { state.orderIntent = intent; }
}
function renderOrders(merchant) {
  const target = merchant ? "merchant-order-list" : "student-order-list";
  $(merchant ? "merchant-order-count" : "student-order-count").textContent = `${state.orders.length} 笔订单`;
  $(target).innerHTML = state.orders.length ? state.orders.map((order) => `<article class="order-card"><div class="order-head"><div><h3>订单 ${escapeHtml(order.orderId)}</h3><p>${escapeHtml(dateText(order.orderTime))} · ${merchant ? "学生" : "商家"} ${escapeHtml(merchant ? order.studentId : order.merchantId)}</p></div><div class="order-amount">${money(order.totalAmount)}</div></div>
    <div class="order-meta"><span class="status-pill status-${order.orderStatus}">${statusText[order.orderStatus] || escapeHtml(order.orderStatus)}</span><button type="button" class="button button-secondary button-small" data-items="${order.orderId}" aria-expanded="false">查看明细</button>
    ${!merchant && order.orderStatus === "PLACED" ? `<button type="button" class="button button-danger button-small" data-cancel="${order.orderId}">取消订单</button>` : ""}
    ${merchant && order.orderStatus === "PLACED" ? `<button type="button" class="button button-primary button-small" data-status="${order.orderId}" data-next="PREPARING">开始制作</button>` : ""}
    ${merchant && order.orderStatus === "PREPARING" ? `<button type="button" class="button button-primary button-small" data-status="${order.orderId}" data-next="COMPLETED">标记完成</button>` : ""}</div><div id="items-${order.orderId}" hidden></div></article>`).join("") : empty("目前还没有订单。");
}
function renderMerchantDishes() {
  $("merchant-dish-count").textContent = `${state.merchantDishes.length} 道菜品`;
  $("merchant-dish-list").innerHTML = state.merchantDishes.length ? state.merchantDishes.map((dish) => `<article class="management-card"><div class="management-head"><div><h3>${escapeHtml(dish.dishName)}</h3><p>${escapeHtml(dish.dishId)} · 库存 ${dish.stock} 份</p></div><span class="management-price">${money(dish.unitPrice)}</span></div><div class="management-meta"><span class="status-pill status-${dish.saleStatus}">${statusText[dish.saleStatus]}</span></div><div class="management-actions"><button type="button" class="button button-secondary button-small" data-edit="${dish.dishId}">编辑</button><button type="button" class="button ${dish.saleStatus === "ON_SALE" ? "button-danger" : "button-secondary"} button-small" data-sale="${dish.dishId}" data-next="${dish.saleStatus === "ON_SALE" ? "OFF_SALE" : "ON_SALE"}">${dish.saleStatus === "ON_SALE" ? "下架" : "上架"}</button></div></article>`).join("") : empty("还没有菜品，先添加第一道菜吧。");
}
function resetDishForm() {
  $("dish-form").reset(); $("dish-form").elements.dishId.value = "";
  $("dish-form-title").textContent = "添加菜品"; $("cancel-dish-edit").hidden = true;
}
function renderDashboard(data) {
  $("dashboard-as-of").textContent = `截至 ${dateText(data.as_of)}`;
  const s = data.summary;
  const metrics = [["有效销售额", money(s.valid_sales_total), "不含已取消订单"], ["有效订单", s.valid_order_count, `总订单 ${s.total_order_count} 笔`], ["售出份数", s.valid_sales_quantity, "有效订单菜品数量"], ["客单价", money(s.average_valid_order_amount), "每笔有效订单"], ["取消订单", s.cancelled_order_count, `取消率 ${s.cancellation_rate_percent}%`], ["在售菜品", s.on_sale_dish_count, "当前可售"], ["低库存", s.low_stock_dish_count, "库存 1～14 份"], ["已缺货", s.out_of_stock_dish_count, "库存为 0"]];
  $("merchant-metrics").innerHTML = metrics.map(([label, value, detail]) => `<div class="metric-card"><div class="metric-label">${label}</div><strong>${escapeHtml(value)}</strong><small>${escapeHtml(detail)}</small></div>`).join("");
  const daily = data.sales.daily;
  const max = Math.max(0, ...daily.map((row) => Number(row.valid_sales_amount)));
  $("daily-sales").innerHTML = daily.length ? daily.map((row) => `<div class="sales-row"><span>${escapeHtml(row.date)}</span><div class="sales-track"><span style="width:${max ? Math.round(Number(row.valid_sales_amount) / max * 100) : 0}%"></span></div><strong>${money(row.valid_sales_amount)}</strong></div>`).join("") : empty("暂无销售记录。");
  const inventory = data.inventory.items.filter((row) => row.stock_status !== "NORMAL");
  $("inventory-list").innerHTML = inventory.length ? inventory.map((row) => `<div class="inventory-row"><div><strong>${escapeHtml(row.dish_name)}</strong><small>${row.stock_status === "OUT_OF_STOCK" ? "已缺货" : "低库存"} · 建议补 ${row.recommended_restock_quantity} 份</small></div><span>${row.stock} 份</span></div>`).join("") : empty("当前没有低库存菜品。");
}

$("show-login").addEventListener("click", () => authMode("login"));
$("show-register").addEventListener("click", () => authMode("register"));
$("login-form").addEventListener("submit", (event) => { event.preventDefault(); const formElement = event.currentTarget; action(async () => { const form = new FormData(formElement); const user = await request("/auth/login", { method: "POST", body: { username: String(form.get("username")).trim(), password: form.get("password") } }); formElement.reset(); showWorkspace(user); }, "登录成功"); });
$("register-form").addEventListener("submit", (event) => { event.preventDefault(); const formElement = event.currentTarget; action(async () => { const form = new FormData(formElement); await request("/auth/register", { method: "POST", body: { username: String(form.get("username")).trim(), password: form.get("password"), role: form.get("role") } }); formElement.reset(); authMode("login"); }, "注册成功，请登录"); });
$("logout-button").addEventListener("click", () => action(async () => { await request("/auth/logout", { method: "POST" }); state.csrf = null; showAuth(); await csrf(); }, "已退出登录"));
$("workspace-nav").addEventListener("click", (event) => { const button = event.target.closest("[data-tab]"); if (button) setTab(button.dataset.tab); });
$("refresh-button").addEventListener("click", () => action(refresh, "数据已刷新"));
$("menu-list").addEventListener("click", (event) => { const button = event.target.closest("[data-add]"); if (!button) return; action(() => { const input = document.querySelector(`[data-quantity="${button.dataset.add}"]`); const quantity = Number(input.value); if (!Number.isSafeInteger(quantity) || quantity < 1) throw new Error("请输入大于 0 的整数数量"); changeBasket(button.dataset.add, quantity); }, "已加入餐篮"); });
$("basket-list").addEventListener("click", (event) => { const button = event.target.closest("[data-increase], [data-decrease]"); if (button) action(() => changeBasket(button.dataset.increase || button.dataset.decrease, button.dataset.increase ? 1 : -1)); });
$("clear-basket").addEventListener("click", () => { if (state.orderSubmitting) return; state.basket.clear(); invalidatePreview(); renderBasket(); });
$("preview-order").addEventListener("click", () => { if (state.orderSubmitting) return; action(async () => { state.preview = await request("/orders/preview", { method: "POST", body: { lines: orderLines() } }); $("basket-preview").textContent = `服务端核对：${state.preview.lines.map((line) => `${line.dishName} × ${line.quantity}`).join("、")}，合计 ${money(state.preview.totalAmount)}。请确认后下单。`; $("basket-preview").hidden = false; $("submit-order").hidden = false; }, "订单已核对"); });
$("submit-order").addEventListener("click", async () => {
  if (state.orderSubmitting) return;
  if ($("submit-order").hidden) return;
  if (!state.preview) { notice("请先核对订单", true); return; }
  let intent;
  try { intent = orderIntent(); }
  catch (error) { notice(error.message, true); return; }
  const button = $("submit-order");
  state.orderSubmitting = true;
  button.disabled = true;
  clearNotice();
  try {
    const order = await request("/orders", { method: "POST", body: { lines: intent.lines }, headers: { "Idempotency-Key": intent.key } });
    state.basket.clear();
    invalidatePreview();
    try {
      state.dishes = await request("/dishes");
      renderMenu(); renderBasket(); await setTab("student-orders");
    } catch (_) {
      renderBasket();
    }
    notice(`下单成功：${order.orderId}`);
  } catch (error) {
    if (error.status === 409 || !error.status || error.status >= 500) {
      markNeedsReview(intent);
      hidePreview();
      await setTab("student-orders");
      notice(error.status === 409
        ? "下单标识已用于不同订单内容。请先查看“我的订单”；如需提交不同内容，请修改餐篮并重新核对。"
        : "下单结果暂时无法确认，请先查看“我的订单”；确认未生成后，重新核对餐篮会沿用同一标识。", true);
    } else notice(error.message || "下单失败", true);
  } finally {
    state.orderSubmitting = false;
    button.disabled = false;
  }
});
for (const listId of ["student-order-list", "merchant-order-list"]) $(listId).addEventListener("click", (event) => { const button = event.target.closest("[data-items], [data-cancel], [data-status]"); if (!button) return; action(async () => {
  if (button.dataset.items) { const box = $(`items-${button.dataset.items}`); if (!box.hidden) { box.hidden = true; button.setAttribute("aria-expanded", "false"); return; } const items = await request(`/orders/${encodeURIComponent(button.dataset.items)}/items`); box.innerHTML = `<ul class="details-list">${items.map((item) => `<li>${escapeHtml(item.dishName)} × ${item.quantity} · ${money(item.unitPrice)} / 份 · 小计 ${money(item.subtotal)}</li>`).join("")}</ul>`; box.hidden = false; button.setAttribute("aria-expanded", "true"); return; }
  if (button.dataset.cancel) { if (!await confirmAction("确定取消这笔订单吗？库存将恢复。")) return; await request(`/orders/${encodeURIComponent(button.dataset.cancel)}/cancel`, { method: "POST" }); await refresh(); notice("订单已取消"); return; }
  await request(`/merchant/orders/${encodeURIComponent(button.dataset.status)}/status`, { method: "PUT", body: { orderStatus: button.dataset.next } }); await refresh(); notice("订单状态已更新");
}); });
$("dish-form").addEventListener("submit", (event) => { event.preventDefault(); const formElement = event.currentTarget; action(async () => { const form = new FormData(formElement); const dishId = form.get("dishId"); const price = String(form.get("unitPrice")); const stock = Number(form.get("stock")); if (!/^\d+(\.\d{1,2})?$/.test(price) || Number(price) <= 0) throw new Error("价格必须大于 0，且最多两位小数"); if (!Number.isSafeInteger(stock) || stock < 0) throw new Error("库存必须是非负整数"); await request(dishId ? `/merchant/dishes/${encodeURIComponent(dishId)}` : "/merchant/dishes", { method: dishId ? "PUT" : "POST", body: { dishName: String(form.get("dishName")).trim(), unitPrice: price, stock } }); resetDishForm(); await refresh(); }, "菜品已保存"); });
$("cancel-dish-edit").addEventListener("click", resetDishForm);
$("merchant-dish-list").addEventListener("click", (event) => { const button = event.target.closest("[data-edit], [data-sale]"); if (!button) return; action(async () => {
  if (button.dataset.edit) { const dish = state.merchantDishes.find((item) => item.dishId === button.dataset.edit); if (!dish) return; const form = $("dish-form").elements; form.dishId.value = dish.dishId; form.dishName.value = dish.dishName; form.unitPrice.value = dish.unitPrice; form.stock.value = dish.stock; $("dish-form-title").textContent = `编辑 ${dish.dishName}`; $("cancel-dish-edit").hidden = false; $("dish-form").scrollIntoView({ behavior: "smooth", block: "start" }); form.dishName.focus(); return; }
  const id = button.dataset.sale; const next = button.dataset.next;
  if (next === "OFF_SALE" && !await confirmAction("确定下架这道菜吗？已有订单会保留。")) return;
  await request(`/merchant/dishes/${encodeURIComponent(id)}/sale-status`, { method: "PUT", body: { saleStatus: next } }); await refresh(); notice(next === "ON_SALE" ? "菜品已上架" : "菜品已下架");
}); });

action(async () => { await csrf(); try { showWorkspace(await request("/auth/me")); } catch (error) { if (error.status !== 401) throw error; showAuth(); } });
