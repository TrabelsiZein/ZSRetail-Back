-- ============================================================
-- Version 3.1.1 — Commandes centrale (orders of a store to its head office)
-- ============================================================
-- Run BEFORE db/3.1.1/update.sql (which stamps APP_VERSION 3.1.1), on a store and on a head office: the same file.
-- The file is UTF-8: with sqlcmd add -f 65001 (sqlcmd ... -f 65001 -i store-orders.sql); in SSMS open it as UTF-8.
-- See docs/modules/store-orders.md.
--
-- Schema, store only (a head office database has ho_store and never gets them): hol_order and hol_order_line, the orders
-- of the store to its head office and their lines, with the names and types Hibernate uses (a start creates them too),
-- and the filtered unique index ux_hol_order_draft (one DRAFT at a time; also made at each start, StoreOrderDraftIndex).
-- They exist, empty, on every store; only a store whose goods come from the head office uses them.
--
-- Schema, store and head office: item_image, the images of an item (any number, in sort order). Nothing writes it yet;
-- item.image_url stays the main image of the till, the admin pages and the copies down.
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

-- 3.1.0 (or 3.1.1 when run again) first. Otherwise the rest of the script is not executed (NOEXEC, reset at the end).
IF NOT EXISTS (SELECT 1 FROM APP_VERSION WHERE version IN ('3.1.0', '3.1.1'))
BEGIN
	DECLARE @current nvarchar(100) = ISNULL((SELECT TOP 1 version FROM APP_VERSION), N'(empty)');
	RAISERROR(N'3.1.1 store-orders: APP_VERSION is %s; run db/3.1.0/update.sql first. Nothing was changed.', 16, 1, @current);
	SET NOEXEC ON;
END
GO

-- The orders of the store to its head office (a store only; as the entity StoreOrder)
IF OBJECT_ID(N'ho_store', N'U') IS NULL AND OBJECT_ID(N'hol_order', N'U') IS NULL
BEGIN
	CREATE TABLE hol_order (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		number varchar(30) NOT NULL,
		status varchar(20) NOT NULL,
		sent_at datetime2 NULL,
		nav_order_no varchar(50) NULL,
		error_message varchar(1000) NULL,
		note varchar(500) NULL,
		line_count int NOT NULL,
		total_qty numeric(18,3) NOT NULL,
		CONSTRAINT PK_hol_order PRIMARY KEY (id),
		CONSTRAINT uk_hol_order_number UNIQUE (number)
	);
	CREATE INDEX ix_hol_order_status ON hol_order (status, created_at);
END
GO

-- One DRAFT at a time
IF OBJECT_ID(N'hol_order', N'U') IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ux_hol_order_draft' AND object_id = OBJECT_ID(N'hol_order'))
	CREATE UNIQUE INDEX ux_hol_order_draft ON hol_order (status) WHERE status = 'DRAFT';
GO

-- Their lines, one per item (as the entity StoreOrderLine)
IF OBJECT_ID(N'ho_store', N'U') IS NULL AND OBJECT_ID(N'hol_order_line', N'U') IS NULL
BEGIN
	CREATE TABLE hol_order_line (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		order_id bigint NOT NULL,
		item_id bigint NOT NULL,
		item_code varchar(100) NOT NULL,
		nav_item_no varchar(255) NULL,
		variant_code varchar(50) NULL,
		description varchar(255) NULL,
		variant_description varchar(255) NULL,
		quantity numeric(18,3) NOT NULL,
		CONSTRAINT PK_hol_order_line PRIMARY KEY (id),
		CONSTRAINT uk_hol_order_line_item UNIQUE (order_id, item_id),
		CONSTRAINT fk_hol_order_line_order FOREIGN KEY (order_id) REFERENCES hol_order (id)
	);
END
GO

-- The images of an item (store and head office; as the entity ItemImage)
IF OBJECT_ID(N'item_image', N'U') IS NULL
BEGIN
	CREATE TABLE item_image (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		item_id bigint NOT NULL,
		source varchar(10) NOT NULL,
		path varchar(1000) NOT NULL,
		sort_order int NOT NULL,
		is_main bit NOT NULL,
		CONSTRAINT PK_item_image PRIMARY KEY (id)
	);
	CREATE INDEX ix_item_image_item ON item_image (item_id, sort_order);
END
GO

SET NOEXEC OFF;
GO
