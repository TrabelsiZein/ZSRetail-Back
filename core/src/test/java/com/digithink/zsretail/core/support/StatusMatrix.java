package com.digithink.zsretail.core.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boot 4 migration, step 1.2: URLs by callers, the status each caller gets, on a really started application. Every row is
 * called by every caller and every difference is reported at once: the table of a migration step that breaks security
 * shows what moved, not only the first cell.
 */
public final class StatusMatrix {

	private final MigTestApplication application;
	private final Map<String, String[]> callers = new LinkedHashMap<>();
	private final List<String> differences = new ArrayList<>();

	public StatusMatrix(MigTestApplication application) {
		this.application = application;
	}

	/** A column: its name, and the headers (name, value...) each of its calls carries. */
	public StatusMatrix caller(String name, String... headers) {
		callers.put(name, headers);
		return this;
	}

	/** A row: one status per caller, in the order of the columns. */
	public StatusMatrix row(String method, String path, int... expected) {
		return row(method, path, null, expected);
	}

	/** A row with a request body. */
	public StatusMatrix row(String method, String path, String body, int... expected) {
		if (expected.length != callers.size()) {
			throw new IllegalArgumentException(method + " " + path + ": " + expected.length + " statuses for " + callers.size()
					+ " callers");
		}
		int column = 0;
		for (Map.Entry<String, String[]> caller : callers.entrySet()) {
			int status = application.call(method, path, body, caller.getValue()).status;
			if (status != expected[column]) {
				differences.add(method + " " + path + " as " + caller.getKey() + ": expected " + expected[column] + ", got " + status);
			}
			column++;
		}
		return this;
	}

	/** Fails with every difference found. */
	public void verify() {
		if (!differences.isEmpty()) {
			throw new AssertionError(differences.size() + " differences:\n" + String.join("\n", differences));
		}
	}
}
