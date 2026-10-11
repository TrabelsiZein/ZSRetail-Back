package com.digithink.zsretail.core.erp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Code cleanup step 8: the catalogue state table renamed erp_catalogue_state at the start, in its three cases, on H2
 * (MODE=MSSQLServer, as the query tests). What H2 proves: the detection query against real tables, the case chosen,
 * the refusal when both exist (nothing changed), and the rows found under the new name. What it cannot run: the T-SQL
 * itself (IF, OBJECT_ID, EXEC sp_rename, BEGIN TRY, THROW). {@link SqlServerStandIn} records each such statement exactly
 * as sent, and makes the same change with H2's own SQL: the text is pinned here, its effect on SQL Server is proved on a
 * real database only (docs/deploy-note.md).
 */
class JdbcCatalogueStateTest {

	static final String OLD = "navpospages_state";
	static final String NEW = "erp_catalogue_state";

	/** The exact T-SQL of the rename, as it runs on SQL Server. */
	static final String RENAME = "BEGIN TRY\n"
			+ " BEGIN TRANSACTION;\n"
			+ " EXEC sp_rename N'navpospages_state', N'erp_catalogue_state';\n"
			+ " IF OBJECT_ID(N'pk_navpospages_state', N'PK') IS NOT NULL\n"
			+ "  EXEC sp_rename N'pk_navpospages_state', N'pk_erp_catalogue_state', N'OBJECT';\n"
			+ " COMMIT TRANSACTION;\n"
			+ "END TRY\n"
			+ "BEGIN CATCH\n"
			+ " IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n"
			+ " THROW;\n"
			+ "END CATCH";

	/** The exact T-SQL of the creation. */
	static final String CREATE = "IF OBJECT_ID(N'erp_catalogue_state', N'U') IS NULL CREATE TABLE erp_catalogue_state"
			+ " (state_key NVARCHAR(200) NOT NULL CONSTRAINT pk_erp_catalogue_state PRIMARY KEY, state_value NVARCHAR(200)"
			+ " NULL, updated_at DATETIME2 NULL)";

	/** A JdbcTemplate on H2 that records the T-SQL statements and makes their change with H2's SQL. */
	static final class SqlServerStandIn extends JdbcTemplate {
		final List<String> executed = new ArrayList<>();

		SqlServerStandIn() {
			super(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MSSQLServer"));
		}

		@Override
		public void execute(String sql) {
			if (sql.equals(RENAME)) {
				executed.add(sql);
				super.execute("ALTER TABLE " + OLD + " RENAME TO " + NEW);
				super.execute("ALTER TABLE " + NEW + " RENAME CONSTRAINT pk_" + OLD + " TO pk_" + NEW);
			} else if (sql.equals(CREATE)) {
				executed.add(sql);
				table(NEW);
			} else {
				super.execute(sql);
			}
		}

		/** A table as the bean creates it, under that name. */
		void table(String name) {
			super.execute("CREATE TABLE " + name + " (state_key NVARCHAR(200) NOT NULL CONSTRAINT pk_" + name
					+ " PRIMARY KEY, state_value NVARCHAR(200) NULL, updated_at DATETIME2 NULL)");
		}

		void row(String table, String key, String value) {
			update("INSERT INTO " + table + " (state_key, state_value) VALUES (?, ?)", key, value);
		}

		List<String> tables() {
			return queryForList("SELECT LOWER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) IN ('" + OLD
					+ "', '" + NEW + "') ORDER BY 1", String.class);
		}

		List<String> rows(String table) {
			return queryForList("SELECT state_key || '=' || state_value FROM " + table + " ORDER BY state_key", String.class);
		}
	}

	@Test
	@DisplayName("The T-SQL is the one documented, and the old name comes from the connector (Happyness)")
	void statements() {
		assertEquals(RENAME, JdbcCatalogueState.rename(OLD));
		assertEquals(CREATE, JdbcCatalogueState.CREATE);
		assertEquals(Collections.singletonList(OLD), JdbcCatalogueState.oldTables());
		assertEquals("SELECT LOWER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) IN"
				+ " ('navpospages_state', 'erp_catalogue_state')", JdbcCatalogueState.find(Arrays.asList(OLD, NEW)));
	}

	@Test
	@DisplayName("The new table missing, the old one present: renamed with its primary key, every row kept")
	void oldOnlyRenamed() {
		SqlServerStandIn db = new SqlServerStandIn();
		db.table(OLD);
		db.row(OLD, "barcodes.cursor", "48213");
		db.row(OLD, "items.run", "true");
		db.row(OLD, "needs-barcodes:A100", "1");
		JdbcCatalogueState state = new JdbcCatalogueState(db);
		state.prepareTable();
		assertEquals(Collections.singletonList(RENAME), db.executed);
		assertEquals(Collections.singletonList(NEW), db.tables());
		assertEquals(Arrays.asList("barcodes.cursor=48213", "items.run=true", "needs-barcodes:A100=1"), db.rows(NEW));
		assertEquals("48213", state.get("barcodes.cursor"), "read under the new name");
		assertEquals(Collections.singletonList("pk_" + NEW), db.queryForList("SELECT LOWER(CONSTRAINT_NAME) FROM"
				+ " INFORMATION_SCHEMA.CONSTRAINTS WHERE LOWER(TABLE_NAME) = '" + NEW + "'", String.class));
	}

	@Test
	@DisplayName("Both present: the start is refused naming both tables, nothing changed")
	void bothRefused() {
		SqlServerStandIn db = new SqlServerStandIn();
		db.table(OLD);
		db.table(NEW);
		db.row(OLD, "barcodes.cursor", "48213");
		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> new JdbcCatalogueState(db).prepareTable());
		assertEquals("Two tables hold the state of the ERP catalogue engine: navpospages_state and erp_catalogue_state"
				+ " (erp_catalogue_state is its name since code cleanup step 8). Keep the one with this head office's state"
				+ " (its barcodes.cursor row), drop the other, then start again; nothing was changed.", refused.getMessage());
		assertEquals(Collections.emptyList(), db.executed);
		assertEquals(Arrays.asList(NEW, OLD), db.tables());
		assertEquals(Collections.singletonList("barcodes.cursor=48213"), db.rows(OLD));
	}

	@Test
	@DisplayName("Neither: the new table is created; the new one alone: nothing runs")
	void neitherCreatedNewAloneKept() {
		SqlServerStandIn db = new SqlServerStandIn();
		new JdbcCatalogueState(db).prepareTable();
		assertEquals(Collections.singletonList(CREATE), db.executed);
		assertEquals(Collections.singletonList(NEW), db.tables());

		db.executed.clear();
		db.row(NEW, "barcodes.cursor", "7");
		new JdbcCatalogueState(db).prepareTable(); // the next start
		assertEquals(Collections.emptyList(), db.executed);
		assertEquals(Collections.singletonList("barcodes.cursor=7"), db.rows(NEW));
	}
}
