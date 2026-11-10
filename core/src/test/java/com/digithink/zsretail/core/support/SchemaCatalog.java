package com.digithink.zsretail.core.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Boot 4 migration, step 1.8: the schema of the throwaway database as lines, one per table, column, key, index, foreign key
 * and check of dbo, sorted (schema/catalog.sql, catalog views only). The names SQL Server makes up are left out, so two
 * databases built alike give the same lines; flyway_schema_history is left out too.
 * <p>
 * A record holds the lines of a new database of a type, {@code src/test/resources/schema/<type>.txt} in its project. A new
 * database and a database upgraded from 3.1.2 must both match it: the rule of step 1.8, a new database is the same as an
 * existing one. {@code -Dzsretail.schema.record=true} writes the record again, only for an intended change (a new V
 * script), and the diff goes in the commit.
 */
public final class SchemaCatalog {

	public static final String RECORD_KEY = "zsretail.schema.record";

	private SchemaCatalog() {
	}

	/** The lines of the throwaway database as it is now. */
	public static List<String> of(MigTestDatabase database) {
		return database.lines(query());
	}

	/** The lines match the record of a type, in the project that runs the test. */
	public static void assertRecord(List<String> lines, String project, String type) {
		Path record = Repo.ROOT.resolve(project).resolve("src/test/resources/schema").resolve(type + ".txt");
		if (Boolean.getBoolean(RECORD_KEY)) {
			write(record, lines);
		}
		assertEquals(read(record), String.join("\n", lines), "the schema of " + record + " (-D" + RECORD_KEY
				+ "=true writes it again, for an intended change only)");
	}

	/** The lines one has and the other has not, "- " then "+ ", for a message. */
	public static String difference(List<String> before, List<String> after) {
		List<String> lines = new ArrayList<>();
		before.stream().filter(line -> !after.contains(line)).forEach(line -> lines.add("- " + line));
		after.stream().filter(line -> !before.contains(line)).forEach(line -> lines.add("+ " + line));
		return String.join("\n", lines);
	}

	private static String query() {
		try (InputStream in = SchemaCatalog.class.getResourceAsStream("/schema/catalog.sql")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String read(Path record) {
		try {
			return Files.readAllLines(record, StandardCharsets.UTF_8).stream().collect(Collectors.joining("\n"));
		} catch (IOException e) {
			throw new UncheckedIOException("No record " + record + " (-D" + RECORD_KEY + "=true writes it)", e);
		}
	}

	private static void write(Path record, List<String> lines) {
		try {
			Files.createDirectories(record.getParent());
			Files.write(record, lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
