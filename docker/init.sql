-- Enable replication privileges
ALTER USER postgres WITH REPLICATION;

-- Create sample high-traffic orders table
CREATE TABLE IF NOT EXISTS orders (
    id BIGSERIAL PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    amount NUMERIC(10, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- REPLICA IDENTITY FULL ensures all column values are emitted during UPDATE and DELETE in the WAL
ALTER TABLE orders REPLICA IDENTITY FULL;

-- Seed with initial 1,000 rows
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
