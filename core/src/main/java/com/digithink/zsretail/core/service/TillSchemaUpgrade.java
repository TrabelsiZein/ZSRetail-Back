package com.digithink.zsretail.core.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.log4j.Log4j2;

/**
 * Till rights (3.1.0): the column changes Hibernate cannot make (ddl-auto adds columns, never relaxes a NOT NULL), also
 * done by db/3.1.0/update.sql; here at each start too, before ZZDataInitializer writes anything, so a database already
 * at 3.1.0 that does not run the script again keeps working. Each statement is guarded (it changes nothing the second
 * time); a failure is logged, never stops the start.
 * <ul>
 * <li>step C: badge_scan_log.scanned_badge_code nullable (a pass on the role's right has no badge), and the rows of
 * before marked as scans (granted_by BADGE);</li>
 * </ul>
 */
@Component
@Log4j2
public class TillSchemaUpgrade {

	static final String SCAN_LOG_BADGE_CODE_NULLABLE = "IF EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID(N'badge_scan_log')"
			+ " AND name = 'scanned_badge_code' AND is_nullable = 0)"
			+ " ALTER TABLE badge_scan_log ALTER COLUMN scanned_badge_code varchar(50) NULL";

	static final String SCAN_LOG_GRANTED_BY_OF_BEFORE = "IF COL_LENGTH('badge_scan_log', 'granted_by') IS NOT NULL"
			+ " EXEC('UPDATE badge_scan_log SET granted_by = ''BADGE'' WHERE granted_by IS NULL')";

	private final JdbcTemplate jdbc;

	public TillSchemaUpgrade(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void run() {
		execute("badge_scan_log.scanned_badge_code nullable", SCAN_LOG_BADGE_CODE_NULLABLE);
		execute("badge_scan_log.granted_by of the rows of before", SCAN_LOG_GRANTED_BY_OF_BEFORE);
	}

	private void execute(String what, String sql) {
		try {
			jdbc.execute(sql);
		} catch (RuntimeException e) {
			log.warn("Till rights schema: {} not done ({}); db/3.1.0/update.sql does it", what, e.getMessage());
		}
	}
}
