-- ============================================================
-- Version 3.1.2 — Cost history of the items (the stock value at a date)
-- ============================================================
-- Run BEFORE db/3.1.2/update.sql (which stamps APP_VERSION 3.1.2), on a store and on a head office: the same file.
-- The file is UTF-8: with sqlcmd add -f 65001 (sqlcmd ... -f 65001 -i item-cost-history.sql); in SSMS open it as UTF-8.
-- See docs/backend.md (item costs) and docs/modules/reporting.md, "Stock report".
--
-- Schema: item_cost_history, one row per change of an item's CMP (item.cost_price), as the entity ItemCostHistory (a
-- start creates it too): written with the change (a purchase, a received ERP invoice or head office invoice, "Ajuster le
-- CMP", an item created or imported with a cost), never on a sale, never updated. Where the installation keeps its own
-- stock only; elsewhere it stays empty. No foreign key to item (an item may be deleted with its history). Its index
-- ix_item_cost_history_item (item_id, changed_at) serves the card's history and the CMP of a past day. Empty at the
-- start: the stock value of a day before 3.1.2 takes the CMP of the item's first change, else its CMP now.
--
-- note: the reason typed in "Ajuster le CMP" (MANUAL rows), null for the others.
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
	RAISERROR(N'3.1.2 item-cost-history: APP_VERSION is %s; run db/3.1.1/update.sql first. Nothing was changed.', 16, 1, @current);
	SET NOEXEC ON;
END
GO

-- The changes of an item's CMP (as ItemCostHistory)
IF OBJECT_ID(N'item_cost_history', N'U') IS NULL
BEGIN
	CREATE TABLE item_cost_history (
		id bigint IDENTITY(1,1) NOT NULL,
		item_id bigint NOT NULL,
		changed_at datetime2 NOT NULL,
		old_cost float NULL,
		new_cost float NOT NULL,
		source varchar(30) NOT NULL,
		document_type varchar(20) NULL,
		document_number varchar(100) NULL,
		qty_in numeric(18,3) NULL,
		unit_cost_in float NULL,
		changed_by varchar(100) NULL,
		note varchar(500) NULL,
		CONSTRAINT PK_item_cost_history PRIMARY KEY (id)
	);
END
GO

-- The reason of a change by hand ("Ajuster le CMP"), added when missing: a database that ran this script before it
IF OBJECT_ID(N'item_cost_history', N'U') IS NOT NULL AND COL_LENGTH(N'item_cost_history', N'note') IS NULL
	ALTER TABLE item_cost_history ADD note varchar(500) NULL;
GO

IF OBJECT_ID(N'item_cost_history', N'U') IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_item_cost_history_item' AND object_id = OBJECT_ID(N'item_cost_history'))
	CREATE INDEX ix_item_cost_history_item ON item_cost_history (item_id, changed_at);
GO

SET NOEXEC OFF;
GO
