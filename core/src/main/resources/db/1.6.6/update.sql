-- ============================================================
-- Version 1.6.6 — Promotion rules: no return for promoted items
-- ============================================================

UPDATE APP_VERSION SET version = '1.6.6';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.6.6', 'NEW',     'Promotion rules: items sold via a promotion can now be blocked from return. Configure via General Setup → Returns → Block Returns for Promoted Items.'),
('1.6.6', 'NEW',     'Promotion rules: best discount wins — promotions are now compared against SalesDiscount and the higher discount is applied (previously SalesDiscount always blocked promotions). SalesPrice is now used as the base price when a promotion gives a better discount.'),
('1.6.6', 'IMPROVE', 'Return Products: discount source and promotion badge are now correctly shown on each line in the return popup.'),
('1.6.6', 'NEW', 'Loyalty ticket: two new toggles in General Setup → Loyalty to show/hide the loyalty balance and earned points on printed sales tickets.');
