package com.digithink.zsretail.core.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import com.digithink.zsretail.core.config.ZsRetailApplication;

/**
 * Boot 4 migration, step 1.8: what each type's schema startup test proves, real starts on the throwaway database. The schema
 * is Flyway's: a new database gets the baseline and the catch-up, a database at 3.1.2 is taken over, and both end with the
 * schema of the type's record ({@link SchemaCatalog}). Any other database is refused before anything is written.
 */
public final class FlywayStartups {

	public static final String CATCH_UP_1 = "2 4.0.0.1 SQL indexes of the old scripts 1";

	/** The indexes catch-up 1 brings to a database a version created from empty (V4.0.0.1). */
	public static final List<String> INDEXES_OF_THE_OLD_SCRIPTS = Arrays.asList("ix_stock_movement_item",
			"ix_stock_movement_created", "ix_sales_header_session_status", "ix_ho_store_order_line_order", "ix_pos_machine_reader");

	private final Class<? extends ZsRetailApplication> application;
	private final String type;

	public FlywayStartups(Class<? extends ZsRetailApplication> application, String type) {
		this.application = application;
		this.type = type;
	}

	/** An empty database: the baseline, then catch-up 1, then Hibernate's check; the record's schema. */
	public void newDatabase() {
		try (MigTestApplication started = MigTestApplication.start(application, type)) {
			assertEquals(Arrays.asList("1 4.0.0 SQL baseline 1", CATCH_UP_1), MigTestApplication.history(started.database()));
			SchemaCatalog.assertRecord(SchemaCatalog.of(started.database()), type, type);
			assertEquals(Arrays.asList(appVersion(started)), started.database().lines("SELECT version FROM APP_VERSION"));
		}
	}

	/**
	 * A database at 3.1.2: a baseline marker instead of the baseline, catch-up 1 run, the item kept, APP_VERSION set to this
	 * WAR's, and the same schema as a new database. A restart runs nothing more.
	 */
	public void upgradeFrom312() {
		try (MigTestApplication started = MigTestApplication.start(application, type, Upgrade312.at312(type))) {
			List<String> history = Arrays.asList("1 4.0.0 BASELINE a database at 3.1.2 1", CATCH_UP_1);
			assertEquals(history, MigTestApplication.history(started.database()));
			assertEquals(Arrays.asList(Upgrade312.ITEM), started.database().lines(Upgrade312.ITEM_QUERY));
			assertEquals(Arrays.asList(appVersion(started)), started.database().lines("SELECT version FROM APP_VERSION"));
			SchemaCatalog.assertRecord(SchemaCatalog.of(started.database()), type, type);
			started.restart();
			assertEquals(history, MigTestApplication.history(started.database()));
		}
	}

	/** Catch-up 1 on a database at 3.1.2: the indexes its table has get created, and only those (a store has no ho_* table). */
	public List<String> indexesCreatedFrom312() {
		try (MigTestApplication started = MigTestApplication.start(application, type, Upgrade312.at312(type))) {
			return started.database().lines("SELECT name FROM sys.indexes WHERE name IN ('"
					+ String.join("', '", INDEXES_OF_THE_OLD_SCRIPTS) + "') ORDER BY name");
		}
	}

	/** A database refused: the message names why, nothing was written (no history, the same schema), no Hibernate. */
	public void refused(Consumer<MigTestDatabase> prepare, String why) {
		MigTestApplication.Refusal refusal = MigTestApplication.startRefused(application, type, prepare);
		assertTrue(refusal.messages().contains(why), "the refusal says why (" + why + "): " + refusal.messages());
		assertTrue(refusal.messages().contains("Nothing was changed."), refusal.messages());
		assertEquals(Arrays.asList(), refusal.historyLeft, "Flyway wrote no history");
		assertEquals("", SchemaCatalog.difference(refusal.schemaBefore, refusal.schemaLeft), "the schema is as it was");
	}

	/**
	 * A database a newer version ran on (a script this WAR does not have, applied): the start stops at Flyway's check, as
	 * AppVersionGuard stopped a newer database (spring.flyway.ignore-migration-patterns emptied). Nothing written.
	 */
	public String newerDatabase() {
		try (MigTestApplication started = MigTestApplication.start(application, type)) {
			started.database().run("INSERT INTO flyway_schema_history (installed_rank, version, description, type, script,"
					+ " checksum, installed_by, execution_time, success) SELECT MAX(installed_rank) + 1, '9.9.9', 'a newer"
					+ " version', 'SQL', 'V9.9.9__a_newer_version.sql', 0, 'migtest', 0, 1 FROM flyway_schema_history");
			List<String> schema = SchemaCatalog.of(started.database());
			List<String> history = MigTestApplication.history(started.database());
			RuntimeException refused = assertThrows(RuntimeException.class, started::restart);
			assertEquals(history, MigTestApplication.history(started.database()));
			assertEquals("", SchemaCatalog.difference(schema, SchemaCatalog.of(started.database())));
			StringBuilder messages = new StringBuilder();
			for (Throwable cause = refused; cause != null; cause = cause.getCause()) {
				messages.append(cause.getMessage()).append('\n');
			}
			return messages.toString();
		}
	}

	private static String appVersion(MigTestApplication started) {
		return started.context().getEnvironment().getProperty("app.version");
	}
}
