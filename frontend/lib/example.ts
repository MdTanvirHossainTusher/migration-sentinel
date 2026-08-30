// A worked example that exercises the real flow: a small migration history, then a candidate
// that is only dangerous because of what those earlier migrations built and how much data sits
// in the result. Deliberately numbered past V9 so the version ordering is visible in the list.

export type ExampleFile = { name: string; sql: string };

export const EXAMPLE_HISTORY: ExampleFile[] = [
  {
    name: "V1__create_tenants.sql",
    sql: `CREATE TABLE tenants (
    id          bigserial PRIMARY KEY,
    name        varchar(128) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);`,
  },
  {
    name: "V2__create_shipments.sql",
    sql: `CREATE TABLE shipments (
    id          bigserial PRIMARY KEY,
    carrier     varchar(32) NOT NULL,
    shipped_at  timestamptz
);`,
  },
  {
    name: "V9__add_legacy_ref.sql",
    sql: `ALTER TABLE shipments ADD COLUMN legacy_ref varchar(64);
CREATE INDEX ix_shipments_legacy_ref ON shipments (legacy_ref);`,
  },
  {
    name: "V10__create_shipment_events.sql",
    sql: `CREATE TABLE shipment_events (
    id           bigserial PRIMARY KEY,
    shipment_id  bigint NOT NULL REFERENCES shipments (id),
    event_type   varchar(32) NOT NULL,
    occurred_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_shipment_events_shipment_id ON shipment_events (shipment_id);`,
  },
  {
    name: "V11__shipments_tracking_number.sql",
    sql: `ALTER TABLE shipments ADD COLUMN tracking_number varchar(64);`,
  },
  {
    name: "V42__add_shipment_tenant.sql",
    sql: `ALTER TABLE shipments DROP COLUMN legacy_ref;
ALTER TABLE shipments ADD COLUMN tenant_id bigint;
ALTER TABLE shipments ADD CONSTRAINT fk_shipments_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants (id) NOT VALID;
ALTER TABLE shipments VALIDATE CONSTRAINT fk_shipments_tenant;
CREATE INDEX idx_shipments_shipped_at ON shipments (shipped_at);`,
  },
];

export const EXAMPLE_SEED = `INSERT INTO tenants (name) SELECT 'tenant-' || g FROM generate_series(1, 5) g;
INSERT INTO shipments (carrier) SELECT 'UPS' FROM generate_series(1, 2000);
-- Tell the planner this table is production-sized without inserting 6M rows.
UPDATE pg_class SET reltuples = 6000000 WHERE relname = 'shipments';`;
