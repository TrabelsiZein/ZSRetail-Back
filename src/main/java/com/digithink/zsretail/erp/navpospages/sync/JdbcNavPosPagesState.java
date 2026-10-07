package com.digithink.zsretail.erp.navpospages.sync;

import java.util.List;

import javax.annotation.PostConstruct;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;

import lombok.extern.log4j.Log4j2;

/**
 * ERP catalogue, step 6: the state of the connector in its own small table, navpospages_state (state_key, state_value,
 * updated_at). Not a JPA entity on purpose: Hibernate (ddl-auto=update) creates the table of every entity on every
 * installation, while this table exists only where the connector is on. It is created here, at the start, when it is
 * missing (SQL Server), by this bean, which exists only with erp.navpospages.enabled=true: a store or a head office
 * without this connector never gets it.
 */
@Component
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
@Log4j2
public class JdbcNavPosPagesState implements NavPosPagesState {

	static final String TABLE = "navpospages_state";

	static final String CREATE = "IF OBJECT_ID(N'" + TABLE + "', N'U') IS NULL CREATE TABLE " + TABLE
			+ " (state_key NVARCHAR(200) NOT NULL CONSTRAINT pk_" + TABLE + " PRIMARY KEY,"
			+ " state_value NVARCHAR(200) NULL, updated_at DATETIME2 NULL)";

	private final JdbcTemplate jdbc;

	public JdbcNavPosPagesState(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@PostConstruct
	void createTable() {
		jdbc.execute(CREATE);
		log.info("ERP connector navpospages: state table {} ready", TABLE);
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
