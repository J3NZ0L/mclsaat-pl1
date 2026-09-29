-- ---------------------------------------------------------------------------
-- Subsystem 2: the activation process.
--
-- This schema lives in the same database as Flowable's own engine tables but in its own schema,
-- and it shares nothing with the catalog or with billing. Its vocabulary is its own:
--
--   * order numbers carry slashes and a year   'ORD/2026/0000001'
--   * the customer is a plain INTEGER          42, not '00000042' and not 'BA-00042'
--   * products are dotted lowercase offer ids  'mob.voice.0010', not 'MOB-VOICE-0010'
--   * data allowance is GB with three decimals 10.000, not 10240 megabytes
--   * money is HUF major units                 5990.00, not 599000 fillér
--   * dates are CHAR(8) yyyyMMdd strings       '20260929', not a DATE and not 'yyyy-MM-dd'
--   * phone numbers are E.164 with a plus      '+36301234567', not '36301234567'
--   * statuses are spelled out                 'AWAITING_PROVISIONING', not 'PA'
--
-- Every one of those has to be translated on the way in and on the way out. Where that
-- translation lives, and what it costs, is what the modernization experiment measures.
-- ---------------------------------------------------------------------------

CREATE TABLE activation.activation_order (
    order_no              VARCHAR(20)   NOT NULL,   -- 'ORD/2026/0000001'
    -- NEW_SUBSCRIPTION | PLAN_CHANGE | ADDON: one process, three variants
    change_type           VARCHAR(20)   NOT NULL,
    customer_ref          INTEGER       NOT NULL,
    offer_id              VARCHAR(30)   NOT NULL,   -- 'mob.voice.0010'
    msisdn                VARCHAR(20),              -- '+36301234567', WITH the plus
    requested_start_date  CHAR(8)       NOT NULL,   -- 'yyyyMMdd'
    -- resolved from the catalog during validation, in this subsystem's units
    data_allowance_gb     NUMERIC(10,3),            -- NULL means unmetered
    monthly_fee_huf       NUMERIC(12,2),
    -- the catalog's identifiers, held as opaque external references
    target_subscription_ref VARCHAR(32),            -- PLAN_CHANGE / ADDON: what to act on
    subscription_ref      VARCHAR(32),              -- what the catalog gave us back
    -- RECEIVED | VALIDATED | AWAITING_PROVISIONING | STUCK | PROVISIONED | FAILED | CANCELLED
    status                VARCHAR(24)   NOT NULL,
    process_instance_id   VARCHAR(64),
    sim_iccid             VARCHAR(22),
    invoice_ref           VARCHAR(20),              -- billing's '2026/INV/000006'
    failure_reason        VARCHAR(400),
    -- fault injection for failure branch A: the provisioning platform never calls back
    simulate_stuck        BOOLEAN       NOT NULL DEFAULT false,
    created_ts            TIMESTAMP     NOT NULL DEFAULT now(),
    updated_ts            TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT pk_activation_order      PRIMARY KEY (order_no),
    CONSTRAINT ck_activation_order_no   CHECK (order_no ~ '^ORD/[0-9]{4}/[0-9]{7}$'),
    CONSTRAINT ck_activation_change     CHECK (change_type IN ('NEW_SUBSCRIPTION', 'PLAN_CHANGE', 'ADDON')),
    CONSTRAINT ck_activation_status     CHECK (status IN ('RECEIVED', 'VALIDATED', 'AWAITING_PROVISIONING',
                                                          'STUCK', 'PROVISIONED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_activation_start_date CHECK (requested_start_date ~ '^[0-9]{8}$'),
    CONSTRAINT ck_activation_customer   CHECK (customer_ref > 0),
    CONSTRAINT ck_activation_offer_id   CHECK (offer_id ~ '^[a-z0-9.]+$'),
    CONSTRAINT ck_activation_msisdn     CHECK (msisdn IS NULL OR msisdn ~ '^\+[0-9]{8,15}$')
);

CREATE INDEX ix_activation_order_status   ON activation.activation_order (status, updated_ts);
CREATE INDEX ix_activation_order_customer ON activation.activation_order (customer_ref);
CREATE INDEX ix_activation_order_sub      ON activation.activation_order (subscription_ref);
CREATE INDEX ix_activation_order_pi       ON activation.activation_order (process_instance_id);

CREATE SEQUENCE activation.order_seq START WITH 1 INCREMENT BY 1;
