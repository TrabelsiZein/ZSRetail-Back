-- ============================================================
-- Version 1.4.0 — Minor: promotions engine & reporting
-- ============================================================

UPDATE APP_VERSION SET version = '1.4.0';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.4.0', 'NEW',     'Promotion engine: cart-level promotions (buy X get Y free, total discount threshold) now processed server-side before payment'),
('1.4.0', 'NEW',     'Promotion codes: cashier can enter a promo code on the payment page to apply a code-gated discount'),
('1.4.0', 'NEW',     'Sales statistics dashboard: daily/weekly/monthly revenue chart, top-selling items, and payment method breakdown'),
('1.4.0', 'NEW',     'Admin: promotion management page with date range, time-of-day restriction, and per-item-family targeting'),
('1.4.0', 'IMPROVE', 'Item selection screen: promotion badge displayed on eligible items in the family grid'),
('1.4.0', 'IMPROVE', 'ERP ticket export now includes applied promotion reference and promo code in the line notes'),
('1.4.0', 'FIX',     'Line discount percentage now displayed with 3 decimal places on printed receipt to avoid rounding display issues');
