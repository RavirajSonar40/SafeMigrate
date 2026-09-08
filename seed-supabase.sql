-- ==============================================================================
-- Enterprise Production E-Commerce & Financial Ledger Schema for Supabase
-- Target: Zero-Downtime SafeMigrate Replication Testing
-- Total Estimated Rows: ~332,000 rows
-- Total Estimated Size: ~140 MB (Well within Supabase 500 MB Free Tier)
-- ==============================================================================

BEGIN;

-- 1. Users / Accounts Table (20,000 rows)
DROP TABLE IF EXISTS audit_logs CASCADE;
DROP TABLE IF EXISTS payment_transactions CASCADE;
DROP TABLE IF EXISTS order_items CASCADE;
DROP TABLE IF EXISTS orders CASCADE;
DROP TABLE IF EXISTS products CASCADE;
DROP TABLE IF EXISTS merchants CASCADE;
DROP TABLE IF EXISTS users CASCADE;

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID DEFAULT gen_random_uuid() NOT NULL,
    username VARCHAR(64) UNIQUE NOT NULL,
    email VARCHAR(128) UNIQUE NOT NULL,
    full_name VARCHAR(128) NOT NULL,
    tier VARCHAR(32) DEFAULT 'STANDARD' NOT NULL,
    account_balance NUMERIC(12, 2) DEFAULT 0.00 NOT NULL,
    kyc_verified BOOLEAN DEFAULT TRUE NOT NULL,
    metadata JSONB DEFAULT '{"notifications": true, "theme": "dark"}'::jsonb NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 2. Merchants / Retailers Table (2,000 rows)
