-- Enable replication privileges
ALTER USER postgres WITH REPLICATION;

-- 1. Users Table
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID DEFAULT gen_random_uuid() NOT NULL,
    username VARCHAR(64) UNIQUE NOT NULL,
    email VARCHAR(128) UNIQUE NOT NULL,
    full_name VARCHAR(128) NOT NULL,
    tier VARCHAR(32) DEFAULT 'STANDARD' NOT NULL,
    account_balance NUMERIC(12, 2) DEFAULT 0.00 NOT NULL,
    kyc_verified BOOLEAN DEFAULT TRUE NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 2. Merchants / Retailers Table
CREATE TABLE IF NOT EXISTS merchants (
    id BIGSERIAL PRIMARY KEY,
    merchant_code VARCHAR(64) UNIQUE NOT NULL,
    business_name VARCHAR(128) NOT NULL,
    category VARCHAR(64) NOT NULL,
    rating NUMERIC(3, 2) DEFAULT 4.75 NOT NULL,
    commission_rate NUMERIC(5, 4) DEFAULT 0.0250 NOT NULL,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    country_code VARCHAR(4) DEFAULT 'US' NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 3. Products Catalog Table
CREATE TABLE IF NOT EXISTS products (
    id BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT REFERENCES merchants(id) ON DELETE CASCADE,
    sku VARCHAR(64) UNIQUE NOT NULL,
    title VARCHAR(255) NOT NULL,
    category VARCHAR(64) NOT NULL,
    price NUMERIC(10, 2) NOT NULL,
    cost_price NUMERIC(10, 2) NOT NULL,
    inventory_count INTEGER DEFAULT 500 NOT NULL,
    is_published BOOLEAN DEFAULT TRUE NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 4. High-traffic Orders Table
CREATE TABLE IF NOT EXISTS orders (
    id BIGSERIAL PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    amount NUMERIC(10, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 5. Order Line Items Table
CREATE TABLE IF NOT EXISTS order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT REFERENCES orders(id) ON DELETE CASCADE,
    product_id BIGINT REFERENCES products(id) ON DELETE RESTRICT,
    unit_price NUMERIC(10, 2) NOT NULL,
    quantity INTEGER DEFAULT 1 NOT NULL,
    subtotal NUMERIC(10, 2) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 6. Payment Transactions Ledger
CREATE TABLE IF NOT EXISTS payment_transactions (
    id BIGSERIAL PRIMARY KEY,
    transaction_ref VARCHAR(64) UNIQUE NOT NULL,
    order_id BIGINT REFERENCES orders(id) ON DELETE CASCADE,
    user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    amount NUMERIC(12, 2) NOT NULL,
    fee_amount NUMERIC(10, 2) DEFAULT 0.00 NOT NULL,
    gateway VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(64) UNIQUE NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 7. High-Volume Audit Logs
CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGSERIAL PRIMARY KEY,
    entity_name VARCHAR(32) NOT NULL,
    entity_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    performed_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- REPLICA IDENTITY FULL ensures all column values are emitted during UPDATE and DELETE in the WAL
ALTER TABLE users REPLICA IDENTITY FULL;
ALTER TABLE merchants REPLICA IDENTITY FULL;
ALTER TABLE products REPLICA IDENTITY FULL;
ALTER TABLE orders REPLICA IDENTITY FULL;
ALTER TABLE order_items REPLICA IDENTITY FULL;
ALTER TABLE payment_transactions REPLICA IDENTITY FULL;
ALTER TABLE audit_logs REPLICA IDENTITY FULL;

-- Seed Users
INSERT INTO users (username, email, full_name, tier, account_balance)
SELECT
    'user_' || i,
    'user_' || i || '@example.com',
    'User Name ' || i,
    CASE (i % 3) WHEN 0 THEN 'VIP' WHEN 1 THEN 'ENTERPRISE' ELSE 'STANDARD' END,
    ROUND((random() * 2000 + 50)::numeric, 2)
FROM generate_series(1, 100) AS i
ON CONFLICT (username) DO NOTHING;

-- Seed Merchants
INSERT INTO merchants (merchant_code, business_name, category, rating, commission_rate, is_active, country_code)
SELECT
    'MCH_' || LPAD(i::text, 6, '0'),
    'Merchant Store ' || i || ' Ltd',
    CASE (i % 4) WHEN 0 THEN 'Electronics' WHEN 1 THEN 'Apparel' WHEN 2 THEN 'Home & Living' ELSE 'Books' END,
    ROUND((random() * 2 + 3)::numeric, 2),
    0.0250,
    TRUE,
    CASE (i % 3) WHEN 0 THEN 'US' WHEN 1 THEN 'GB' ELSE 'DE' END
FROM generate_series(1, 100) AS i
ON CONFLICT (merchant_code) DO NOTHING;

-- Seed Products
INSERT INTO products (merchant_id, sku, title, category, price, cost_price, inventory_count)
SELECT
    (i % 100) + 1,
    'SKU-' || LPAD(i::text, 8, '0'),
    'Product Title #' || i,
    CASE (i % 4) WHEN 0 THEN 'Electronics' WHEN 1 THEN 'Apparel' WHEN 2 THEN 'Home & Living' ELSE 'Books' END,
    ROUND((random() * 200 + 10)::numeric, 2),
    ROUND((random() * 50 + 5)::numeric, 2),
    ROUND((random() * 1000 + 10)::numeric, 0)::int
FROM generate_series(1, 200) AS i
ON CONFLICT (sku) DO NOTHING;

-- Seed Orders (1,000 rows)
INSERT INTO orders (customer_id, amount, status)
SELECT 
    'cust_' || (i % 500),
    ROUND((random() * 500 + 10)::numeric, 2),
    CASE (i % 4)
        WHEN 0 THEN 'PENDING'
        WHEN 1 THEN 'PROCESSING'
        WHEN 2 THEN 'COMPLETED'
        ELSE 'CANCELLED'
    END
FROM generate_series(1, 1000) AS i;
