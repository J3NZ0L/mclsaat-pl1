-- ---------------------------------------------------------------------------
-- Subsystem 3: billing and payment.
--
-- This schema disagrees with the catalog's on nearly every axis, on purpose:
--
--   * the customer is an INTEGER customer_ref (42), not CHAR(8) '00000042'
--   * money is NUMERIC(12,2) in HUF *major* units, not an integer count of fillér
--   * statuses are spelled out ('SETTLEMENT_PENDING'), not two-character codes
--   * invoice numbers carry slashes ('2026/INV/000001')
--
-- billing_account is the cross-reference that makes the identity mismatch real: nothing in
-- the database ties '00000042' to 42 except the convention that one is the other with the
-- padding stripped, and that convention lives in code, in docs, and nowhere else.
-- ---------------------------------------------------------------------------

CREATE TABLE billing.billing_account (
    ba_no         VARCHAR(12)  NOT NULL,   -- 'BA-00042'
    customer_ref  INTEGER      NOT NULL,   -- the catalog's cust_no with the zero padding stripped
    account_name  VARCHAR(120) NOT NULL,
    billing_email VARCHAR(160) NOT NULL,
    currency      CHAR(3)      NOT NULL DEFAULT 'HUF',
    created_ts    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT pk_billing_account       PRIMARY KEY (ba_no),
    CONSTRAINT uq_billing_account_cust  UNIQUE (customer_ref),
    CONSTRAINT ck_billing_account_ba_no CHECK (ba_no ~ '^BA-[0-9]{5}$'),
    CONSTRAINT ck_billing_account_cust  CHECK (customer_ref > 0)
);

CREATE TABLE billing.payment_batch (
    batch_id      VARCHAR(24)   NOT NULL,   -- 'BATCH-20260929-001'
    file_name     VARCHAR(80),
    -- OPEN     collecting items, not written out yet
    -- SENT     the fixed-width file is in the outbox and the clearing house owes us an ACK
    -- ACKED    the acknowledgement came back and the invoices were posted
    -- FAILED   the clearing house rejected the batch
    status        VARCHAR(12)   NOT NULL,
    item_count    INTEGER       NOT NULL DEFAULT 0,
    total_amount  NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency      CHAR(3)       NOT NULL DEFAULT 'HUF',
    created_ts    TIMESTAMP     NOT NULL DEFAULT now(),
    sent_ts       TIMESTAMP,
    acked_ts      TIMESTAMP,
    ack_file_name VARCHAR(80),
    CONSTRAINT pk_payment_batch     PRIMARY KEY (batch_id),
    CONSTRAINT ck_payment_batch_sts CHECK (status IN ('OPEN', 'SENT', 'ACKED', 'FAILED'))
);

CREATE TABLE billing.invoice (
    invoice_no       VARCHAR(20)   NOT NULL,   -- '2026/INV/000001'
    ba_no            VARCHAR(12)   NOT NULL,
    subscription_ref VARCHAR(32),              -- the catalog's sub_id, opaque text here
    period_start     DATE          NOT NULL,
    period_end       DATE          NOT NULL,
    net_amount       NUMERIC(12,2) NOT NULL,
    vat_rate         NUMERIC(5,4)  NOT NULL,   -- frozen on the invoice, 0.2700 today
    vat_amount       NUMERIC(12,2) NOT NULL,
    gross_amount     NUMERIC(12,2) NOT NULL,
    currency         CHAR(3)       NOT NULL DEFAULT 'HUF',
    -- OPEN               issued, unpaid
    -- SETTLEMENT_PENDING the card payment succeeded at Stripe but the clearing-house batch
    --                    has not been acknowledged, so the money is not posted yet
    -- PAID               acknowledged and posted
    -- CANCELLED          voided
    status           VARCHAR(20)   NOT NULL,
    payment_ref      VARCHAR(64),              -- Stripe PaymentIntent id
    request_ref      VARCHAR(64),              -- caller-supplied idempotency key
    batch_id         VARCHAR(24),
    issued_ts        TIMESTAMP     NOT NULL DEFAULT now(),
    updated_ts       TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT pk_invoice          PRIMARY KEY (invoice_no),
    CONSTRAINT fk_invoice_account  FOREIGN KEY (ba_no)    REFERENCES billing.billing_account (ba_no),
    CONSTRAINT fk_invoice_batch    FOREIGN KEY (batch_id) REFERENCES billing.payment_batch (batch_id),
    CONSTRAINT uq_invoice_request  UNIQUE (request_ref),
    CONSTRAINT ck_invoice_status   CHECK (status IN ('OPEN', 'SETTLEMENT_PENDING', 'PAID', 'CANCELLED')),
    CONSTRAINT ck_invoice_no       CHECK (invoice_no ~ '^[0-9]{4}/INV/[0-9]{6}$'),
    CONSTRAINT ck_invoice_period   CHECK (period_end >= period_start),
    CONSTRAINT ck_invoice_gross    CHECK (gross_amount = net_amount + vat_amount)
);

CREATE INDEX ix_invoice_account ON billing.invoice (ba_no, status);
CREATE INDEX ix_invoice_batch   ON billing.invoice (batch_id);
CREATE INDEX ix_invoice_sub     ON billing.invoice (subscription_ref);

CREATE SEQUENCE billing.invoice_seq         START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE billing.billing_account_seq START WITH 900 INCREMENT BY 1;
CREATE SEQUENCE billing.batch_seq           START WITH 1 INCREMENT BY 1;
