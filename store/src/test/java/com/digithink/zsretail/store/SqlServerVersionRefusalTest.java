package com.digithink.zsretail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.config.SqlServerVersionCheck;
import com.digithink.zsretail.core.support.MigTestApplication;

/**
 * Boot 4 migration, decision 2: a real start on a SQL Server below the minimum stops before Hibernate has touched the
 * database. The server here is too new to refuse, so the same check runs with a minimum it cannot reach.
 */
class SqlServerVersionRefusalTest {

	private static String messages(Throwable failure) {
		StringBuilder messages = new StringBuilder();
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			messages.append(cause.getMessage()).append('\n');
		}
		return messages.toString();
	}

	@Test
	@DisplayName("Refused with its message, and not one table created")
	void refusedBeforeHibernate() {
		MigTestApplication.Refusal refusal = MigTestApplication.startRefused(StoreApplication.class, "store",
				new SqlServerVersionCheck(99));

		assertNotNull(refusal.failure);
		assertTrue(messages(refusal.failure).contains("needs SQL Server 2017 or later"), messages(refusal.failure));
		assertEquals(0, refusal.tablesLeft, "ddl-auto ran before the check");
	}
}
