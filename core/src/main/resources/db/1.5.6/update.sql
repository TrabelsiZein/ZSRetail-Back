-- ============================================================
-- Version 1.5.6 — Patch: table management & sync fixes
-- ============================================================

UPDATE APP_VERSION SET version = '1.5.6';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.5.6', 'FIX',     'Table management: opening a previously saved pending ticket from another table no longer reassigns it to the currently selected table'),
('1.5.6', 'FIX',     'Franchise item sync: items deleted at HQ are now correctly deactivated on the client instead of remaining visible in POS'),
('1.5.6', 'FIX',     'Supply reception: quantity received is now validated against the ordered quantity before confirmation'),
('1.5.6', 'IMPROVE', 'Table overview grid reloads automatically every 30 seconds without requiring a manual refresh'),
('1.5.6', 'IMPROVE', 'Version mismatch error at startup now prints the expected and actual versions clearly in the application log');
