CREATE TABLE users (
 user_id VARCHAR(9) PRIMARY KEY, username VARCHAR(30) NOT NULL UNIQUE,
 password_salt VARCHAR(128) NOT NULL, password_hash VARCHAR(128) NOT NULL,
 role VARCHAR(8) NOT NULL CHECK (role IN ('STUDENT','MERCHANT')),
 user_status VARCHAR(8) NOT NULL CHECK (user_status IN ('ACTIVE','DISABLED')),
 created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE dishes (
 dish_id VARCHAR(9) PRIMARY KEY, merchant_id VARCHAR(9) NOT NULL,
 dish_name VARCHAR(50) NOT NULL, unit_price DECIMAL(12,2) NOT NULL CHECK (unit_price > 0),
 stock INT NOT NULL CHECK (stock >= 0), sale_status VARCHAR(8) NOT NULL CHECK (sale_status IN ('ON_SALE','OFF_SALE')),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY (merchant_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE orders (
 order_id VARCHAR(9) PRIMARY KEY, student_id VARCHAR(9) NOT NULL,
 merchant_id VARCHAR(9) NOT NULL, order_time DATETIME(6) NOT NULL,
 order_status VARCHAR(10) NOT NULL CHECK (order_status IN ('PLACED','PREPARING','COMPLETED','CANCELLED')),
 total_amount DECIMAL(12,2) NOT NULL CHECK (total_amount > 0), status_updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY (student_id) REFERENCES users(user_id), FOREIGN KEY (merchant_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE order_items (
 order_item_id VARCHAR(9) PRIMARY KEY, order_id VARCHAR(9) NOT NULL,
 dish_id VARCHAR(9) NOT NULL, dish_name VARCHAR(50) NOT NULL,
 quantity INT NOT NULL CHECK (quantity > 0), unit_price DECIMAL(12,2) NOT NULL CHECK (unit_price > 0),
 subtotal DECIMAL(12,2) NOT NULL CHECK (subtotal > 0), UNIQUE(order_id,dish_id),
 FOREIGN KEY (order_id) REFERENCES orders(order_id), FOREIGN KEY (dish_id) REFERENCES dishes(dish_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE order_idempotency (
 student_id VARCHAR(9) NOT NULL, request_key VARCHAR(36) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, order_id VARCHAR(9) NOT NULL UNIQUE,
 created_at DATETIME(6) NOT NULL, PRIMARY KEY(student_id,request_key),
 FOREIGN KEY (student_id) REFERENCES users(user_id), FOREIGN KEY (order_id) REFERENCES orders(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE business_revision (
 id INT PRIMARY KEY CHECK (id=1), revision BIGINT NOT NULL CHECK (revision>=0),
 imported_at DATETIME(6), source_digest VARCHAR(64), last_export_revision BIGINT NOT NULL DEFAULT -1,
 export_error VARCHAR(1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE id_counters (
 prefix VARCHAR(3) PRIMARY KEY, next_number INT NOT NULL CHECK (next_number BETWEEN 1 AND 1000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
