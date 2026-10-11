-- ============================================================
-- Version 1.8.0 — Change Payment Method on NAV-unsynced tickets
-- ============================================================
-- Deployment order (per AppVersionGuard):
--   1) Run this script against the database.
--   2) Deploy the 1.8.0 binary.
-- The payment_change_log table and the ENABLE_PAYMENT_METHOD_CHANGE
-- general_setup entry are created automatically on startup
-- (Hibernate ddl-auto=update + ZZDataInitializer).

UPDATE APP_VERSION SET version = '1.8.0';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES

-- Payment Method Change (admin)
('1.8.0', 'NEW',     'Ticket History: administrators can now change the payment method of a ticket (without changing any amount) while its cashier session has not yet been synchronized with NAV. The ERP payment-export rows are rebuilt to reflect the new method, and for closed sessions the expected cash is recomputed. Every change is recorded in an audit log (payment_change_log).'),
('1.8.0', 'NEW',     'General Setup: new "Allow Payment Method Change" option in the Payment Methods section, disabled by default. When enabled, the Change Payment Method action becomes available to administrators in Ticket History.'),
('1.8.0', 'IMPROVE', 'Cashier sessions: session close and verification now take a row-level lock so the expected-cash figure stays consistent if an administrator changes a payment method at the same time.');
