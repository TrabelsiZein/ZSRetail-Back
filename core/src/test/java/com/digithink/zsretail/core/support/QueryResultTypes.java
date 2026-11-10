package com.digithink.zsretail.core.support;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * Boot 4 migration, step 1.8: the Java class a native query and an HQL aggregate return, for each SQL Server type and
 * aggregate our queries use, on real SQL Server. The code casts the Object[] rows of its queries ((Long) row[0], (BigDecimal)
 * ...): a class that changes between Hibernate versions breaks the cast at run time only. The same list ran on
 * release/3.1.2's Hibernate 5.4 (docs/migration/diffs/step-1.8-result-types.txt); a record pins Hibernate 6.6's, to read
 * again at step 2.2 on Hibernate 7.
 * <p>
 * Reads the table item: two items are added first ({@link #ITEMS}), on a database of its own.
 */
public final class QueryResultTypes {

	/** The two items the aggregates read, as Hibernate 5.4's run had them. */
	public static final String ITEMS = "INSERT INTO item (item_code, name, show_in_pos, unit_price, cost_price, stock_quantity,"
			+ " min_stock_level, created_at, created_by) VALUES ('MIG-1', 'Migration item 1', 1, 1.5, 1.25, 2.125, 3,"
			+ " '2026-11-10T10:00:00', 'migtest'), ('MIG-2', 'Migration item 2', 1, 3.0, 2.5, 4.25, 6, '2026-11-10T10:00:00',"
			+ " 'migtest')";

	public static final String[] NATIVE = { "CAST(1 AS bigint)", "CAST(1 AS int)", "CAST(1 AS smallint)", "CAST(1 AS tinyint)",
			"CAST(1 AS bit)", "CAST(1.5 AS float)", "CAST(1.5 AS real)", "CAST(1.5 AS numeric(18,3))", "CAST(1.5 AS decimal(19,2))",
			"CAST(1.5 AS numeric(38,5))", "CAST('x' AS varchar(10))", "CAST(N'x' AS nvarchar(10))", "CAST('x' AS char(1))",
			"CAST('x' AS varchar(max))", "CAST(N'x' AS nvarchar(max))", "CAST('2026-01-01' AS date)",
			"CAST('2026-01-01T10:00:00' AS datetime2)", "CAST('2026-01-01T10:00:00' AS datetime)", "SYSDATETIME()",
			"CAST('10:00' AS time)", "CAST('2026-01-01T10:00:00+01:00' AS datetimeoffset)", "NEWID()", "CAST(0x01 AS varbinary(10))",
			"(SELECT COUNT(*) FROM item)", "(SELECT COUNT_BIG(*) FROM item)", "(SELECT SUM(min_stock_level) FROM item)",
			"(SELECT SUM(id) FROM item)", "(SELECT SUM(unit_price) FROM item)", "(SELECT SUM(stock_quantity) FROM item)",
			"(SELECT AVG(min_stock_level) FROM item)", "(SELECT AVG(stock_quantity) FROM item)", "(SELECT MAX(created_at) FROM item)",
			"(SELECT SUM(CASE WHEN unit_price > 0 THEN 1 ELSE 0 END) FROM item)", "(SELECT ISNULL(SUM(stock_quantity), 0) FROM item)",
			"(SELECT COALESCE(SUM(unit_price), 0) FROM item)", "(SELECT SUM(unit_price * stock_quantity) FROM item)",
			"(SELECT MAX(CAST(show_in_pos AS int)) FROM item)", "(SELECT MAX(show_in_pos) FROM item)" };

	public static final String[] HQL = { "count(i)", "count(distinct i.itemCode)", "sum(i.id)", "sum(i.minStockLevel)",
			"sum(i.unitPrice)", "sum(i.stockQuantity)", "avg(i.minStockLevel)", "avg(i.unitPrice)", "avg(i.stockQuantity)",
			"max(i.createdAt)", "max(i.id)", "min(i.unitPrice)", "max(i.stockQuantity)", "sum(case when i.unitPrice > 0 then 1 else 0 end)",
			"coalesce(sum(i.stockQuantity), 0)", "coalesce(sum(i.unitPrice), 0)", "coalesce(sum(i.minStockLevel), 0)",
			"sum(i.unitPrice * i.stockQuantity)", "sum(i.minStockLevel * i.unitPrice)", "sum(i.stockQuantity * i.minStockLevel)",
			"sum(i.stockQuantity - i.minStockLevel)", "sum(i.unitPrice) / count(i)", "max(i.showInPos)",
			"sum(case when i.showInPos = true then i.stockQuantity else 0 end)", "coalesce(max(i.unitPrice), 0.0)" };

	private QueryResultTypes() {
	}

	/** "NATIVE expression -> class" and "HQL expression -> class", an error as "ERROR Class: message". */
	public static List<String> of(MigTestApplication started) {
		EntityManagerFactory factory = started.bean(EntityManagerFactory.class);
		List<String> lines = new ArrayList<>();
		for (String expression : NATIVE) {
			lines.add("NATIVE " + expression + " -> " + typeOf(factory, "SELECT " + expression, true));
		}
		for (String expression : HQL) {
			lines.add("HQL " + expression + " -> " + typeOf(factory, "select " + expression + " from Item i", false));
		}
		return lines;
	}

	private static String typeOf(EntityManagerFactory factory, String query, boolean nativeQuery) {
		EntityManager entityManager = factory.createEntityManager();
		try {
			Object value = (nativeQuery ? entityManager.createNativeQuery(query) : entityManager.createQuery(query))
					.getSingleResult();
			return value == null ? "null" : value.getClass().getName();
		} catch (RuntimeException e) {
			Throwable root = e;
			while (root.getCause() != null) {
				root = root.getCause();
			}
			String message = String.valueOf(root.getMessage());
			int end = message.indexOf('\n');
			return "ERROR " + root.getClass().getSimpleName() + ": " + (end < 0 ? message : message.substring(0, end));
		} finally {
			entityManager.close();
		}
	}
}
