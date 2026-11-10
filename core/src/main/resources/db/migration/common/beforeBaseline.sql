-- ZS Retail 4.0.0 (Boot 4 migration, step 1.8): Flyway runs this before it takes over an existing database, one with tables
-- and no flyway_schema_history (spring.flyway.baseline-on-migrate). 4.0.0 takes over a database at 3.1.2 only, the version
-- its baseline is (db/migration/<type>/V4.0.0__baseline.sql). Any other is refused here, before Flyway writes anything: it
-- creates its history table after this callback, so a refused database is left exactly as it was, and the start stops
-- before Hibernate. To upgrade an older one: bring it to 3.1.2 with the scripts of db/<version>/ (their update.sql says the
-- order), start 3.1.2 once if its notes ask for it, then deploy 4.0.0.
--
-- What makes "3.1.2": APP_VERSION says so, and what db/3.1.2/update.sql checks before it stamps the version is there (a
-- database stamped by hand, or while 3.1.2 was being built, can lack a part: seen on a development head office on
-- 2026-11-10, item.sold_in_bulk missing).

IF OBJECT_ID(N'APP_VERSION', N'U') IS NULL
	RAISERROR(N'ZS Retail 4.0.0 takes over a database at 3.1.2 only. This one has tables but no APP_VERSION table: it is not a ZS Retail database, or not this installation''s. Nothing was changed.', 16, 1);
ELSE IF NOT EXISTS (SELECT 1 FROM APP_VERSION WHERE version = '3.1.2')
BEGIN
	DECLARE @found nvarchar(100) = ISNULL((SELECT TOP 1 version FROM APP_VERSION), N'(empty)');
	RAISERROR(N'ZS Retail 4.0.0 takes over a database at 3.1.2 only; this one is at %s. Bring it to 3.1.2 first with the scripts of db/<version>/ (their update.sql), then start 4.0.0 again. Nothing was changed.', 16, 1, @found);
END
ELSE IF (OBJECT_ID(N'sales_line', N'U') IS NOT NULL AND COL_LENGTH(N'sales_line', N'unit_cost') IS NULL)
	OR (OBJECT_ID(N'return_line', N'U') IS NOT NULL AND COL_LENGTH(N'return_line', N'unit_cost') IS NULL)
	RAISERROR(N'ZS Retail 4.0.0: APP_VERSION says 3.1.2 but the database lacks the unit_cost column of the ticket lines: run db/3.1.2/sales-line-unit-cost.sql, then start 4.0.0 again. Nothing was changed.', 16, 1);
ELSE IF OBJECT_ID(N'item_cost_history', N'U') IS NULL
	RAISERROR(N'ZS Retail 4.0.0: APP_VERSION says 3.1.2 but the database has no item_cost_history table: run db/3.1.2/item-cost-history.sql, then start 4.0.0 again. Nothing was changed.', 16, 1);
ELSE IF (OBJECT_ID(N'item', N'U') IS NOT NULL AND COL_LENGTH(N'item', N'sold_in_bulk') IS NULL)
	OR (OBJECT_ID(N'ho_store', N'U') IS NULL AND OBJECT_ID(N'quick_quantity', N'U') IS NULL)
	RAISERROR(N'ZS Retail 4.0.0: APP_VERSION says 3.1.2 but the database lacks the bulk sale (item.sold_in_bulk, or on a store the quick_quantity table): run db/3.1.2/bulk-sale.sql, then start 4.0.0 again. Nothing was changed.', 16, 1);
