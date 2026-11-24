package com.digithink.zsretail.core.eventlog.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.eventlog.EventImportance;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;

import lombok.extern.log4j.Log4j2;

/**
 * 3.4.0, event log step 8: the event log's settings (General setup, tab "Journal des événements"), kept in memory so
 * that a gesture never reads the database to know whether its event is stored (EventLog). Read at the start and again
 * after each save of one of them (EventLogSettingsChange), and by the night cleanup before it runs. A missing or
 * unreadable row is its default.
 * <ul>
 * <li>EVENTLOG_STORE_FROM: STEP (every event, the default) or NOTABLE (the Steps are not written, nor sent by the till;
 * Notable and Alert always are). Nothing below the level takes a number: no hole.</li>
 * <li>EVENTLOG_KEEP_STEP_MONTHS, _NOTABLE_, _ALERT_: how long each importance is kept, in months (3, 24, 24); empty:
 * kept forever.</li>
 * <li>EVENTLOG_AUTO_CLEANUP: the night cleanup, off by default.</li>
 * </ul>
 */
@Component
@Log4j2
public class EventLogSettings {

	public static final String STORE_FROM = "EVENTLOG_STORE_FROM";
	public static final String KEEP_STEP_MONTHS = "EVENTLOG_KEEP_STEP_MONTHS";
	public static final String KEEP_NOTABLE_MONTHS = "EVENTLOG_KEEP_NOTABLE_MONTHS";
	public static final String KEEP_ALERT_MONTHS = "EVENTLOG_KEEP_ALERT_MONTHS";
	public static final String AUTO_CLEANUP = "EVENTLOG_AUTO_CLEANUP";

	/** The five codes, in the tab's order. */
	public static final List<String> CODES = Collections
			.unmodifiableList(Arrays.asList(STORE_FROM, KEEP_STEP_MONTHS, KEEP_NOTABLE_MONTHS, KEEP_ALERT_MONTHS, AUTO_CLEANUP));

	public static final String LEVEL_STEP = "STEP";
	public static final String LEVEL_NOTABLE = "NOTABLE";

	/** The defaults (a missing or unreadable row). */
	public static final Map<String, String> DEFAULTS;
	static {
		Map<String, String> d = new LinkedHashMap<>();
		d.put(STORE_FROM, LEVEL_STEP);
		d.put(KEEP_STEP_MONTHS, "3");
		d.put(KEEP_NOTABLE_MONTHS, "24");
		d.put(KEEP_ALERT_MONTHS, "24");
		d.put(AUTO_CLEANUP, "false");
		DEFAULTS = Collections.unmodifiableMap(d);
	}

	/** A duration is 1 to 600 months (50 years), or empty. */
	public static final int MAX_MONTHS = 600;

	private final GeneralSetupRepository setup;

	private volatile int minImportance = EventImportance.STEP;
	private volatile Map<Integer, Integer> keepMonths = defaultsKept();
	private volatile boolean autoCleanup;

	@Autowired
	public EventLogSettings(GeneralSetupRepository setup) {
		this.setup = setup;
	}

	/** Tests: fixed values, no database. */
	public EventLogSettings(int minImportance, Map<Integer, Integer> keepMonths, boolean autoCleanup) {
		this.setup = null;
		this.minImportance = minImportance;
		this.keepMonths = keepMonths;
		this.autoCleanup = autoCleanup;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void started() {
		refresh();
	}

	/** Reads the five rows again (never at a gesture: the start, after a save, before the night cleanup). */
	public void refresh() {
		if (setup == null) {
			return;
		}
		try {
			minImportance = LEVEL_NOTABLE.equalsIgnoreCase(value(STORE_FROM)) ? EventImportance.NOTABLE : EventImportance.STEP;
			Map<Integer, Integer> kept = new HashMap<>();
			kept.put(EventImportance.STEP, months(value(KEEP_STEP_MONTHS)));
			kept.put(EventImportance.NOTABLE, months(value(KEEP_NOTABLE_MONTHS)));
			kept.put(EventImportance.ALERT, months(value(KEEP_ALERT_MONTHS)));
			keepMonths = kept;
			autoCleanup = "true".equalsIgnoreCase(value(AUTO_CLEANUP));
		} catch (RuntimeException e) {
			log.warn("Event log settings not read ({}): the values of before are kept", e.toString());
		}
	}

	private String value(String code) {
		Optional<GeneralSetup> row = setup.findByCode(code);
		String v = row.map(GeneralSetup::getValeur).orElse(DEFAULTS.get(code));
		return v == null ? "" : v.trim();
	}

	/** The months of a row: null for empty (kept forever); an unreadable value: null too (never deletes by mistake). */
	static Integer months(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		try {
			int n = Integer.parseInt(value.trim());
			return n >= 1 && n <= MAX_MONTHS ? n : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Map<Integer, Integer> defaultsKept() {
		Map<Integer, Integer> kept = new HashMap<>();
		kept.put(EventImportance.STEP, 3);
		kept.put(EventImportance.NOTABLE, 24);
		kept.put(EventImportance.ALERT, 24);
		return kept;
	}

	/** The lowest importance stored: STEP (10) or NOTABLE (20). */
	public int minImportance() {
		return minImportance;
	}

	/** The event of this code is written (its importance at or above the level). */
	public boolean stores(EventCode code) {
		return code != null && code.getImportance() >= minImportance;
	}

	/** How long an importance is kept, in months; null: forever. */
	public Integer keepMonths(int importance) {
		return keepMonths.get(importance);
	}

	public boolean autoCleanup() {
		return autoCleanup;
	}
}
