-- ============================================================
-- Version 1.6.0 — Minor: new payment method + ERP payment sync per customer
-- ============================================================

UPDATE APP_VERSION SET version = '1.6.0';

-- New payment method: Virement Bancaire
INSERT INTO payment_method (code, name, type, description, active, display_order,
    require_title_number, require_due_date, require_drawer_name, require_issuing_bank,
    requires_confirmation, created_by, updated_by)
VALUES ('VIREMENT_BANCAIRE', 'Virement Bancaire', 'VIREMENT_BANCAIRE', 'Paiement par virement bancaire',
    1, 9, 0, 0, 0, 0, 0, 'System', 'System');

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.6.0', 'NEW',     'New payment method: Virement Bancaire — cashier can record bank transfer payments directly from the payment screen (amount only, no additional fields required)'),
('1.6.0', 'IMPROVE', 'ERP payment export: CLIENT_ESPECES payments are now tracked per customer — each customer gets a separate payment line instead of one aggregated line for the whole session');
