package com.digithink.zsretail.core.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Boot 4 migration, step 1.8: the databases a 4.0.0 WAR meets, built on the throwaway database before its start.
 * <p>
 * A database at 3.1.2 is the type's baseline run as plain SQL, without Flyway: that script is the schema release/3.1.2's
 * code makes, compared once object by object with a database a real start of release/3.1.2 made (the only difference, the
 * indexes of its startup T-SQL, is in the baseline too: docs/migration/diffs/step-1.8-schema.txt). Then APP_VERSION 3.1.2
 * and one item, the row the upgrade must keep.
 */
public final class Upgrade312 {

	/** The item a 3.1.2 database holds, as {@link #ITEM_QUERY} reads it back. */
	public static final String ITEM = "MIG-1|Migration item 1|1.5|2.125|3";

	public static final String ITEM_QUERY = "SELECT CONCAT(item_code, '|', name, '|', unit_price, '|', stock_quantity, '|',"
			+ " min_stock_level) FROM item WHERE item_code = 'MIG-1'";

	private Upgrade312() {
	}

	/** A database at 3.1.2 of a type (store, headoffice). */
	public static Consumer<MigTestDatabase> at312(String type) {
		return stamped(type, "3.1.2");
	}

	/** The schema of 3.1.2 and its item, with APP_VERSION at another version (an older one, or empty when null). */
	public static Consumer<MigTestDatabase> stamped(String type, String version) {
		return database -> {
			database.run(baseline(type));
			if (version != null) {
				database.run("INSERT INTO APP_VERSION (version) VALUES ('" + version + "')");
			}
			database.run("INSERT INTO item (item_code, name, show_in_pos, unit_price, stock_quantity, min_stock_level, created_at,"
					+ " created_by) VALUES ('MIG-1', 'Migration item 1', 1, 1.5, 2.125, 3, '2026-11-10T10:00:00', 'migtest')");
		};
	}

	/** The type's baseline, from the resources of core (the WARs carry the same file). */
	public static String baseline(String type) {
		String resource = "/db/migration/" + type + "/V4.0.0__baseline.sql";
		try (InputStream in = Upgrade312.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("No " + resource);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
