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
 * <li>step E: user_account.role nullable (the entity no longer has the enum; a user created now leaves it empty), its
 * type kept; and {@link #assignRolesOfLegacyUsers}, the users of before without an AppRole given the role of their
 * enum's name.</li>
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

	/** Step E: the column of the retired enum, nullable, with its own type and length (read, never assumed). */
	static final String USER_ROLE_NULLABLE = "DECLARE @type nvarchar(200) = (SELECT TYPE_NAME(user_type_id)"
			+ " + CASE WHEN max_length = -1 THEN '(max)' WHEN TYPE_NAME(user_type_id) IN ('nvarchar', 'nchar')"
			+ " THEN '(' + CAST(max_length / 2 AS varchar(10)) + ')' ELSE '(' + CAST(max_length AS varchar(10)) + ')' END"
			+ " FROM sys.columns WHERE object_id = OBJECT_ID(N'user_account') AND name = 'role' AND is_nullable = 0);"
			+ " IF @type IS NOT NULL EXEC('ALTER TABLE user_account ALTER COLUMN role ' + @type + ' NULL')";

	/** Step E: a user of before without an AppRole gets the role named as his enum (ADMIN, RESPONSIBLE, POS_USER). */
	static final String LEGACY_USERS_ROLE = "IF COL_LENGTH('user_account', 'role') IS NOT NULL"
			+ " EXEC('UPDATE u SET u.app_role_id = r.id, u.updated_by = ''System'' FROM user_account u"
			+ " JOIN app_role r ON r.name = u.role WHERE u.app_role_id IS NULL')";

	private final JdbcTemplate jdbc;

	public TillSchemaUpgrade(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void run() {
		execute("badge_scan_log.scanned_badge_code nullable", SCAN_LOG_BADGE_CODE_NULLABLE);
		execute("badge_scan_log.granted_by of the rows of before", SCAN_LOG_GRANTED_BY_OF_BEFORE);
		execute("user_account.role nullable", USER_ROLE_NULLABLE);
	}

	/**
	 * Step E: the users of before that have no AppRole yet get the role named as their enum value (it did this through
	 * the entity's enum until step E); after the roles exist (ZZDataInitializer.ensureDefaultRoles). A head office has
	 * no RESPONSIBLE or POS_USER role: such a user keeps none, as before.
	 */
	public void assignRolesOfLegacyUsers() {
		execute("the roles of the users of before", LEGACY_USERS_ROLE);
	}

	private void execute(String what, String sql) {
		try {
			jdbc.execute(sql);
		} catch (RuntimeException e) {
			log.warn("Till rights schema: {} not done ({}); db/3.1.0/update.sql does it", what, e.getMessage());
		}
	}
}
