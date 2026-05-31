-- ============================================================
-- Version 1.7.0 — Session History rebuild + Ticket/Return filters + ERP Communications
-- ============================================================

UPDATE APP_VERSION SET version = '1.7.0';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES

-- Bug Fixes
('1.7.0', 'FIX',     'Sales Report: Revenue (TTC) and Discount figures now include ticket-level (header) discounts. Previously only line-level discounts were reflected, causing Revenue to be overstated and the Discount column to be understated on tickets where a header discount was applied. The header discount is now distributed proportionally across lines.'),
('1.7.0', 'FIX',     'Cash drawer total (ESPECE SYSTEM): change amount was incorrectly subtracted from the cash total for all sales including pure TPE tickets. Fixed: change is now only deducted when the sale includes at least one CLIENT_ESPECES payment.'),
('1.7.0', 'FIX',     'Payment recording: change amount was saved on the sales header even when no cash payment was involved (e.g. TPE overpayment). Fixed: changeAmount is now set to 0 when no CLIENT_ESPECES payment is present, both on standard and split-bill payment paths.'),
('1.7.0', 'FIX',     'Payment screen: the UI previously allowed completing a payment with an overpayment via non-cash methods (e.g. TPE). Fixed: overpayment is now blocked unless at least one cash payment is included in the transaction.'),

-- Ticket History
('1.7.0', 'IMPROVE', 'Ticket History: date filters now include time (datetime-local) for more precise range searches.'),
('1.7.0', 'NEW',     'Ticket History: new filter by Cashier — restricts results to tickets processed by a specific agent.'),
('1.7.0', 'NEW',     'Ticket History: new Price Min / Max filters to search tickets within a specific amount range.'),
('1.7.0', 'NEW',     'Ticket History: new Session Number filter to quickly retrieve all tickets belonging to a given session.'),
('1.7.0', 'IMPROVE', 'Ticket History: switched to full server-side pagination — page load is significantly faster on large ticket volumes.'),

-- Returns Management
('1.7.0', 'NEW',     'Returns Management: new Session Number and Cashier filters added.'),
('1.7.0', 'IMPROVE', 'Returns Management: when navigating from a session detail to its returns, the session filter is automatically pre-filled — no need to re-enter it manually.'),
('1.7.0', 'IMPROVE', 'Returns Management: switched to server-side pagination for improved performance.'),

-- Session History
('1.7.0', 'IMPROVE', 'Session History: new filters added — Cashier, Total Sales Min/Max, Sales Count Min/Max, and Difference Sign (positive / negative / zero / all).'),
('1.7.0', 'IMPROVE', 'Session History: table now displays Opening Cash, Cashier name, POS Closure amount and Difference column (colour-coded: green = balanced or surplus, red = deficit).'),
('1.7.0', 'IMPROVE', 'Session History: for Responsible users, system amounts are hidden (shown as *****) on sessions that are not yet terminated, to preserve independence of control.'),
('1.7.0', 'NEW',     'Session History — Detail modal: new Payment Summary table showing, per payment method, the system amount, cashier closure amount, responsible closure amount, and the corresponding deltas.'),
('1.7.0', 'IMPROVE', 'Session History — Detail modal: Verify Session form is now pre-filled per payment method based on cashier closure values. Responsible can add extra payment methods if needed.'),
('1.7.0', 'IMPROVE', 'Session History — Detail modal: View Tickets and View Returns buttons open in a new tab with the session pre-selected as filter.'),
('1.7.0', 'IMPROVE', 'Session History: responsible session verification now records the declared amount per payment method in addition to the total closure amount.'),

-- ERP Communications
('1.7.0', 'IMPROVE', 'ERP Communications: date filters now include time (datetime-local) for more precise filtering.'),
('1.7.0', 'IMPROVE', 'ERP Communications: page now loads the most recent records on open — no longer requires manually entering a date to see results.'),
('1.7.0', 'IMPROVE', 'ERP Communications: switched to server-side pagination with DB-level filtering — eliminates loading all records into memory, significantly improving page performance.');