CREATE TABLE merchants (
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

-- 3. Products Catalog Table (10,000 rows)
CREATE TABLE products (
    id BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT REFERENCES merchants(id) ON DELETE CASCADE NOT NULL,
    sku VARCHAR(64) UNIQUE NOT NULL,
    title VARCHAR(255) NOT NULL,
    category VARCHAR(64) NOT NULL,
    price NUMERIC(10, 2) NOT NULL,
    cost_price NUMERIC(10, 2) NOT NULL,
    inventory_count INTEGER DEFAULT 500 NOT NULL,
    is_published BOOLEAN DEFAULT TRUE NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 4. Orders Table (50,000 rows)
CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    order_number VARCHAR(64) UNIQUE NOT NULL,
    user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT NOT NULL,
    merchant_id BIGINT REFERENCES merchants(id) ON DELETE RESTRICT NOT NULL,
    total_amount NUMERIC(12, 2) NOT NULL,
    tax_amount NUMERIC(10, 2) DEFAULT 0.00 NOT NULL,
    shipping_fee NUMERIC(10, 2) DEFAULT 0.00 NOT NULL,
    currency VARCHAR(8) DEFAULT 'USD' NOT NULL,
    status VARCHAR(32) NOT NULL,
    payment_method VARCHAR(32) NOT NULL,
    delivery_notes TEXT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 5. Order Line Items Table (100,000 rows)
CREATE TABLE order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT REFERENCES orders(id) ON DELETE CASCADE NOT NULL,
    product_id BIGINT REFERENCES products(id) ON DELETE RESTRICT NOT NULL,
    unit_price NUMERIC(10, 2) NOT NULL,
    quantity INTEGER DEFAULT 1 NOT NULL,
    subtotal NUMERIC(10, 2) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 6. Payment Transactions Ledger (50,000 rows)
CREATE TABLE payment_transactions (
    id BIGSERIAL PRIMARY KEY,
    transaction_ref VARCHAR(64) UNIQUE NOT NULL,
    order_id BIGINT REFERENCES orders(id) ON DELETE CASCADE NOT NULL,
    user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    fee_amount NUMERIC(10, 2) DEFAULT 0.00 NOT NULL,
    gateway VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(64) UNIQUE NOT NULL,
    metadata JSONB,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 7. High-Volume Audit Logs (100,000 rows)
CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    entity_name VARCHAR(32) NOT NULL,
    entity_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    performed_by VARCHAR(64) NOT NULL,
    payload JSONB,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- Indexes for realistic high query performance
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_products_merchant ON products(merchant_id);
CREATE INDEX idx_products_category ON products(category);
CREATE INDEX idx_orders_user ON orders(user_id);
CREATE INDEX idx_orders_merchant ON orders(merchant_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_payment_order ON payment_transactions(order_id);
CREATE INDEX idx_audit_entity ON audit_logs(entity_name, entity_id);

-- Enable FULL Replica Identity for strict zero-loss WAL CDC streaming
ALTER TABLE users REPLICA IDENTITY FULL;
ALTER TABLE merchants REPLICA IDENTITY FULL;
ALTER TABLE products REPLICA IDENTITY FULL;
ALTER TABLE orders REPLICA IDENTITY FULL;
ALTER TABLE order_items REPLICA IDENTITY FULL;
ALTER TABLE payment_transactions REPLICA IDENTITY FULL;
ALTER TABLE audit_logs REPLICA IDENTITY FULL;

COMMIT;

-- ==============================================================================
-- SEEDING GENERATORS (Executed via high-speed set-returning generate_series)
-- ==============================================================================

-- 1. Seed 20,000 Users
INSERT INTO users (username, email, full_name, tier, account_balance, kyc_verified, metadata, created_at)
SELECT
    'user_' || i,
    'user_' || i || '@enterprise-cloud.io',
    'Customer ' || i,
    CASE (i % 3)
        WHEN 0 THEN 'ENTERPRISE'
        WHEN 1 THEN 'PREMIUM'
        ELSE 'STANDARD'
    END,
    ROUND((random() * 2500 + 50)::numeric, 2),
    (i % 10 != 0),
    json_build_object('region', CASE (i % 4) WHEN 0 THEN 'us-east' WHEN 1 THEN 'eu-central' WHEN 2 THEN 'ap-southeast' ELSE 'sa-east' END, 'mfa_enabled', (i % 2 = 0))::jsonb,
    NOW() - (random() * interval '90 days')
FROM generate_series(1, 20000) AS i;

-- 2. Seed 2,000 Merchants
INSERT INTO merchants (merchant_code, business_name, category, rating, commission_rate, is_active, country_code, created_at)
SELECT
    'MCH_' || LPAD(i::text, 6, '0'),
    'Merchant Store ' || i || ' Ltd',
    CASE (i % 5)
        WHEN 0 THEN 'Electronics'
        WHEN 1 THEN 'Apparel'
        WHEN 2 THEN 'Home & Kitchen'
        WHEN 3 THEN 'Industrial Tools'
        ELSE 'Health & Beauty'
    END,
    ROUND((random() * 1.5 + 3.5)::numeric, 2),
    ROUND((random() * 0.03 + 0.015)::numeric, 4),
    (i % 25 != 0),
    CASE (i % 3) WHEN 0 THEN 'US' WHEN 1 THEN 'DE' ELSE 'SG' END,
    NOW() - (random() * interval '180 days')
FROM generate_series(1, 20000 / 10) AS i;

-- 3. Seed 10,000 Products
INSERT INTO products (merchant_id, sku, title, category, price, cost_price, inventory_count, is_published, created_at)
SELECT
    ((i % 2000) + 1),
    'SKU-' || LPAD(i::text, 8, '0'),
    'Enterprise Component #' || i,
    CASE (i % 6)
        WHEN 0 THEN 'Hardware'
        WHEN 1 THEN 'Sensors'
        WHEN 2 THEN 'Cables'
        WHEN 3 THEN 'Power Modules'
        WHEN 4 THEN 'Storage Drives'
        ELSE 'Network Adapters'
    END,
    ROUND((random() * 450 + 20)::numeric, 2),
    ROUND((random() * 200 + 10)::numeric, 2),
    ROUND((random() * 1000 + 50)::numeric, 0),
    TRUE,
    NOW() - (random() * interval '60 days')
FROM generate_series(1, 10000) AS i;

-- 4. Seed 50,000 Orders
INSERT INTO orders (order_number, user_id, merchant_id, total_amount, tax_amount, shipping_fee, currency, status, payment_method, delivery_notes, created_at, updated_at)
SELECT
    'ORD-' || LPAD(i::text, 8, '0'),
    ((i % 20000) + 1),
    ((i % 2000) + 1),
    ROUND((random() * 850 + 40)::numeric, 2),
    ROUND((random() * 40 + 5)::numeric, 2),
    ROUND((random() * 25 + 5)::numeric, 2),
    'USD',
    CASE (i % 5)
        WHEN 0 THEN 'COMPLETED'
        WHEN 1 THEN 'PROCESSING'
        WHEN 2 THEN 'SHIPPED'
        WHEN 3 THEN 'DELIVERED'
        ELSE 'PENDING'
    END,
    CASE (i % 4)
        WHEN 0 THEN 'STRIPE_CARD'
        WHEN 1 THEN 'APPLE_PAY'
        WHEN 2 THEN 'BANK_WIRE'
        ELSE 'CRYPTO_USDC'
    END,
    'Fast courier delivery requested. Leave with front desk.',
    NOW() - (random() * interval '45 days'),
    NOW() - (random() * interval '45 days')
FROM generate_series(1, 50000) AS i;

-- 5. Seed 100,000 Order Items (2 items per order)
INSERT INTO order_items (order_id, product_id, unit_price, quantity, subtotal, created_at)
SELECT
    ((i % 50000) + 1),
    ((i % 10000) + 1),
    ROUND((random() * 200 + 15)::numeric, 2),
    ((i % 4) + 1),
    ROUND((random() * 400 + 30)::numeric, 2),
    NOW() - (random() * interval '45 days')
FROM generate_series(1, 100000) AS i;

-- 6. Seed 50,000 Payment Transactions
INSERT INTO payment_transactions (transaction_ref, order_id, user_id, amount, fee_amount, gateway, status, idempotency_key, metadata, created_at)
SELECT
    'TXN-' || LPAD(i::text, 8, '0'),
    i,
    ((i % 20000) + 1),
    ROUND((random() * 850 + 40)::numeric, 2),
    ROUND((random() * 15 + 1.5)::numeric, 2),
    CASE (i % 3)
        WHEN 0 THEN 'STRIPE'
        WHEN 1 THEN 'ADYEN'
        ELSE 'CHECKOUT_COM'
    END,
    CASE (i % 15)
        WHEN 0 THEN 'FAILED'
        WHEN 1 THEN 'REFUNDED'
        ELSE 'SUCCESS'
    END,
    'IDEMP-' || gen_random_uuid(),
    json_build_object('client_ip', '192.168.' || (i % 255) || '.' || (i % 250), 'risk_score', ROUND((random() * 10)::numeric, 2))::jsonb,
    NOW() - (random() * interval '45 days')
FROM generate_series(1, 50000) AS i;

-- 7. Seed 100,000 Audit Logs
INSERT INTO audit_logs (entity_name, entity_id, action, performed_by, payload, created_at)
SELECT
    CASE (i % 4)
        WHEN 0 THEN 'ORDER'
        WHEN 1 THEN 'PAYMENT'
        WHEN 2 THEN 'USER'
        ELSE 'PRODUCT'
    END,
    ((i % 50000) + 1),
    CASE (i % 4)
        WHEN 0 THEN 'RECORD_CREATED'
        WHEN 1 THEN 'STATUS_UPDATED'
        WHEN 2 THEN 'BALANCE_ADJUSTED'
        ELSE 'SETTLED'
    END,
    'system_automated_worker@safemigrate.internal',
    json_build_object('event_source', 'postgres_wal_cdc', 'latency_ms', (i % 15))::jsonb,
    NOW() - (random() * interval '30 days')
FROM generate_series(1, 100000) AS i;

-- Vacuum analyze to optimize stats
VACUUM ANALYZE users;
VACUUM ANALYZE merchants;
VACUUM ANALYZE products;
VACUUM ANALYZE orders;
VACUUM ANALYZE order_items;
VACUUM ANALYZE payment_transactions;
VACUUM ANALYZE audit_logs;
