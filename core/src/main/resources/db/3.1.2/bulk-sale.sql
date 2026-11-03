-- ============================================================
-- Version 3.1.2 — Bulk sale ("vente en vrac"), step 1: the item flag and the quick quantities
-- ============================================================
-- Run BEFORE db/3.1.2/update.sql (which stamps APP_VERSION 3.1.2), on a store and on a head office: the same file.
-- The file is UTF-8: with sqlcmd add -f 65001 (sqlcmd ... -f 65001 -i bulk-sale.sql); in SSMS open it as UTF-8.
-- See docs/modules/bulk-sale.md.
--
-- Schema, store and head office: item.sold_in_bulk (bit, null = false), "vendu en vrac" on the item card, set by
-- PUT /item/{id}/bulk-flag and sent down with the catalogue. Every item stays null: nothing changes until an item is
-- flagged.
--
-- Schema, store only (a head office database has ho_store and never gets it): quick_quantity, the quick quantities the
-- till will offer for a bulk item (label, quantity with 3 decimals, an optional container item added as its own line,
-- active, display order), with the names and types Hibernate uses (a start creates it too). Empty: the store fills it
-- on the page Settings > Quick quantities.
--
-- Re-runnable: each object only when missing; no data changed.

-- The session options are set here (sqlcmd starts with QUOTED_IDENTIFIER OFF); SSMS has them on already.

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET NUMERIC_ROUNDABORT OFF;
GO

-- 3.1.1 (or 3.1.2 when run again) first. Otherwise the rest of the script is not executed (NOEXEC, reset at the end).
IF NOT EXISTS (SELECT 1 FROM APP_VERSION WHERE version IN ('3.1.1', '3.1.2'))
BEGIN
	DECLARE @current nvarchar(100) = ISNULL((SELECT TOP 1 version FROM APP_VERSION), N'(empty)');
	RAISERROR(N'3.1.2 bulk-sale: APP_VERSION is %s; run db/3.1.1/update.sql first. Nothing was changed.', 16, 1, @current);
	SET NOEXEC ON;
END
GO

-- "Vendu en vrac" on the item (as Item.soldInBulk), store and head office
IF OBJECT_ID(N'item', N'U') IS NOT NULL AND COL_LENGTH(N'item', N'sold_in_bulk') IS NULL
	ALTER TABLE item ADD sold_in_bulk bit NULL;
GO

-- The quick quantities of the till (a store only; as the entity QuickQuantity)
IF OBJECT_ID(N'ho_store', N'U') IS NULL AND OBJECT_ID(N'quick_quantity', N'U') IS NULL
BEGIN
	CREATE TABLE quick_quantity (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		label varchar(100) NOT NULL,
		quantity numeric(18,3) NOT NULL,
		linked_item_id bigint NULL,
		display_order int NULL,
		CONSTRAINT PK_quick_quantity PRIMARY KEY (id),
		CONSTRAINT fk_quick_quantity_linked_item FOREIGN KEY (linked_item_id) REFERENCES item (id)
	);
END
GO

SET NOEXEC OFF;
GO
