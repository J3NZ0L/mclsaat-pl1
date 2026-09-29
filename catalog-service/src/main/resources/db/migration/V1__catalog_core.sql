-- ---------------------------------------------------------------------------
-- Subsystem 1: catalog and subscription registry.
--
-- The shape of this schema is part of the exercise. It is written the way a
-- 20-year-old telco billing/CRM database is written, because the semantic
-- mismatches between the three subsystems are the point of the whole project:
--
--   * identifiers are zero-padded fixed-width CHAR columns, not integers
--   * statuses and kinds are short coded strings, not readable enums
--   * booleans are 'Y'/'N' CHAR(1) flags
--   * data allowances are integers in MEGABYTES
--   * money is an integer count of HUF minor units (fillér, 1 HUF = 100 fillér)
--
-- Subsystem 2 (activation) and subsystem 3 (billing) each use a different
-- convention for every one of those. See docs/semantic-mismatches.md.
-- ---------------------------------------------------------------------------

CREATE TABLE catalog.plan (
    plan_code        VARCHAR(20)  NOT NULL,
    service_kind     CHAR(1)      NOT NULL,   -- 'I' = internet, 'M' = mobile
    display_name     VARCHAR(80)  NOT NULL,
    plan_kind        VARCHAR(8)   NOT NULL,   -- 'BASE' | 'ADDON'
    addon_category   VARCHAR(12),             -- 'DATA_PACK' | 'ROAMING', ADDON only
    monthly_fee_minor BIGINT      NOT NULL,   -- HUF fillér (minor units)
    data_allowance_mb INTEGER     NOT NULL,   -- megabytes; -1 means unmetered
    speed_kbps       INTEGER,                 -- internet plans only
    active_flag      CHAR(1)      NOT NULL DEFAULT 'Y',
    CONSTRAINT pk_plan PRIMARY KEY (plan_code),
    CONSTRAINT ck_plan_service_kind CHECK (service_kind IN ('I', 'M')),
    CONSTRAINT ck_plan_kind         CHECK (plan_kind IN ('BASE', 'ADDON')),
    CONSTRAINT ck_plan_addon_cat    CHECK (
        (plan_kind = 'ADDON' AND addon_category IS NOT NULL)
     OR (plan_kind = 'BASE'  AND addon_category IS NULL)),
    CONSTRAINT ck_plan_active_flag  CHECK (active_flag IN ('Y', 'N')),
    CONSTRAINT ck_plan_fee          CHECK (monthly_fee_minor >= 0),
    CONSTRAINT ck_plan_allowance    CHECK (data_allowance_mb >= -1)
);

CREATE TABLE catalog.subscriber (
    cust_no     CHAR(8)      NOT NULL,   -- zero-padded, e.g. '00000042'
    full_name   VARCHAR(120) NOT NULL,
    email       VARCHAR(160) NOT NULL,
    msisdn      VARCHAR(15),             -- E.164 digits WITHOUT '+', e.g. '36301234567'
    created_ts  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT pk_subscriber PRIMARY KEY (cust_no),
    CONSTRAINT ck_subscriber_cust_no CHECK (cust_no ~ '^[0-9]{8}$'),
    CONSTRAINT ck_subscriber_msisdn  CHECK (msisdn IS NULL OR msisdn ~ '^[0-9]{8,15}$')
);

CREATE TABLE catalog.subscription (
    sub_id            VARCHAR(16) NOT NULL,   -- 'SUB-2026-000001'
    cust_no           CHAR(8)     NOT NULL,
    plan_code         VARCHAR(20) NOT NULL,
    -- two-character legacy status codes:
    -- NW new, PA pending activation, AC active, SU suspended, TE terminated
    status_code       CHAR(2)     NOT NULL,
    activated_on      DATE,
    msisdn            VARCHAR(15),
    sim_iccid         VARCHAR(22),
    data_allowance_mb INTEGER     NOT NULL,   -- effective allowance incl. add-ons, megabytes
    parent_sub_id     VARCHAR(16),            -- add-on subscriptions hang off a base one
    created_ts        TIMESTAMP   NOT NULL DEFAULT now(),
    updated_ts        TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT pk_subscription        PRIMARY KEY (sub_id),
    CONSTRAINT fk_subscription_cust   FOREIGN KEY (cust_no)   REFERENCES catalog.subscriber (cust_no),
    CONSTRAINT fk_subscription_plan   FOREIGN KEY (plan_code) REFERENCES catalog.plan (plan_code),
    CONSTRAINT fk_subscription_parent FOREIGN KEY (parent_sub_id) REFERENCES catalog.subscription (sub_id),
    CONSTRAINT ck_subscription_status CHECK (status_code IN ('NW', 'PA', 'AC', 'SU', 'TE')),
    CONSTRAINT ck_subscription_sub_id CHECK (sub_id ~ '^SUB-[0-9]{4}-[0-9]{6}$')
);

CREATE INDEX ix_subscription_cust   ON catalog.subscription (cust_no);
CREATE INDEX ix_subscription_status ON catalog.subscription (status_code, updated_ts);
CREATE INDEX ix_subscription_parent ON catalog.subscription (parent_sub_id);

CREATE SEQUENCE catalog.subscription_seq START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE catalog.subscriber_seq   START WITH 500  INCREMENT BY 1;
