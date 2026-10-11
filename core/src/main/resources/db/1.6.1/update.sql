-- ============================================================
-- Version 1.6.1 — Patch: return voucher refund correctness + Return Products UI + Responsible role menus
-- ============================================================

UPDATE APP_VERSION SET version = '1.6.1';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.6.1', 'FIX',     'Return voucher refund amount was over-paying by the tax-stamp share when the source ticket had both a tax stamp and a ticket-level discount. The refund now uses the ticket''s stored discount percentage directly instead of a ratio that included the non-discounted tax stamp.'),
('1.6.1', 'FIX',     'Return Products page showed the undiscounted total in the Return Summary for tickets with a header-level discount, because the ticket-details response did not include discountAmount and discountPercentage. Both fields are now returned and the UI preview matches the voucher.'),
('1.6.1', 'IMPROVE', 'Return Products page: Ticket Details now shows Original Amount (TTC), Discount (with %) and Total Amount (TTC). Return Summary now shows Total Items in TTC with a Discount row — both sections display the exact percentage stored on the original ticket.'),
('1.6.1', 'IMPROVE', 'RESPONSIBLE role now has access to the full Stock menu (Item Families, Item Subfamilies, Sales Prices, Sales Discounts, Promotions) and to the Reports menu (Sales, Loyalty, Sessions, Promotions).');
