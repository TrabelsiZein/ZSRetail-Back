package com.digithink.zsretail.erp.engine;

import java.util.List;

import javax.annotation.PostConstruct;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnErpAbility;
import com.digithink.zsretail.erp.spi.ErpAbility;


import lombok.extern.log4j.Log4j2;

/**
 * ERP catalogue, step 6: the state of the catalogue engine in its own small table, navpospages_state (state_key,
 * state_value, updated_at). Not a JPA entity on purpose: Hibernate (ddl-auto=update) creates the table of every entity
 * on every installation, while this table exists only where the engine runs. It is created here, at the start, when it
 * is missing (SQL Server), by this bean, which exists only with a connector that has the ability
 * HEAD_OFFICE_STOCK_POINTS: a store or a head office without one never gets it. Code cleanup step 3: was
 * JdbcNavPosPagesState; the table keeps its name (and the engine its keys), the live head office keeps its state.
 */
@Component
@ConditionalOnErpAbility(ErpAbility.HEAD_OFFICE_STOCK_POINTS)
@Log4j2
public class JdbcCatalogueState implements CatalogueState {

	static final String TABLE = "navpospages_state";

	static final String CREATE = "IF OBJECT_ID(N'" + TABLE + "', N'U') IS NULL CREATE TABLE " + TABLE
			+ " (state_key NVARCHAR(200) NOT NULL CONSTRAINT pk_" + TABLE + " PRIMARY KEY,"
			+ " state_value NVARCHAR(200) NULL, updated_at DATETIME2 NULL)";

	private final JdbcTemplate jdbc;

	public JdbcCatalogueState(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@PostConstruct
	void createTable() {
		jdbc.execute(CREATE);
		log.info("ERP catalogue engine: state table {} ready", TABLE);
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
