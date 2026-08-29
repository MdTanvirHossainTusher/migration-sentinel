ALTER TABLE shipments ADD COLUMN order_id bigint;
ALTER TABLE shipments ADD CONSTRAINT fk_shipments_order
    FOREIGN KEY (order_id) REFERENCES orders (id) NOT VALID;
ALTER TABLE shipments VALIDATE CONSTRAINT fk_shipments_order;
