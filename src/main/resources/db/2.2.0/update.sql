-- ============================================================
-- Version 2.2.0 — Points de stock au siège (head office whose catalogue comes from the ERP)
-- ============================================================
-- Deployment order:
--   1) Run db/2.1.0 first if the database is older than 2.1.0.
--   2) Run this script against the database.
--   3) Deploy the 2.2.0 binaries (backend WAR and frontend).
-- The application refuses to start while APP_VERSION differs from its own version, so step 2 must be done before 3.
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

UPDATE APP_VERSION SET version = '2.2.0';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('2.2.0', 'NEW', 'Siège avec le catalogue de l''ERP : plusieurs points de stock. Chaque point de stock est un emplacement de l''ERP, lu par la tâche des articles ; chaque magasin reçoit seulement les articles de son point de stock, avec leur nom, leur famille et leur prix dans ce point.'),
('2.2.0', 'NEW', 'Siège : pages Points de stock et Articles par point de stock (menu Catalogue), et choix du point de stock de chaque magasin sur la page des magasins. Un magasin sans point de stock reçoit tous les articles comme avant.'),
('2.2.0', 'IMPROVE', 'Factures de l''ERP : un avertissement nomme les articles qui ne sont pas dans le point de stock du magasin.'),
('2.2.0', 'IMPROVE', 'La ligne erp.navpospages.location-code n''est plus lue : les points de stock se créent au siège avant la tâche des articles.');
