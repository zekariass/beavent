-- =============================================================================
-- 16: stock — retail and professional-use inventory, purchasing, fulfilment.
-- stock.levels is a cache maintained only by immutable stock.movements.
-- =============================================================================

CREATE TABLE stock.suppliers (
  id           uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id       uuid NOT NULL REFERENCES tenant.organizations(id),
  name         text NOT NULL,
  contact_name text,
  phone_e164   text,
  email        citext,
  tin          text,
  address      jsonb NOT NULL DEFAULT '{}'::jsonb,
  status       text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id)
);

CREATE TABLE stock.levels (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  location_id        uuid NOT NULL,
  product_variant_id uuid NOT NULL,
  on_hand            numeric(14,3) NOT NULL DEFAULT 0 CHECK (on_hand >= 0),
  reserved           numeric(14,3) NOT NULL DEFAULT 0 CHECK (reserved >= 0),  -- online orders awaiting pickup/delivery
  reorder_point      numeric(14,3),
  reorder_quantity   numeric(14,3),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (location_id, product_variant_id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  FOREIGN KEY (org_id, product_variant_id) REFERENCES catalog.product_variants (org_id, id),
  CHECK (reserved <= on_hand)
);

CREATE TABLE stock.purchase_orders (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL REFERENCES tenant.organizations(id),
  location_id   uuid NOT NULL,
  supplier_id   uuid NOT NULL,
  number        text NOT NULL,
  status        text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SENT','PARTIALLY_RECEIVED','RECEIVED','CANCELLED')),
  currency_code char(3) NOT NULL REFERENCES ref.currencies(code),
  expected_at   date,
  notes         text,
  created_by    uuid REFERENCES iam.users(id),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  UNIQUE (org_id, number),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  FOREIGN KEY (org_id, supplier_id) REFERENCES stock.suppliers (org_id, id)
);

CREATE TABLE stock.purchase_order_lines (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  purchase_order_id  uuid NOT NULL,
  product_variant_id uuid NOT NULL,
  quantity_ordered   numeric(14,3) NOT NULL CHECK (quantity_ordered > 0),
  quantity_received  numeric(14,3) NOT NULL DEFAULT 0 CHECK (quantity_received >= 0),
  unit_cost_minor    bigint NOT NULL CHECK (unit_cost_minor >= 0),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, purchase_order_id) REFERENCES stock.purchase_orders (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, product_variant_id) REFERENCES catalog.product_variants (org_id, id)
);

CREATE TABLE stock.transfers (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id           uuid NOT NULL REFERENCES tenant.organizations(id),
  from_location_id uuid NOT NULL,
  to_location_id   uuid NOT NULL,
  status           text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','IN_TRANSIT','RECEIVED','CANCELLED')),
  created_by       uuid REFERENCES iam.users(id),
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, from_location_id) REFERENCES tenant.locations (org_id, id),
  FOREIGN KEY (org_id, to_location_id) REFERENCES tenant.locations (org_id, id),
  CHECK (from_location_id <> to_location_id)
);

CREATE TABLE stock.stocktakes (
  id           uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id       uuid NOT NULL REFERENCES tenant.organizations(id),
  location_id  uuid NOT NULL,
  status       text NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS','COMPLETED','CANCELLED')),
  started_by   uuid REFERENCES iam.users(id),
  started_at   timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id)
);

CREATE TABLE stock.stocktake_lines (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  stocktake_id       uuid NOT NULL,
  product_variant_id uuid NOT NULL,
  expected_quantity  numeric(14,3) NOT NULL,
  counted_quantity   numeric(14,3) NOT NULL CHECK (counted_quantity >= 0),
  UNIQUE (stocktake_id, product_variant_id),
  FOREIGN KEY (org_id, stocktake_id) REFERENCES stock.stocktakes (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, product_variant_id) REFERENCES catalog.product_variants (org_id, id)
);

