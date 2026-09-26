const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { webcrypto } = require('node:crypto');

const source = fs.readFileSync(path.join(__dirname, '../src/main/resources/static/app.js'), 'utf8');

async function pageWithRequest(testRequest, storage = new Map()) {
  const elements = new Map();
  const document = {
    getElementById(id) {
      if (!elements.has(id)) {
        elements.set(id, {
          hidden: false, disabled: false, textContent: '', innerHTML: '',
          classList: { toggle() {} },
          listeners: {},
          addEventListener(type, listener) { this.listeners[type] = listener; },
          setAttribute() {}, removeAttribute() {}
        });
      }
      return elements.get(id);
    },
    querySelectorAll() { return []; }
  };
  const fetch = async (url) => url.endsWith('/auth/csrf')
    ? { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ token: 'test-token' }) }
    : { ok: false, status: 401, headers: { get: () => 'application/json' }, json: async () => ({ error: '请先登录' }) };
  const sessionStorage = {
    getItem(key) { return storage.get(key) ?? null; },
    setItem(key, value) { storage.set(key, value); },
    removeItem(key) { storage.delete(key); }
  };
  const context = vm.createContext({ document, fetch, console, testRequest, sessionStorage, crypto: webcrypto, Uint8Array });
  vm.runInContext(source, context);
  await new Promise((resolve) => setImmediate(resolve));
  vm.runInContext('request = testRequest', context);
  const state = vm.runInContext('state', context);
  state.user = { user_id: 'USR000003', username: 'test_student', role: 'STUDENT' };
  state.preview = { merchantId: 'USR000001', lines: [], totalAmount: '9.80' };
  state.basket.set('DSH000001', 1);
  return { elements, state, storage, context, click: () => elements.get('submit-order').listeners.click() };
}

