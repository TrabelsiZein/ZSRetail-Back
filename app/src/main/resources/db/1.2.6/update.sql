-- ============================================================
-- Version 1.2.6 — Patch: payment & session fixes
-- ============================================================

UPDATE APP_VERSION SET version = '1.2.6';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.2.6', 'FIX',     'Payment page: cash amount no longer resets to zero when switching between payment methods in the same session'),
('1.2.6', 'FIX',     'Session closure: discrepancy check now correctly ignores return voucher amounts from the expected cash total'),
('1.2.6', 'FIX',     'Return voucher validity countdown now based on creation date (UTC) instead of server local time'),
('1.2.6', 'IMPROVE', 'General Setup page: saving multiple changed fields now reports per-field errors instead of aborting on first failure');
