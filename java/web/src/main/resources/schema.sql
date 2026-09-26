CREATE TABLE IF NOT EXISTS users (
 user_id VARCHAR(9) PRIMARY KEY, username VARCHAR(30) NOT NULL UNIQUE,
 password_salt VARCHAR(128) NOT NULL, password_hash VARCHAR(128) NOT NULL,
 role VARCHAR(8) NOT NULL CHECK (role IN ('STUDENT','MERCHANT')),
 user_status VARCHAR(8) NOT NULL CHECK (user_status IN ('ACTIVE','DISABLED')),
 created_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS dishes (
 dish_id VARCHAR(9) PRIMARY KEY, merchant_id VARCHAR(9) NOT NULL REFERENCES users(user_id),
 dish_name VARCHAR(50) NOT NULL, unit_price DECIMAL(12,2) NOT NULL CHECK (unit_price > 0),
 stock INTEGER NOT NULL CHECK (stock >= 0),
 sale_status VARCHAR(8) NOT NULL CHECK (sale_status IN ('ON_SALE','OFF_SALE')),
 created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS orders (
 order_id VARCHAR(9) PRIMARY KEY, student_id VARCHAR(9) NOT NULL REFERENCES users(user_id),
 merchant_id VARCHAR(9) NOT NULL REFERENCES users(user_id), order_time TIMESTAMP NOT NULL,
 order_status VARCHAR(10) NOT NULL CHECK (order_status IN ('PLACED','PREPARING','COMPLETED','CANCELLED')),
 total_amount DECIMAL(12,2) NOT NULL CHECK (total_amount > 0), status_updated_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS order_items (
 order_item_id VARCHAR(9) PRIMARY KEY, order_id VARCHAR(9) NOT NULL REFERENCES orders(order_id),
 dish_id VARCHAR(9) NOT NULL REFERENCES dishes(dish_id), dish_name VARCHAR(50) NOT NULL,
 quantity INTEGER NOT NULL CHECK (quantity > 0), unit_price DECIMAL(12,2) NOT NULL CHECK (unit_price > 0),
 subtotal DECIMAL(12,2) NOT NULL CHECK (subtotal > 0), UNIQUE(order_id,dish_id)
);
CREATE TABLE IF NOT EXISTS order_idempotency (
 student_id VARCHAR(9) NOT NULL REFERENCES users(user_id),
 request_key VARCHAR(36) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 order_id VARCHAR(9) NOT NULL UNIQUE REFERENCES orders(order_id),
 created_at TIMESTAMP NOT NULL,
 PRIMARY KEY(student_id,request_key)
);
CREATE TABLE IF NOT EXISTS business_revision (
 id INTEGER PRIMARY KEY CHECK (id=1), revision BIGINT NOT NULL CHECK (revision>=0),
 imported_at TIMESTAMP, source_digest VARCHAR(64), last_export_revision BIGINT NOT NULL DEFAULT -1,
 export_error VARCHAR(1000)
);
INSERT INTO business_revision(id, revision, imported_at, source_digest, last_export_revision)
 SELECT 1, 0, NULL, NULL, -1 WHERE NOT EXISTS (SELECT 1 FROM business_revision WHERE id=1);
CREATE TABLE IF NOT EXISTS id_counters (prefix VARCHAR(3) PRIMARY KEY, next_number INTEGER NOT NULL CHECK (next_number BETWEEN 1 AND 1000000));
