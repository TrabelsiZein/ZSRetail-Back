-- ============================================================
-- Version 1.12.1 — Hotfix EMTOP : moyen de paiement « Retenue à la source »
-- ============================================================
-- Deployment order:
--   1) Run db/1.12.0/update.sql first if the database is still on 1.11.0.
--   2) Stop the application, then run this script against the database.
--   3) Deploy the 1.12.1 binaries (backend WAR and frontend).
-- The application refuses to start while APP_VERSION differs from its own
-- version, so step 2 must be done before step 3. A running 1.12.0 cannot read
-- the new payment method type, hence the stop before the script.
-- No schema statement in this script. It can be run twice: the payment method
-- and the release note are inserted only once.

UPDATE APP_VERSION SET version = '1.12.1';

-- New payment method: Retenue à la source. Its code is the NAV payment class.
IF NOT EXISTS (SELECT 1 FROM payment_method WHERE type = 'RETENUE_SOURCE' OR code = 'ENCAISS-RS-CLT')
INSERT INTO payment_method (code, name, type, description, active, display_order,
    require_title_number, require_due_date, require_drawer_name, require_issuing_bank,
    requires_confirmation, created_by, updated_by)
VALUES ('ENCAISS-RS-CLT', N'Retenue à la source', 'RETENUE_SOURCE', N'Paiement par retenue à la source',
    1, 10, 0, 0, 0, 0, 0, 'System', 'System');

IF NOT EXISTS (SELECT 1 FROM APP_RELEASE_NOTES WHERE version = '1.12.1')
INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.12.1', 'NEW', N'Nouveau moyen de paiement « Retenue à la source » (code ERP ENCAISS-RS-CLT).');
