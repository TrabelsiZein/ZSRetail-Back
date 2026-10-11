package com.digithink.zsretail.erp.spi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The summary of a run, in the order it is written to the communications log: the kind of run, the page read, dry run,
 * the rows left out by reason, then the counts. Used by the catalogue engine and by a connector's own runs (the
 * invoices of navpospages), so every run writes the same format. Code cleanup step 3: was NavPosPagesSync.Summary.
 */
public final class RunSummary {
	public final Map<String, Object> values = new LinkedHashMap<>();
	public final Map<String, Integer> leftOut = new TreeMap<>();

	public RunSummary(String kind, String page, boolean dryRun) {
		values.put("run", kind);
		values.put("page", page);
		values.put("dryRun", dryRun);
		values.put("leftOut", leftOut);
	}

	/** The values, in order (the left out rows by reason among them). */
	public Map<String, Object> values() {
		return values;
	}

	public void read(ErpRead<?> result) {
		values.put("read", result.getRead());
		result.getLeftOut().forEach((reason, count) -> leftOut.merge(reason, count, Integer::sum));
		if (!result.getNotes().isEmpty()) {
			values.put("notes", result.getNotes());
		}
	}

	/** Stock points: one more point read, its counts added to those of the points before it. */
	public void addRead(ErpRead<?> result) {
		add("read", result.getRead());
		result.getLeftOut().forEach((reason, count) -> leftOut.merge(reason, count, Integer::sum));
		if (!result.getNotes().isEmpty()) {
			@SuppressWarnings("unchecked")
			Map<String, Integer> notes = (Map<String, Integer>) values.computeIfAbsent("notes", key -> new LinkedHashMap<String, Integer>());
			result.getNotes().forEach((reason, count) -> notes.merge(reason, count, Integer::sum));
		}
	}

	public void leftOut(String reason) {
		leftOut.merge(reason, 1, Integer::sum);
	}

	public void add(String key, int count) {
		Object current = values.get(key);
		values.put(key, (current instanceof Number ? ((Number) current).intValue() : 0) + count);
	}

	public void put(String key, Object value) {
		values.put(key, value);
	}

	public RunSummary waiting(String reason) {
		values.put("waiting", reason);
		values.put("handed", 0);
		return this;
	}

	public void guard(String warning) {
		values.put("guard", warning);
	}

	/** The first cap changes; counts of new, handed and held back. */
	public <T> List<T> handOver(List<T> changes, int fresh, int cap) {
		values.put("new", fresh);
		if (!values.containsKey("changed")) {
			values.put("changed", changes.size() - fresh);
		}
		List<T> handed = changes.size() > cap ? new ArrayList<>(changes.subList(0, cap)) : changes;
		values.put("handed", (Boolean) values.get("dryRun") ? 0 : handed.size());
		values.put("toHand", changes.size());
		values.put("heldBackByCap", changes.size() - handed.size());
		return handed;
	}
}
