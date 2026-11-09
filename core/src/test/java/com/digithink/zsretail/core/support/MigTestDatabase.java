package com.digithink.zsretail.core.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Assumptions;

/**
 * Boot 4 migration, step 1.2 (decision 6): the throwaway database of the startup tests, on the local SQL Server, described
 * by migtest.properties. Only {@value #NAME} is ever created and dropped, and no statement acts on the whole instance. A
 * settings file that names another database or another machine is refused: the test fails, it never connects there.
 * <p>
 * An unreachable server skips the test with a message, so a machine without SQL Server still builds. A migration step with
 * skipped startup tests is not green: {@code -Dzsretail.migtest.required=true} turns the skip into a failure.
 * <p>
 * Two builds at once would share the database: the suite runs without {@code -T}.
 */
public final class MigTestDatabase {

	public static final String NAME = "zsretail_migtest";

	/** System property: true, an unreachable server fails instead of skipping. */
	public static final String REQUIRED_KEY = "zsretail.migtest.required";

	private static final List<String> THIS_MACHINE = Arrays.asList("localhost", "127.0.0.1");
	private static final String PASSWORD_KEY = "spring.datasource.password";

	private final String server;
	private final String port;
	private final String options;
	private final String username;
	private final String passwordFile;
	private final Path configs;

	/** The settings checked: this class connects nowhere else. */
	MigTestDatabase(Properties settings, Path configs) {
		String database = settings.getProperty("migtest.database");
		if (!NAME.equals(database)) {
			throw new IllegalStateException("The startup tests only touch the database " + NAME + ", not " + database);
		}
		this.server = settings.getProperty("migtest.server");
		if (!THIS_MACHINE.contains(server)) {
			throw new IllegalStateException("The startup tests only touch the SQL Server of this machine, not " + server);
		}
		this.port = settings.getProperty("migtest.port");
		this.options = settings.getProperty("migtest.options");
		this.username = settings.getProperty("migtest.username");
		this.passwordFile = settings.getProperty("migtest.password-file");
		this.configs = configs;
	}

	/** The database of migtest.properties. */
	public static MigTestDatabase fromSettings() {
		Properties settings = new Properties();
		try (InputStream in = MigTestDatabase.class.getResourceAsStream("/migtest.properties")) {
			settings.load(in);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return new MigTestDatabase(settings, Repo.ROOT.resolve("configs"));
	}

	/** An empty database, the leftover of a killed run dropped first. Skips or fails when the server is unreachable. */
	public void recreate() {
		String why = unreachable();
		if (why != null) {
			String message = "Startup tests skipped: database " + NAME + " on " + server + ":" + port + " is unreachable (" + why
					+ "). A Boot 4 migration step with these tests skipped is not green (docs/migration/boot4-migration.md).";
			if (Boolean.getBoolean(REQUIRED_KEY)) {
				throw new AssertionError(message);
			}
			Assumptions.assumeTrue(false, message);
		}
		drop();
		execute("CREATE DATABASE [" + NAME + "]");
	}

	/** Gone, whatever still holds a connection to it. Only this database: the session goes back to master first. */
	public void drop() {
		execute("IF DB_ID('" + NAME + "') IS NOT NULL BEGIN ALTER DATABASE [" + NAME
				+ "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [" + NAME + "]; END");
	}

	/** The reason the server cannot be reached, null when it can. Never carries the password. */
	String unreachable() {
		String password = password();
		if (password == null) {
			return "no " + PASSWORD_KEY + " in configs/" + passwordFile;
		}
		try (Connection connection = DriverManager.getConnection(url("master"), username, password)) {
			return null;
		} catch (SQLException e) {
			return e.getMessage();
		}
	}

	/** The user tables of the throwaway database: a start refused before Hibernate leaves none. */
	public int tableCount() {
		try (Connection connection = DriverManager.getConnection(url(), username, password());
				Statement statement = connection.createStatement();
				ResultSet tables = statement.executeQuery("SELECT COUNT(*) FROM sys.tables")) {
			tables.next();
			return tables.getInt(1);
		} catch (SQLException e) {
			throw new IllegalStateException("On database " + NAME + ": " + e.getMessage(), e);
		}
	}

	private void execute(String sql) {
		try (Connection connection = DriverManager.getConnection(url("master"), username, password());
				Statement statement = connection.createStatement()) {
			statement.execute(sql);
		} catch (SQLException e) {
			throw new IllegalStateException("On database " + NAME + ": " + e.getMessage(), e);
		}
	}

	private String url(String database) {
		return "jdbc:sqlserver://" + server + ":" + port + ";databaseName=" + database + ";" + options;
	}

	/** The JDBC address of the throwaway database. */
	public String url() {
		return url(NAME);
	}

	public String username() {
		return username;
	}

	/** Read from the machine file at each use, never kept or copied. Null when there is none. */
	public String password() {
		Path file = configs.resolve(passwordFile);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		Properties machine = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			machine.load(in);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return machine.getProperty(PASSWORD_KEY);
	}
}
