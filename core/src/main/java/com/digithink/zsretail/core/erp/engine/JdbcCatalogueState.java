package com.digithink.zsretail.core.erp.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.config.ConditionalOnErpAbility;
import com.digithink.zsretail.erp.api.ErpAbility;
import com.digithink.zsretail.erp.api.ErpConnectorDescriptor;
import com.digithink.zsretail.erp.api.ErpConnectors;

import lombok.extern.log4j.Log4j2;

/**
 * ERP catalogue, step 6: the state of the catalogue engine in its own small table, erp_catalogue_state (state_key,
 * state_value, updated_at). Not a JPA entity on purpose: Hibernate (ddl-auto=update) creates the table of every entity
 * on every installation, while this table exists only where the engine runs. It is made ready here, at the start
 * (SQL Server), by this bean, which exists only with a connector that has the ability HEAD_OFFICE_STOCK_POINTS: a
 * store or a head office without one never gets it. Code cleanup step 3: was JdbcNavPosPagesState.
 * <p>
 * Code cleanup step 8 (Zein, 2026-10-11): the table was named after a connector up to 2.2.2; that connector declares
 * the old name ({@link ErpConnectorDescriptor#oldCatalogueStateTable()}). At the start, by the tables found:
 * <ul>
 * <li>the new one missing, an old one present: renamed, with its primary key ({@link #rename}, one transaction, every
 * row kept), and one INFO line;</li>
 * <li>both present: the start is refused, naming both tables (nothing changed);</li>
 * <li>neither: the new one is created ({@link #CREATE});</li>
 * <li>the new one alone: nothing to do.</li>
 * </ul>
 */
@Component
@ConditionalOnErpAbility(ErpAbility.HEAD_OFFICE_STOCK_POINTS)
@Log4j2
public class JdbcCatalogueState implements CatalogueState {

	static final String TABLE = "erp_catalogue_state";

	static final String CREATE = "IF OBJECT_ID(N'" + TABLE + "', N'U') IS NULL CREATE TABLE " + TABLE
			+ " (state_key NVARCHAR(200) NOT NULL CONSTRAINT pk_" + TABLE + " PRIMARY KEY,"
			+ " state_value NVARCHAR(200) NULL, updated_at DATETIME2 NULL)";

	private final JdbcTemplate jdbc;

	public JdbcCatalogueState(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** The old names of the table, as the connectors found declare them (a table name: letters, digits, _). */
	static List<String> oldTables() {
		List<String> names = ErpConnectors.descriptors().stream().map(ErpConnectorDescriptor::oldCatalogueStateTable)
				.filter(name -> name != null).distinct().collect(Collectors.toList());
		for (String name : names) {
			if (!name.matches("[a-z][a-z0-9_]*")) {
				throw new IllegalStateException("Invalid old catalogue state table name: " + name);
			}
		}
		return names;
	}

	/** Which of these tables exist, lower case (SQL Server and the H2 of the tests answer it alike). */
	static String find(List<String> tables) {
		return "SELECT LOWER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) IN ('"
				+ String.join("', '", tables) + "')";
	}

	/** The old table and its primary key renamed in one transaction (sp_rename keeps every row). */
	static String rename(String old) {
		return "BEGIN TRY\n"
				+ " BEGIN TRANSACTION;\n"
				+ " EXEC sp_rename N'" + old + "', N'" + TABLE + "';\n"
				+ " IF OBJECT_ID(N'pk_" + old + "', N'PK') IS NOT NULL\n"
				+ "  EXEC sp_rename N'pk_" + old + "', N'pk_" + TABLE + "', N'OBJECT';\n"
				+ " COMMIT TRANSACTION;\n"
				+ "END TRY\n"
				+ "BEGIN CATCH\n"
				+ " IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n"
				+ " THROW;\n"
				+ "END CATCH";
	}

	@PostConstruct
	void prepareTable() {
		List<String> tables = new ArrayList<>(oldTables());
		tables.add(TABLE);
		List<String> found = jdbc.queryForList(find(tables), String.class);
		List<String> old = found.stream().filter(name -> !TABLE.equals(name)).sorted()
				.collect(Collectors.toList());
		if (found.contains(TABLE) && !old.isEmpty() || old.size() > 1) {
			List<String> named = new ArrayList<>(old);
			if (found.contains(TABLE)) {
				named.add(TABLE);
			}
			throw new IllegalStateException("Two tables hold the state of the ERP catalogue engine: "
					+ String.join(" and ", named) + " (" + TABLE + " is its name since code cleanup step 8). Keep the"
					+ " one with this head office's state (its barcodes.cursor row), drop the other, then start again;"
					+ " nothing was changed.");
		}
		if (!old.isEmpty()) {
			jdbc.execute(rename(old.get(0)));
			Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE, Integer.class);
			log.info("ERP catalogue engine: state table {} renamed {} with its primary key, {} rows kept", old.get(0),
					TABLE, rows);
		} else if (!found.contains(TABLE)) {
			jdbc.execute(CREATE);
			log.info("ERP catalogue engine: state table {} created", TABLE);
		} else {
			log.info("ERP catalogue engine: state table {} ready", TABLE);
		}
	}

	@Override
	public String get(String key) {
		List<String> values = jdbc.queryForList("SELECT state_value FROM " + TABLE + " WHERE state_key = ?",
				String.class, key);
		return values.isEmpty() ? null : values.get(0);
	}

	@Override
	public void put(String key, String value) {
		int updated = jdbc.update("UPDATE " + TABLE + " SET state_value = ?, updated_at = SYSDATETIME() WHERE state_key = ?",
				value, key);
		if (updated == 0) {
			jdbc.update("INSERT INTO " + TABLE + " (state_key, state_value, updated_at) VALUES (?, ?, SYSDATETIME())", key,
					value);
		}
	}

	@Override
	public void remove(String key) {
		jdbc.update("DELETE FROM " + TABLE + " WHERE state_key = ?", key);
	}

	@Override
	public List<String> keysStartingWith(String prefix) {
		String escaped = prefix.replace("[", "[[]").replace("%", "[%]").replace("_", "[_]");
		return jdbc.queryForList("SELECT state_key FROM " + TABLE + " WHERE state_key LIKE ? ORDER BY state_key",
				String.class, escaped + "%");
	}
}
