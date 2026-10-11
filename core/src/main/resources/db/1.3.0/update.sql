-- ============================================================
-- Version 1.3.0 — Minor: split-bill & customer enhancements
-- ============================================================

UPDATE APP_VERSION SET version = '1.3.0';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.3.0', 'NEW',     'Split bill: a single ticket can now be split across multiple payment transactions with configurable rounding rules'),
('1.3.0', 'NEW',     'Customer loyalty balance visible directly on the POS item selection screen when a customer is attached to the cart'),
('1.3.0', 'NEW',     'Admin: customer merge tool — consolidate duplicate customer records and transfer loyalty points'),
('1.3.0', 'IMPROVE', 'Ticket history search now supports filtering by payment method type'),
('1.3.0', 'IMPROVE', 'Receipt template: return voucher reference number printed as a CODE128 barcode for faster scanning at next visit'),
('1.3.0', 'FIX',     'Pending ticket list no longer shows tickets from closed sessions');
