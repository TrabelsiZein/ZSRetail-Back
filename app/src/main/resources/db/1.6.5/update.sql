-- ============================================================
-- Version 1.6.5 — UI enhancements + discount_percent on return
-- ============================================================

UPDATE APP_VERSION SET version = '1.6.5';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.6.5', 'NEW',     'Return header: discount_percentage field added (copied from original ticket at return creation time). Exposed in return details API and sent to NAV as Discount_Percent.'),
('1.6.5', 'IMPROVE', 'Return popup: Original Ticket value is now a clickable link that opens the ticket details modal in a new tab.'),
('1.6.5', 'IMPROVE', 'Return popup: discount percentage is now shown alongside the discount amount (e.g. Discount (%): 50%).'),
('1.6.5', 'IMPROVE', 'Ticket details popup: Cashier and Session values are now clickable links opening the user management and session history pages in new tabs with the exact record pre-selected.'),
('1.6.5', 'IMPROVE', 'Ticket details popup: discount percentage is now displayed next to the discount amount in the summary section.'),
('1.6.5', 'IMPROVE', 'Session details popup: UI aligned with ticket/return popups (ERP sync card styling, removed redundant Session Information title, payment classes synchronized counter added).'),
('1.6.5', 'IMPROVE', 'Session details popup: Cashier value is now a clickable link opening the user details in a new tab.'),
('1.6.5', 'IMPROVE', 'Session details popup: Ticket # in Payment Headers & Lines table is now a clickable link opening the ticket details modal in a new tab.');