CREATE TABLE stock.movements (
  id                     uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id                 uuid NOT NULL,
  location_id            uuid NOT NULL,
  product_variant_id     uuid NOT NULL,
  kind                   text NOT NULL CHECK (kind IN ('RECEIPT','SALE','SALE_RETURN','PROFESSIONAL_USE','TRANSFER_OUT','TRANSFER_IN','ADJUSTMENT','DAMAGE','STOCKTAKE','RESERVE','UNRESERVE')),
  quantity               numeric(14,3) NOT NULL CHECK (quantity <> 0), -- signed effect on on_hand (or reserved for RESERVE/UNRESERVE)
  unit_cost_minor        bigint,
  order_line_id          uuid,
  purchase_order_line_id uuid,
  transfer_id            uuid,
  stocktake_id           uuid,
  appointment_item_id    uuid,                   -- backbar product used during a service
  reason                 text,
  actor_user_id          uuid REFERENCES iam.users(id),
  occurred_at            timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  FOREIGN KEY (org_id, product_variant_id) REFERENCES catalog.product_variants (org_id, id),
  FOREIGN KEY (org_id, order_line_id) REFERENCES commerce.order_lines (org_id, id),
  FOREIGN KEY (org_id, purchase_order_line_id) REFERENCES stock.purchase_order_lines (org_id, id),
  FOREIGN KEY (org_id, transfer_id) REFERENCES stock.transfers (org_id, id),
  FOREIGN KEY (org_id, stocktake_id) REFERENCES stock.stocktakes (org_id, id),
  FOREIGN KEY (org_id, appointment_item_id) REFERENCES scheduling.appointment_items (org_id, id),
  CHECK (kind NOT IN ('RECEIPT','SALE_RETURN','TRANSFER_IN','RESERVE') OR quantity > 0),
  CHECK (kind NOT IN ('SALE','PROFESSIONAL_USE','TRANSFER_OUT','DAMAGE','UNRESERVE') OR quantity < 0),
  CHECK (kind NOT IN ('SALE','SALE_RETURN') OR order_line_id IS NOT NULL),
  CHECK (kind <> 'RECEIPT' OR purchase_order_line_id IS NOT NULL OR reason IS NOT NULL),
  CHECK (kind NOT IN ('TRANSFER_IN','TRANSFER_OUT') OR transfer_id IS NOT NULL)
);
CREATE INDEX stock_movements_variant_idx ON stock.movements (product_variant_id, occurred_at);
CREATE TRIGGER stock_movements_immutable BEFORE UPDATE OR DELETE ON stock.movements
  FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();

CREATE FUNCTION stock.apply_movement() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO stock.levels (org_id, location_id, product_variant_id, on_hand, reserved)
  VALUES (NEW.org_id, NEW.location_id, NEW.product_variant_id, 0, 0)
  ON CONFLICT (location_id, product_variant_id) DO NOTHING;

  UPDATE stock.levels
     SET on_hand  = on_hand  + CASE WHEN NEW.kind IN ('RESERVE','UNRESERVE') THEN 0 ELSE NEW.quantity END,
         reserved = reserved + CASE NEW.kind WHEN 'RESERVE' THEN NEW.quantity WHEN 'UNRESERVE' THEN NEW.quantity ELSE 0 END,
         updated_at = now()
   WHERE location_id = NEW.location_id AND product_variant_id = NEW.product_variant_id;
  RETURN NEW;
END $$;
CREATE TRIGGER stock_movements_apply AFTER INSERT ON stock.movements
  FOR EACH ROW EXECUTE FUNCTION stock.apply_movement();

-- -----------------------------------------------------------------------------
-- Online store fulfilment (pickup at a location / at an event, local delivery)
-- -----------------------------------------------------------------------------
CREATE TABLE stock.delivery_zones (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id          uuid NOT NULL REFERENCES tenant.organizations(id),
  location_id     uuid NOT NULL,
  name            text NOT NULL,
  admin_area_ids  uuid[] NOT NULL DEFAULT '{}',
  center          geography(Point, 4326),
  radius_m        integer CHECK (radius_m IS NULL OR radius_m > 0),
  fee_minor       bigint NOT NULL DEFAULT 0 CHECK (fee_minor >= 0),
  free_over_minor bigint,
  currency_code   char(3) NOT NULL REFERENCES ref.currencies(code),
  eta_minutes     integer,
  is_active       boolean NOT NULL DEFAULT true,
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  CHECK (cardinality(admin_area_ids) > 0 OR (center IS NOT NULL AND radius_m IS NOT NULL))
);

CREATE TABLE stock.fulfilments (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id           uuid NOT NULL,
  order_id         uuid NOT NULL,
  method           text NOT NULL CHECK (method IN ('PICKUP_AT_LOCATION','PICKUP_AT_EVENT','LOCAL_DELIVERY','COURIER')),
  location_id      uuid,
  occurrence_id    uuid,
  delivery_zone_id uuid,
  address          jsonb,
  status           text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','READY','OUT_FOR_DELIVERY','DELIVERED','COLLECTED','CANCELLED','RETURNED')),
  courier_name     text,
  tracking_ref     text,
  ready_at         timestamptz,
  completed_at     timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  FOREIGN KEY (org_id, occurrence_id) REFERENCES events.occurrences (org_id, id),
  FOREIGN KEY (org_id, delivery_zone_id) REFERENCES stock.delivery_zones (org_id, id),
  CHECK (method <> 'PICKUP_AT_LOCATION' OR location_id IS NOT NULL),
  CHECK (method <> 'PICKUP_AT_EVENT' OR occurrence_id IS NOT NULL),
  CHECK (method NOT IN ('LOCAL_DELIVERY','COURIER') OR address IS NOT NULL)
);
