-- ============================================================
-- Version 1.2.5 — Initial versioned baseline
-- Includes: migration 026 (config_type + config_options)
-- ============================================================

-- ── Schema: config_type + config_options on general_setup ────
IF NOT EXISTS (
    SELECT * FROM sys.columns
    WHERE object_id = OBJECT_ID('general_setup') AND name = 'config_type'
)
BEGIN
    ALTER TABLE [dbo].[general_setup]
        ADD [config_type] NVARCHAR(20) NOT NULL
        CONSTRAINT DF_general_setup_config_type DEFAULT 'STRING';
    PRINT 'Added config_type column to general_setup';
END

IF NOT EXISTS (
    SELECT * FROM sys.columns
    WHERE object_id = OBJECT_ID('general_setup') AND name = 'config_options'
)
BEGIN
    ALTER TABLE [dbo].[general_setup]
        ADD [config_options] NVARCHAR(500) NULL;
    PRINT 'Added config_options column to general_setup';
END

-- ── Backfill config_type for all known keys ──────────────────
UPDATE [dbo].[general_setup]
SET    [config_type] = 'BOOLEAN'
WHERE  [code] IN (
    'ENABLE_SIMPLE_RETURN', 'ALWAYS_SHOW_BADGE_SCAN_POPUP',
    'AUTO_ADD_CASH_PAYMENT_ON_PAYMENT_PAGE', 'ENABLE_CASH_DISCREPANCY_CHECK',
    'ENABLE_TAX_STAMP', 'LOYALTY_ENABLED', 'ALLOW_NEGATIVE_STOCK',
    'POS_SHOW_IMAGES', 'TABLE_MANAGEMENT_ENABLED'
);

UPDATE [dbo].[general_setup]
SET    [config_type] = 'NUMBER'
WHERE  [code] IN (
    'MAX_DAYS_FOR_RETURN', 'RETURN_VOUCHER_VALIDITY_DAYS',
    'PAYMENT_METHOD_CLIENT_CHEQUE_TITLE_NUMBER_LENGTH',
    'PAYMENT_METHOD_TICKET_RESTAURANT_TITLE_NUMBER_LENGTH',
    'PAYMENT_METHOD_CHEQUE_CADEAU_TITLE_NUMBER_LENGTH',
    'PAYMENT_METHOD_CLIENT_TRAITE_TITLE_NUMBER_LENGTH',
    'TAX_STAMP_VALUE_MILLIMES', 'TABLE_MANAGEMENT_TABLE_COUNT'
);

UPDATE [dbo].[general_setup]
SET    [config_type] = 'DATETIME'
WHERE  [code] IN (
    'ERP_SYNC_LAST_ITEM_FAMILY', 'ERP_SYNC_LAST_ITEM_SUBFAMILY',
    'ERP_SYNC_LAST_ITEM', 'ERP_SYNC_LAST_ITEM_BARCODE',
    'ERP_SYNC_LAST_LOCATION', 'ERP_SYNC_LAST_CUSTOMER',
    'ERP_SYNC_LAST_SALES_PRICE', 'ERP_SYNC_LAST_SALES_DISCOUNT',
    'ERP_SYNC_LAST_DELETION_LOG',
    'FRANCHISE_LAST_ITEM_SYNC', 'FRANCHISE_LAST_SUPPLY_RECEPTION_SYNC'
);

UPDATE [dbo].[general_setup]
SET    [config_type]    = 'SELECT',
       [config_options] = 'ERRORS_ONLY,ERRORS_AND_WARNINGS,ALL'
WHERE  [code] = 'ERP_SYNC_TRACKING_LEVEL';

UPDATE APP_VERSION SET version = '1.2.5';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.2.5', 'NEW',     'Initial versioned release — version control system introduced'),
('1.2.5', 'NEW',     'General Setup page redesigned as structured settings page with typed fields (BOOLEAN, NUMBER, SELECT)'),
('1.2.5', 'NEW',     'ConfigType + ConfigOptions columns added to general_setup table'),
('1.2.5', 'FIX',     'Tax stamp item initializer fixed — was incorrectly saving LOYALTY_ENABLED instead of TAX_STAMP item'),
('1.2.5', 'IMPROVE', 'ZZDataInitializer: all GeneralSetup configs seeded idempotently on every startup'),
('1.2.5', 'IMPROVE', 'Application footer updated — Digithink Consulting branding + POS version number');

PRINT 'Version updated to 1.2.5';
