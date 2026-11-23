package com.digithink.zsretail.core.eventlog;

/**
 * 3.4.0, event log: how much an event matters, a number (event_log.importance). Three names to start, ten apart, so a
 * fourth fits between two of them without touching the table or the rows. Each importance has its own numbering
 * (event_log_sequence): deleting old events of one importance leaves no hole in another's numbers.
 */
public final class EventImportance {

	/** A gesture of the normal flow: a line added, a ticket validated, a session opened. */
	public static final int STEP = 10;
	/** A gesture worth a look: a line deleted, a discount, a return, a setting changed. */
	public static final int NOTABLE = 20;
	/** A gesture to check: a refusal, a reprint (the drawer opens), a difference at closing, rights changed. */
	public static final int ALERT = 30;

	private EventImportance() {
	}

	/** The name of an importance (STEP, NOTABLE, ALERT), or its number as text for one without a name yet. */
	public static String nameOf(int importance) {
		switch (importance) {
			case STEP:
				return "STEP";
			case NOTABLE:
				return "NOTABLE";
			case ALERT:
				return "ALERT";
			default:
				return String.valueOf(importance);
		}
	}
}
