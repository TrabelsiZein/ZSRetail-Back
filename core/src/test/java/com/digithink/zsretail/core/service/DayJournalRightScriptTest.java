package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.controller.AppRoleAPI;

/**
 * 3.2.0, the Journal's own right (read:day-journal): db/3.2.0/update.sql step 20 gives it to every role that holds
 * read:admin-sessions-history, once (a second run adds nothing), and removes nothing; a new database seeds it with the
 * Sessions' right on ADMIN and RESPONSIBLE; the Roles page lists it. Reads the script's text: no database (the step was run
 * twice on a test database by hand, see the CHANGELOG).
 */
class DayJournalRightScriptTest {

	private static final String SCRIPT = "/db/3.2.0/update.sql";

	private static String statements() throws Exception {
		try (InputStream in = DayJournalRightScriptTest.class.getResourceAsStream(SCRIPT)) {
			assertNotNull(in, SCRIPT);
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			return text.replaceAll("(?m)--.*$", " ").replaceAll("\\s+", " ");
		}
	}

	@Test
	@DisplayName("Step 20: every role with read:admin-sessions-history gets read:day-journal, only when it lacks it")
	void givenToTheRolesOfTheSessionsRight() throws Exception {
		String sql = statements();
		assertTrue(sql.contains("IF OBJECT_ID(N'app_role_permission', N'U') IS NOT NULL"
				+ " INSERT INTO app_role_permission (role_id, permission_key)"
				+ " SELECT DISTINCT p.role_id, 'read:day-journal' FROM app_role_permission p"
				+ " WHERE p.permission_key = 'read:admin-sessions-history'"
				+ " AND NOT EXISTS (SELECT 1 FROM app_role_permission q WHERE q.role_id = p.role_id"
				+ " AND q.permission_key = 'read:day-journal');"), sql);
		assertEquals(1, count(sql, "'read:day-journal'") - 1, "one insert (and its guard), nothing else");
		assertEquals(0, count(sql, "DELETE FROM app_role_permission WHERE[^;]*read:(day-journal|admin-sessions-history)"));
		assertTrue(sql.indexOf("'read:day-journal'") < sql.indexOf("UPDATE APP_VERSION SET version = '3.2.0';"),
				"before the version is written");
	}

	@Test
	@DisplayName("The Roles page lists read:day-journal beside the Sessions' right")
	void listed() {
		java.util.List<String> all = new AppRoleAPI().getAllPermissions();
		assertTrue(all.contains("read:day-journal"));
		assertEquals(all.indexOf("read:admin-sessions-history") + 1, all.indexOf("read:day-journal"));
	}

	private static int count(String text, String regex) {
		Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
		int count = 0;
		while (matcher.find()) {
			count++;
		}
		return count;
	}
}
