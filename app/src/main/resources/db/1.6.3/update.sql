-- ============================================================
-- Version 1.6.3 — Patch: loyalty correctness + ERP sync fixes
-- ============================================================

UPDATE APP_VERSION SET version = '1.6.3';


INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.6.3', 'FIX',     'Loyalty: loyalty_deduction_amount was never stored on the ticket — the return value of redeemPoints() was discarded. The deduction in TND is now correctly saved on sales_header after each redemption.'),
('1.6.3', 'IMPROVE', 'Loyalty: Ticket Details modal now shows a "Loyalty Deduction" row in the Summary section (amount in TND + points redeemed) so the cashier can see why the total is lower than the sum of lines.'),
('1.6.3', 'FIX',     'ERP export — header: Discount_Percent sent to NAV now combines the header discount and the loyalty deduction into a single percentage so NAV can reconcile gross lines total to the net Ticket_Amount exactly.'),
('1.6.3', 'FIX',     'ERP export — lines: Line_Discount_Percent is now derived from line_total vs unit_price when discount_percentage is null (fixed-amount promotions). Previously these lines were exported with no discount, causing NAV to post the full undiscounted price.'),
('1.6.3', 'FIX',     'Payment sync: CLIENT_ESPECES aggregate lines now track all grouped payments via a join table. Previously only the first payment in the group was marked synced after ERP export; all payments in the group are now correctly marked synced once the session line is pushed to NAV.');
