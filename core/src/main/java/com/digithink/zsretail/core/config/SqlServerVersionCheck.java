package com.digithink.zsretail.core.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import lombok.extern.log4j.Log4j2;

/**
 * Boot 4 migration, decision 2: 4.0.0 needs SQL Server 2017 or later (Flyway's open-source edition asks for the same, step
 * 1.8), and a start on an older one is refused with a message that says so. The check runs as soon as the DataSource exists,
 * so before Hibernate: a refused start has changed nothing in the database, where ddl-auto would otherwise have run first
 * (docs/deploy-note.md).
 * <p>
 * Another database (H2 in a test) is not checked. A server that cannot be reached is not this check's to report: the start
 * fails a moment later with its usual message.
 */
@Component
@Log4j2
public class SqlServerVersionCheck implements BeanPostProcessor {

	/** SQL Server 2017; 2016 is 13, 2019 is 15. */
	public static final int MINIMUM_MAJOR_VERSION = 14;

	private static final String SQL_SERVER = "Microsoft SQL Server";

	private final int minimumMajorVersion;

	public SqlServerVersionCheck() {
		this(MINIMUM_MAJOR_VERSION);
	}

	/** Another minimum: for the test that proves a real start is refused before Hibernate. */
	public SqlServerVersionCheck(int minimumMajorVersion) {
		this.minimumMajorVersion = minimumMajorVersion;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (bean instanceof DataSource) {
			check((DataSource) bean);
		}
		return bean;
	}

	void check(DataSource dataSource) {
		try (Connection connection = dataSource.getConnection()) {
			DatabaseMetaData metaData = connection.getMetaData();
			if (SQL_SERVER.equals(metaData.getDatabaseProductName()) && metaData.getDatabaseMajorVersion() < minimumMajorVersion) {
				String refusal = "This version of ZS Retail needs SQL Server 2017 or later; this server is version "
						+ metaData.getDatabaseProductVersion() + ". Upgrade SQL Server, then start again. The start stopped"
						+ " here: nothing was changed in the database.";
				log.error(refusal);
				throw new IllegalStateException(refusal);
			}
		} catch (SQLException e) {
			log.warn("SQL Server version not checked: {}", e.getMessage());
		}
	}
}
