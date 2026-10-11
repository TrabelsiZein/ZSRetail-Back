-- ============================================================
-- Version 1.5.0 — Minor: table management & franchise sync
-- ============================================================

UPDATE APP_VERSION SET version = '1.5.0';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.5.0', 'NEW',     'Table management mode: cashier selects a table number before starting a sale; pending tickets are grouped by table on the POS screen'),
('1.5.0', 'NEW',     'Table status overview: responsible user can see at a glance which tables are occupied, free, or have a pending bill'),
('1.5.0', 'NEW',     'Franchise: franchise client now receives real-time item price updates from HQ without requiring a full item sync'),
('1.5.0', 'NEW',     'Franchise: supply reception module — franchise client records received stock from HQ and updates local inventory'),
('1.5.0', 'IMPROVE', 'Session report PDF now includes table-by-table breakdown when table management is enabled'),
('1.5.0', 'IMPROVE', 'ERP sync checkpoint timestamps displayed in admin General Setup as formatted datetime (was raw ISO string)'),
('1.5.0', 'FIX',     'Table number correctly preserved when converting a pending ticket to a completed sale');
