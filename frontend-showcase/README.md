# Standalone frontend showcase

Open `index.html` directly in a browser.

The initial state contains two fictional merchants, 31 dishes, three student personas, and roughly one month of lunch/dinner orders. It covers every order status plus popular, slow-moving, low-stock, sold-out, and off-sale dishes. The dashboard derives its totals, trends, rankings, and inventory warnings from this local synthetic state.

The merchant dashboard uses native SVG and CSS for a sales/order combination chart, order-status donut, dish ranking, meal-period distribution, and inventory-health view. Every chart follows the selected merchant and 7/30-day range; current inventory is labeled separately.

This directory is a browser-only interaction prototype. It uses synthetic data and browser storage. It does not call the Java API, authenticate against the server, or write to H2 or MySQL. The real integrated UI is located in `java/web/src/main/resources/static/`.

Run its pure data and rendering tests with `node frontend-showcase/dashboard.test.cjs` from the repository root.
