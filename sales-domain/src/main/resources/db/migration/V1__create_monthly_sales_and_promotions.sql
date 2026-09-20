CREATE TABLE monthly_sales (
    id UUID PRIMARY KEY,
    store_id INTEGER NOT NULL,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    category VARCHAR(100) NOT NULL,
    year_month DATE NOT NULL,
    quantity_sold INTEGER NOT NULL,
    total_value NUMERIC(14, 2) NOT NULL,
    region VARCHAR(100) NOT NULL,
    store_name VARCHAR(255) NOT NULL
);

CREATE INDEX idx_monthly_sales_store_yearmonth ON monthly_sales (store_id, year_month);

CREATE TABLE promotions (
    promotion_id UUID PRIMARY KEY,
    store_id INTEGER NOT NULL,
    year_month DATE NOT NULL,
    promotion_name VARCHAR(255) NOT NULL,
    discount_percentage NUMERIC(5, 2) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL
);

CREATE INDEX idx_promotions_store_yearmonth ON promotions (store_id, year_month);
