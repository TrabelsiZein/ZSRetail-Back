package com.digithink.zsretail.erp.navpospages.sync;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ERP catalogue, step 6: one run of the changes engine: the rows handed to the import, the summary written to the
 * communications log (counts only, never the list) and the address of the page read.
 */
public class NavPosRun<T> {

	private final List<T> handed;
	private final Map<String, Object> summary;
	private final String url;

	public NavPosRun(List<T> handed, Map<String, Object> summary, String url) {
		this.handed = Collections.unmodifiableList(handed);
		this.summary = Collections.unmodifiableMap(new LinkedHashMap<>(summary));
		this.url = url;
	}

	/** The rows handed to the import (empty in a dry run and while waiting). */
	public List<T> getHanded() {
		return handed;
	}

	public Map<String, Object> getSummary() {
		return summary;
	}

	public String getUrl() {
		return url;
	}

	/** A number of the summary, 0 when absent. */
	public int count(String key) {
		Object value = summary.get(key);
		return value instanceof Number ? ((Number) value).intValue() : 0;
	}

	@Override
	public String toString() {
		return summary.toString();
	}
}
