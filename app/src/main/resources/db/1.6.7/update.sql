-- ============================================================
-- Version 1.6.7
-- ============================================================

UPDATE APP_VERSION SET version = '1.6.7';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.6.7', 'NEW', 'ERP session sync: new General Setup toggle (ERP_SKIP_CHEQUE_PAYMENTS) to exclude cheque payments from NAV synchronization. When enabled, CLIENT_CHEQUE payment headers and lines are not sent to NAV.'),
('1.6.7', 'NEW', 'Tombola: new General Setup toggle (TOMBOLA_ENABLED) to automatically print a small tombola slip alongside the main receipt for tickets attached to a loyalty member. The slip contains the ticket number, barcode, customer name and phone.');
