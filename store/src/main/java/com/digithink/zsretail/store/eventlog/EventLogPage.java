package com.digithink.zsretail.store.eventlog;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 3.4.0, event log step 6: one page of the Journal (GET /event-log): its lines (newest first), the number of lines of
 * every page ({@link #total}: a run of refused logins counts once) and the counters of the period and the other filters
 * ({@link Counts}: events, a run counting each of its events; the importance filter left out, so that a click on a
 * counter shows what it says).
 */
@Data
public class EventLogPage {

	private List<EventLogRow> rows = new ArrayList<>();
	private int page;
	private int size;
	private long total;
	private Counts counts = new Counts();

	@Data
	public static class Counts {
		private long alerts;
		private long notables;
		private long steps;
	}
}