test('连续点击只发送一次下单请求，成功后清空餐篮', async () => {
  let resolveOrder;
  const pendingOrder = new Promise((resolve) => { resolveOrder = resolve; });
  let postCount = 0;
  const keys = [];
  const page = await pageWithRequest(async (url, options) => {
    if (url === '/orders' && options?.method === 'POST') { postCount++; keys.push(options.headers['Idempotency-Key']); return pendingOrder; }
    if (url === '/dishes') return [];
    return [];
  });
  const first = page.click();
  const second = page.click();
  assert.equal(postCount, 1);
  assert.match(keys[0], /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.equal(page.elements.get('submit-order').disabled, true);
  resolveOrder({ orderId: 'ORD000001' });
  await Promise.all([first, second]);
  assert.equal(postCount, 1);
  assert.equal(page.state.basket.size, 0);
  assert.equal(page.state.preview, null);
  assert.equal(page.state.orderSubmitting, false);
  assert.match(page.elements.get('notice').textContent, /下单成功：ORD000001/);
});

test('明确失败后解除锁定并可重试，重试成功只生成一单', async () => {
  let postCount = 0;
  const keys = [];
  const page = await pageWithRequest(async (url, options) => {
    if (url === '/orders' && options?.method === 'POST') {
      postCount++;
      keys.push(options.headers['Idempotency-Key']);
      if (postCount === 1) throw Object.assign(new Error('库存不足'), { status: 400 });
      return { orderId: 'ORD000002' };
    }
    if (url === '/dishes') return [];
    return [];
  });
  await page.click();
  assert.equal(postCount, 1);
  assert.equal(page.elements.get('submit-order').disabled, false);
  assert.equal(page.state.basket.size, 1);
  assert.notEqual(page.state.preview, null);
  await page.click();
  assert.equal(postCount, 2);
  assert.equal(keys[0], keys[1]);
  assert.equal(page.state.basket.size, 0);
  assert.match(page.elements.get('notice').textContent, /下单成功：ORD000002/);
});

test('结果不明时隐藏确认按钮，提示先核对订单', async () => {
  let postCount = 0;
  const keys = [];
  const page = await pageWithRequest(async (url, options) => {
    if (url === '/orders' && options?.method === 'POST') { postCount++; keys.push(options.headers['Idempotency-Key']); throw new Error('网络中断'); }
    return [];
  });
  await page.click();
  await page.click();
  assert.equal(postCount, 1);
  assert.equal(page.state.preview, null);
  assert.equal(page.elements.get('submit-order').hidden, true);
  assert.match(page.elements.get('notice').textContent, /先查看“我的订单”/);
  page.state.preview = { merchantId: 'USR000001', lines: [], totalAmount: '9.80' };
  page.elements.get('submit-order').hidden = false;
  await page.click();
  assert.equal(postCount, 2);
  assert.equal(keys[0], keys[1]);
});

test('购物车变化使用新键，结果不明后的键可从会话存储恢复', async () => {
  const storage = new Map();
  const keys = [];
  const page = await pageWithRequest(async (url, options) => {
    if (url === '/orders' && options?.method === 'POST') { keys.push(options.headers['Idempotency-Key']); throw new Error('网络中断'); }
    return [];
  }, storage);
  await page.click();
  assert.equal(keys.length, 1);
  let newKey;
  const restored = await pageWithRequest(async (url, options) => {
    if (url === '/orders' && options?.method === 'POST') { newKey = options.headers['Idempotency-Key']; throw new Error('网络中断'); }
    return [];
  }, storage);
  vm.runInContext("restoreOrderIntent('USR000003')", restored.context);
  assert.equal(restored.state.orderIntent.key, keys[0]);
  assert.equal(restored.state.basket.get('DSH000001'), 1);
  restored.state.dishes = [{ dishId: 'DSH000001', dishName: '测试菜', merchantId: 'USR000001', unitPrice: '9.80', stock: 5 }];
  vm.runInContext("changeBasket('DSH000001', 1)", restored.context);
  restored.state.preview = { merchantId: 'USR000001', lines: [], totalAmount: '19.60' };
  restored.elements.get('submit-order').hidden = false;
  await restored.click();
  assert.notEqual(newKey, keys[0]);
  assert.equal(restored.state.orderIntent.key, newKey);
});

test('新订单使用新键，409 保留原键并提示查单', async () => {
  const keys = [];
  let response = 'success';
  const page = await pageWithRequest(async (url, options) => {
    if (url === '/orders' && options?.method === 'POST') {
      keys.push(options.headers['Idempotency-Key']);
      if (response === 'conflict') throw Object.assign(new Error('请求冲突'), { status: 409 });
      return { orderId: `ORD00000${keys.length}` };
    }
    return [];
  });
  await page.click();
  assert.equal(page.state.orderIntent, null);
  page.state.basket.set('DSH000001', 1);
  page.state.preview = { merchantId: 'USR000001', lines: [], totalAmount: '9.80' };
  page.elements.get('submit-order').hidden = false;
  response = 'conflict';
  await page.click();
  assert.notEqual(keys[0], keys[1]);
  assert.equal(page.state.orderIntent.key, keys[1]);
  assert.equal(page.state.orderIntent.needsReview, true);
  assert.match(page.elements.get('notice').textContent, /已用于不同订单内容.*先查看“我的订单”/);
});

test('响应丢失后同键重放返回原订单，页面只保留一单', async () => {
  const ledger = new Map();
  const keys = [];
  let loseFirstResponse = true;
  const page = await pageWithRequest(async (url, options) => {
    if (url === '/orders' && options?.method === 'POST') {
      const key = options.headers['Idempotency-Key'];
      keys.push(key);
      if (!ledger.has(key)) ledger.set(key, { orderId: 'ORD000001' });
      if (loseFirstResponse) { loseFirstResponse = false; throw new Error('网络中断'); }
      return ledger.get(key);
    }
    return [];
  });
  await page.click();
  assert.equal(ledger.size, 1);
  page.state.preview = { merchantId: 'USR000001', lines: [], totalAmount: '9.80' };
  page.elements.get('submit-order').hidden = false;
  await page.click();
  assert.equal(keys[0], keys[1]);
  assert.equal(ledger.size, 1);
  assert.equal(page.state.basket.size, 0);
  assert.match(page.elements.get('notice').textContent, /下单成功：ORD000001/);
});

test('查单刷新也失败时保留结果不明的处置提示', async () => {
  const page = await pageWithRequest(async () => { throw new Error('Failed to fetch'); });
  await page.click();
  assert.match(page.elements.get('notice').textContent, /下单结果暂时无法确认.*先查看“我的订单”/);
});
