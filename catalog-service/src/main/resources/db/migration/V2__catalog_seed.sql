-- ---------------------------------------------------------------------------
-- Seed data. Prices are Hungarian-style monthly fees in HUF *fillér*, so a
-- 12 990 Ft plan is stored as 1299000. Allowances are megabytes, so 10 GB is
-- 10240 and -1 means unmetered.
-- ---------------------------------------------------------------------------

INSERT INTO catalog.plan
    (plan_code, service_kind, display_name, plan_kind, addon_category,
     monthly_fee_minor, data_allowance_mb, speed_kbps, active_flag)
VALUES
    -- internet base plans
    ('INET-FIB-0500',  'I', 'Fibernet 500',            'BASE', NULL,        1299000,    -1,  500000, 'Y'),
    ('INET-FIB-1000',  'I', 'Fibernet 1000',           'BASE', NULL,        1799000,    -1, 1000000, 'Y'),
    ('INET-ADSL-0030', 'I', 'ADSL Komfort 30',         'BASE', NULL,         699000,    -1,   30000, 'Y'),
    -- mobile base plans
    ('MOB-VOICE-0010', 'M', 'Mobil Alap 10GB',         'BASE', NULL,         599000, 10240,    NULL, 'Y'),
    ('MOB-VOICE-0050', 'M', 'Mobil Max 50GB',          'BASE', NULL,         999000, 51200,    NULL, 'Y'),
    -- withdrawn plan: kept for historical subscriptions, must not show up in the catalog
    ('MOB-VOICE-0002', 'M', 'Mobil Mini 2GB (kivezetve)', 'BASE', NULL,      349000,  2048,    NULL, 'N'),
    -- add-ons
    ('ADDON-DATA-0005','M', '+5 GB adatcsomag',        'ADDON', 'DATA_PACK', 149000,  5120,    NULL, 'Y'),
    ('ADDON-DATA-0010','M', '+10 GB adatcsomag',       'ADDON', 'DATA_PACK', 249000, 10240,    NULL, 'Y'),
    ('ADDON-ROAM-EU01','M', 'EU Roaming Pass',         'ADDON', 'ROAMING',   399000,  3072,    NULL, 'Y');

INSERT INTO catalog.subscriber (cust_no, full_name, email, msisdn, created_ts) VALUES
    ('00000042', 'Kovács Anna',   'anna.kovacs@example.hu',   '36301234567', now() - INTERVAL '3 years'),
    ('00000043', 'Nagy Béla',     'bela.nagy@example.hu',     '36209876543', now() - INTERVAL '2 years'),
    ('00000101', 'Szabó Csilla',  'csilla.szabo@example.hu',  '36707654321', now() - INTERVAL '10 days');

INSERT INTO catalog.subscription
    (sub_id, cust_no, plan_code, status_code, activated_on, msisdn, sim_iccid,
     data_allowance_mb, parent_sub_id, created_ts, updated_ts)
VALUES
    ('SUB-2026-000001', '00000042', 'INET-FIB-0500',  'AC', DATE '2026-01-15', NULL,          NULL,
        -1,    NULL, now() - INTERVAL '250 days', now() - INTERVAL '250 days'),
    ('SUB-2026-000002', '00000042', 'MOB-VOICE-0010', 'AC', DATE '2026-01-15', '36301234567', '8936010012345678901',
        10240, NULL, now() - INTERVAL '250 days', now() - INTERVAL '250 days'),
    ('SUB-2026-000003', '00000043', 'INET-ADSL-0030', 'AC', DATE '2026-03-01', NULL,          NULL,
        -1,    NULL, now() - INTERVAL '200 days', now() - INTERVAL '200 days'),
    -- Deliberately inconsistent row, seeded for failure branch A: this subscription has been
    -- sitting in 'PA' (pending activation) for days because the SIM provisioning callback for
    -- its activation order never arrived. The ops console is what finds it.
    ('SUB-2026-000009', '00000101', 'MOB-VOICE-0050', 'PA', NULL,              '36707654321', NULL,
        51200, NULL, now() - INTERVAL '4 days', now() - INTERVAL '4 days');
