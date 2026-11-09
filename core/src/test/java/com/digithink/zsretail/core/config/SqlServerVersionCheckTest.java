package com.digithink.zsretail.core.config;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Decision 2 of the Boot 4 migration: below SQL Server 2017 the start is refused. That a real start stops before Hibernate
 * is in the store's SqlServerVersionRefusalTest.
 */
class SqlServerVersionCheckTest {

	private static DataSource server(String product, int major, String version) throws SQLException {
		DatabaseMetaData metaData = mock(DatabaseMetaData.class);
		when(metaData.getDatabaseProductName()).thenReturn(product);
		when(metaData.getDatabaseMajorVersion()).thenReturn(major);
		when(metaData.getDatabaseProductVersion()).thenReturn(version);
		Connection connection = mock(Connection.class);
		when(connection.getMetaData()).thenReturn(metaData);
		DataSource dataSource = mock(DataSource.class);
		when(dataSource.getConnection()).thenReturn(connection);
		return dataSource;
	}

	private static Object check(Object bean) {
		return new SqlServerVersionCheck().postProcessAfterInitialization(bean, "dataSource");
	}

	@Test
	@DisplayName("SQL Server 2016 is refused, with a message that names the minimum, the version and what to do")
	void sqlServer2016() throws Exception {
		DataSource server = server("Microsoft SQL Server", 13, "13.00.5026");
		IllegalStateException refused = assertThrows(IllegalStateException.class, () -> check(server));
		assertTrue(refused.getMessage().contains("SQL Server 2017"), refused.getMessage());
		assertTrue(refused.getMessage().contains("13.00.5026"), refused.getMessage());
		assertTrue(refused.getMessage().contains("Upgrade SQL Server"), refused.getMessage());
	}

	@Test
	@DisplayName("SQL Server 2014 is refused too")
	void sqlServer2014() throws Exception {
		DataSource server = server("Microsoft SQL Server", 12, "12.00.6024");
		assertThrows(IllegalStateException.class, () -> check(server));
	}

	@Test
	@DisplayName("SQL Server 2017 and 2019 start")
	void minimumAndAbove() throws Exception {
		DataSource sql2017 = server("Microsoft SQL Server", 14, "14.00.1000");
		DataSource sql2019 = server("Microsoft SQL Server", 15, "15.00.2000");
		assertSame(sql2017, check(sql2017));
		assertSame(sql2019, check(sql2019));
	}

	@Test
	@DisplayName("Another database is not checked (H2 in a test)")
	void anotherDatabase() throws Exception {
		DataSource h2 = server("H2", 1, "1.4.200");
		assertSame(h2, check(h2));
	}

	@Test
	@DisplayName("A server that cannot be reached is not refused here: the start fails with its usual message")
	void unreachable() throws Exception {
		DataSource down = mock(DataSource.class);
		when(down.getConnection()).thenThrow(new SQLException("Connection refused"));
		assertSame(down, check(down));
	}

	@Test
	@DisplayName("Any other bean goes through untouched")
	void otherBean() {
		Object other = new Object();
		assertSame(other, check(other));
	}
}
