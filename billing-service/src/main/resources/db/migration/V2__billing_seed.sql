-- ---------------------------------------------------------------------------
-- Seed data. Amounts are HUF major units with two decimals, so the same
-- 5 990 Ft that the catalog stores as 599000 appears here as a 4716.54 net plus
-- 1273.47 VAT. The one-fillér gap between 5990.01 and 5990.00 is real and is
-- documented in docs/semantic-mismatches.md - it is what a gross-priced catalog
-- meeting a net-invoicing billing system actually costs you.
-- ---------------------------------------------------------------------------

INSERT INTO billing.billing_account (ba_no, customer_ref, account_name, billing_email, currency, created_ts) VALUES
    ('BA-00042',  42, 'Kovács Anna',  'anna.kovacs@example.hu',  'HUF', now() - INTERVAL '3 years'),
    ('BA-00043',  43, 'Nagy Béla',    'bela.nagy@example.hu',    'HUF', now() - INTERVAL '2 years'),
    ('BA-00101', 101, 'Szabó Csilla', 'csilla.szabo@example.hu', 'HUF', now() - INTERVAL '10 days');

-- A batch that was sent to the clearing house and never acknowledged: failure branch B,
-- available from a cold start without having to trigger it first.
INSERT INTO billing.payment_batch
    (batch_id, file_name, status, item_count, total_amount, currency, created_ts, sent_ts)
VALUES
    ('BATCH-20260925-001', 'PMT-BATCH-20260925-001.txt', 'SENT', 1, 12990.00, 'HUF',
     now() - INTERVAL '4 days', now() - INTERVAL '4 days');

INSERT INTO billing.invoice
    (invoice_no, ba_no, subscription_ref, period_start, period_end,
     net_amount, vat_rate, vat_amount, gross_amount, currency, status, payment_ref, batch_id, issued_ts, updated_ts)
VALUES
    -- settled history
    ('2026/INV/000001', 'BA-00042', 'SUB-2026-000001', DATE '2026-08-01', DATE '2026-08-31',
     10228.35, 0.2700, 2761.65, 12990.00, 'HUF', 'PAID', 'pi_seed_000001', NULL,
     now() - INTERVAL '60 days', now() - INTERVAL '58 days'),
    -- open invoices the demo queries and pays
    ('2026/INV/000002', 'BA-00042', 'SUB-2026-000001', DATE '2026-09-01', DATE '2026-09-30',
     10228.35, 0.2700, 2761.65, 12990.00, 'HUF', 'OPEN', NULL, NULL,
     now() - INTERVAL '20 days', now() - INTERVAL '20 days'),
    ('2026/INV/000003', 'BA-00042', 'SUB-2026-000002', DATE '2026-09-01', DATE '2026-09-30',
     4716.54, 0.2700, 1273.47, 5990.01, 'HUF', 'OPEN', NULL, NULL,
     now() - INTERVAL '20 days', now() - INTERVAL '20 days'),
    ('2026/INV/000004', 'BA-00043', 'SUB-2026-000003', DATE '2026-09-01', DATE '2026-09-30',
     5503.94, 0.2700, 1486.06, 6990.00, 'HUF', 'OPEN', NULL, NULL,
     now() - INTERVAL '20 days', now() - INTERVAL '20 days'),
    -- Stripe took the money, the clearing house never confirmed: stranded in SETTLEMENT_PENDING
    ('2026/INV/000005', 'BA-00042', 'SUB-2026-000001', DATE '2026-07-01', DATE '2026-07-31',
     10228.35, 0.2700, 2761.65, 12990.00, 'HUF', 'SETTLEMENT_PENDING', 'pi_seed_000005',
     'BATCH-20260925-001', now() - INTERVAL '90 days', now() - INTERVAL '4 days');

-- keep the sequence ahead of the seeded invoice numbers
SELECT setval('billing.invoice_seq', 5);
