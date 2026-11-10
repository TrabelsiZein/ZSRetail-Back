-- ZS Retail 4.0.0, catch-up 1 (Boot 4 migration, step 1.8): the indexes that only the scripts of db/<version>/ made.
--
-- A database upgraded through those scripts has them. A database a version created from empty never had them (Hibernate
-- makes only the indexes the entities name), and neither has the baseline, which is such a database. Each one is created
-- where its table is and it is missing, so a new database and an upgraded one end with the same indexes. Nothing on a
-- database that has them. Store and head office (the scripts made them on both where the table is).

-- 3.1.2 (db/3.1.2/update.sql): an item's last stock movements, newest first, and the movements after a day for the stock
-- value at a date
IF OBJECT_ID(N'stock_movement', N'U') IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_stock_movement_item' AND object_id = OBJECT_ID(N'stock_movement'))
	CREATE INDEX ix_stock_movement_item ON stock_movement (item_id, id);
GO
IF OBJECT_ID(N'stock_movement', N'U') IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_stock_movement_created' AND object_id = OBJECT_ID(N'stock_movement'))
	CREATE INDEX ix_stock_movement_created ON stock_movement (created_at) INCLUDE (item_id, direction, quantity);
GO

-- 3.1.1 (db/3.1.1/update.sql): the till's tickets of a session, by status
IF OBJECT_ID(N'sales_header', N'U') IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_sales_header_session_status' AND object_id = OBJECT_ID(N'sales_header'))
	CREATE INDEX ix_sales_header_session_status ON sales_header (cashier_session_id, status);
GO

-- 3.1.1 (db/3.1.1/store-orders.sql): the lines of a store's order, at the head office
IF OBJECT_ID(N'ho_store_order_line', N'U') IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_ho_store_order_line_order' AND object_id = OBJECT_ID(N'ho_store_order_line'))
	CREATE INDEX ix_ho_store_order_line_order ON ho_store_order_line (order_id);
GO

-- 3.1.0 (db/3.1.0/update.sql): a till machine's RFID reader, on a store
IF OBJECT_ID(N'pos_machine', N'U') IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_pos_machine_reader' AND object_id = OBJECT_ID(N'pos_machine'))
	CREATE INDEX ix_pos_machine_reader ON pos_machine (reader_id);
GO
