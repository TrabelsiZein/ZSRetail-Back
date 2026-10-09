-- ============================================================
-- Version 2.2.0 — Points de stock au siège (head office whose catalogue comes from the ERP)
-- ============================================================
-- In progress on release/2.2.0: steps 2 to 6 add to this script; the APP_VERSION update and the release notes come
-- at step 6.
--
-- Deployment order:
--   1) Run db/2.1.0 first if the database is older than 2.1.0.
--   2) Run this script against the database.
--   3) Deploy the 2.2.0 binaries (backend WAR and frontend).
--   4) On a head office that reads its catalogue from the ERP (erp.navpospages.enabled=true): create its points de
--      stock (Catalogue > Points de stock) before the next items run. erp.navpospages.location-code is no longer
--      required.
--
-- Schema: the new tables ho_stock_point and ho_stock_point_item (both exist, empty, in every database) are created by
-- Hibernate ddl-auto=update at the first start. The statement below adds the new nullable column of an existing table
-- (null = no point de stock: the store receives the catalogue as in 2.1). It does nothing when the column is already
-- there or when ho_store does not exist yet (Hibernate then creates it whole). No data is changed.

-- Stock points, step 1: the store's point de stock
IF OBJECT_ID('ho_store') IS NOT NULL AND COL_LENGTH('ho_store', 'stock_point_id') IS NULL
	ALTER TABLE ho_store ADD stock_point_id bigint NULL;
GO

-- Stock points, step 4: the last read of a point (only for a database already started by an earlier 2.2 build;
-- otherwise Hibernate creates ho_stock_point whole)
IF OBJECT_ID('ho_stock_point') IS NOT NULL
BEGIN
	IF COL_LENGTH('ho_stock_point', 'last_read_at') IS NULL ALTER TABLE ho_stock_point ADD last_read_at datetime2 NULL;
	IF COL_LENGTH('ho_stock_point', 'last_read_status') IS NULL ALTER TABLE ho_stock_point ADD last_read_status varchar(20) NULL;
	IF COL_LENGTH('ho_stock_point', 'last_read_summary') IS NULL ALTER TABLE ho_stock_point ADD last_read_summary varchar(255) NULL;
END
GO
