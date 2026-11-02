-- ============================================================
-- Version 3.1.2 — Unit cost frozen on the ticket lines
-- ============================================================
-- Run BEFORE db/3.1.2/update.sql (which stamps APP_VERSION 3.1.2), on a store and on a head office: the same file.
-- The file is UTF-8: with sqlcmd add -f 65001 (sqlcmd ... -f 65001 -i sales-line-unit-cost.sql); in SSMS open it as UTF-8.
-- See docs/backend.md (item costs) and docs/modules/head-office.md, "Item costs (CMP)".
--
-- Schema: sales_line.unit_cost and return_line.unit_cost, DECIMAL(19,3) NULL, the name and type Hibernate uses (a start
-- adds them too). A sales line takes the item's CMP (item.cost_price) when its ticket is completed and keeps it; a return
-- line takes the cost of its sales line. NULL means the cost was unknown at that time: the lines written before 3.1.2
-- stay NULL (no estimate from today's CMP).
--
-- Re-runnable: each column only when missing; no data changed.

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
	RAISERROR(N'3.1.2 sales-line-unit-cost: APP_VERSION is %s; run db/3.1.1/update.sql first. Nothing was changed.', 16, 1, @current);
	SET NOEXEC ON;
END
GO

-- The unit cost of a ticket line (as SalesLine.unitCost)
IF OBJECT_ID(N'sales_line', N'U') IS NOT NULL AND COL_LENGTH(N'sales_line', N'unit_cost') IS NULL
	ALTER TABLE sales_line ADD unit_cost DECIMAL(19,3) NULL;
GO

-- The unit cost of a return line (as ReturnLine.unitCost)
IF OBJECT_ID(N'return_line', N'U') IS NOT NULL AND COL_LENGTH(N'return_line', N'unit_cost') IS NULL
	ALTER TABLE return_line ADD unit_cost DECIMAL(19,3) NULL;
GO

SET NOEXEC OFF;
GO
