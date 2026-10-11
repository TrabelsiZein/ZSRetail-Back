-- ============================================================
-- Version 1.6.2 — Patch: loyalty program management (edit / deactivate / delete / end date)
-- ============================================================

UPDATE APP_VERSION SET version = '1.6.2';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.6.2', 'NEW',     'Loyalty Programs: "End date" is now a field in the create/edit form — supports time-boxed campaigns (e.g. Ramadan 2026) as well as open-ended programs (empty = no end).'),
('1.6.2', 'NEW',     'Loyalty Programs: Edit button added to the management page. Name, description and end date can be changed at any time; rate settings (points per TND, point value, thresholds, expiry) are editable only while the program has zero transactions, and locked afterwards to preserve the audit trail.'),
('1.6.2', 'NEW',     'Loyalty Programs: Deactivate button added — stops the current active program as of today without having to create a replacement program.'),
('1.6.2', 'NEW',     'Loyalty Programs: Delete button added — allowed only for programs that were never used (zero transactions). Used programs must be deactivated instead.'),
('1.6.2', 'IMPROVE', 'Loyalty Programs: the active program detection is now date-aware. A program with a future end date is still considered active until that date, so planned campaigns and scheduled closures behave as expected.');
