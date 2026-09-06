# General Setup

**Status**: ✅ Complete (Migration 026)

**Migration**: `026_add_config_type_to_general_setup.sql`

Added two columns to `general_setup`:
- `config_type` NVARCHAR(20) NOT NULL DEFAULT 'STRING' — drives the admin UI input widget (BOOLEAN / NUMBER / STRING / DATETIME / SELECT)
- `config_options` NVARCHAR(500) NULL — comma-separated valid values for SELECT type entries

**Rule**: When adding a new `general_setup` entry (in any migration or `ZZDataInitializer`), always add the corresponding `UPDATE … SET config_type = '…'` statement.

### Type assignments

| Type | Codes |
|---|---|
| BOOLEAN | `ENABLE_SIMPLE_RETURN`, `ALWAYS_SHOW_BADGE_SCAN_POPUP`, `AUTO_ADD_CASH_PAYMENT_ON_PAYMENT_PAGE`, `ENABLE_CASH_DISCREPANCY_CHECK`, `ENABLE_TAX_STAMP`, `LOYALTY_ENABLED`, `ALLOW_NEGATIVE_STOCK`, `POS_SHOW_IMAGES`, `TABLE_MANAGEMENT_ENABLED` |
| NUMBER | `MAX_DAYS_FOR_RETURN`, `RETURN_VOUCHER_VALIDITY_DAYS`, `PAYMENT_METHOD_*_TITLE_NUMBER_LENGTH` (×4), `TAX_STAMP_VALUE_MILLIMES`, `TABLE_MANAGEMENT_TABLE_COUNT` |
| DATETIME | All `ERP_SYNC_LAST_*` checkpoints (×9), `FRANCHISE_LAST_ITEM_SYNC`, `FRANCHISE_LAST_SUPPLY_RECEPTION_SYNC` |
| SELECT | `ERP_SYNC_TRACKING_LEVEL` (options: `ERRORS_ONLY,ERRORS_AND_WARNINGS,ALL`) |
| STRING | Default — all other codes not listed above |
