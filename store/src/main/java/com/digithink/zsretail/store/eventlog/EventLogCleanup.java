package com.digithink.zsretail.store.eventlog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.eventlog.EventImportance;
import com.digithink.zsretail.core.eventlog.service.EventLog;
import com.digithink.zsretail.core.eventlog.service.EventLogSettings;
import com.digithink.zsretail.core.eventlog.service.NewEvent;
import com.digithink.zsretail.core.model.UserAccount;

import lombok.Data;
import lombok.extern.log4j.Log4j2;

/**
 * 3.4.0, event log step 8: the cleanup of the Journal, by the button (EventLogPageAPI, delete:admin-event-log) or each
 * night at 03:30 when EVENTLOG_AUTO_CLEANUP is on. Per importance, what is older than its duration (EventLogSettings;
 * none: kept forever) is deleted.
 * <ul>
 * <li><b>The floor:</b> the last {@link #FLOOR_DAYS} days are never deleted, whatever the durations: the limit is never
 * later than today minus 30 days.</li>
 * <li><b>The numbers:</b> a numbered importance loses a run of its first numbers, 1 to N, N the number just before the
 * first event written at or after the limit (event_at: the numbers follow the server's clock); event_log_sequence.
 * cleaned_to is set to N before the rows go, so the page never shows them as missing. A row without a number (copied
 * from the old badge scans) goes by its own time (occurred_at).</li>
 * <li><b>Never deleted:</b> the cleanups and the changes of the settings (EventCode.NEVER_CLEANED).</li>
 * <li><b>Small groups:</b> {@link #GROUP} rows per delete, each its own short statement, a pause between them: the
 * till's inserts never wait long.</li>
 * <li><b>One event:</b> EVENTLOG_CLEANUP (Alert, kept): who (the user, or the night job), how many per importance, the
 * limit dates.</li>
 * </ul>
 * One cleanup at a time (the button and the night job alike).
 */
@Component
@Log4j2
public class EventLogCleanup {

	public static final int FLOOR_DAYS = 30;
	static final int GROUP = 500;
	static final long PAUSE_MS = 20;

	private static final List<Integer> IMPORTANCES = Arrays.asList(EventImportance.ALERT, EventImportance.NOTABLE,
			EventImportance.STEP);
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private final NamedParameterJdbcTemplate jdbc;
	private final EventLog eventLog;
	private final EventLogSettings settings;
	private final Supplier<LocalDate> today;
	private final long pauseMs;
	private final AtomicBoolean running = new AtomicBoolean();
	/** Its own thread: never the scheduler's (shared with the ERP sync), never a request's. */
	private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "event-log-cleanup");
		thread.setDaemon(true);
		return thread;
	});
	private volatile Result last;

	@Autowired
	public EventLogCleanup(JdbcTemplate jdbc, EventLog eventLog, EventLogSettings settings) {
		this(jdbc, eventLog, settings, LocalDate::now, PAUSE_MS);
	}

	/** Tests: their day, no pause. */
	EventLogCleanup(JdbcTemplate jdbc, EventLog eventLog, EventLogSettings settings, Supplier<LocalDate> today,
			long pauseMs) {
		this.jdbc = new NamedParameterJdbcTemplate(jdbc);
		this.eventLog = eventLog;
		this.settings = settings;
		this.today = today;
		this.pauseMs = pauseMs;
	}

	/** One importance: its duration, its limit, how many rows go (a preview) or went (a result). */
	@Data
	public static class Part {
		private int importance;
		/** The duration in months; null: kept forever (nothing deleted). */
		private Integer months;
		/** The events before this day go; null: nothing (kept forever). */
		private LocalDate limit;
		/** The limit was moved to today minus 30 days (a duration shorter than the floor). */
		private boolean floored;
		private long rows;
		/** The numbers 1 to this one go (0: none). */
		private long cleanedTo;
	}

	@Data
	public static class Result {
		private List<Part> parts = new ArrayList<>();
		private long total;
		private LocalDateTime doneAt;
		private String by;
	}

	public boolean isRunning() {
		return running.get();
	}

	public Result last() {
		return last;
	}

	/** What a cleanup now would delete, per importance (read only). */
	public List<Part> preview() {
		List<Part> parts = new ArrayList<>();
		for (int importance : IMPORTANCES) {
			parts.add(part(importance));
		}
		return parts;
	}

	private Part part(int importance) {
		Part p = new Part();
		p.importance = importance;
		p.months = settings.keepMonths(importance);
		if (p.months == null) {
			return p;
		}
		LocalDate byDuration = today.get().minusMonths(p.months);
		LocalDate floor = today.get().minusDays(FLOOR_DAYS);
		p.floored = byDuration.isAfter(floor);
		p.limit = p.floored ? floor : byDuration;
		MapSqlParameterSource params = params(importance, p.limit);
		Long firstYoung = jdbc.queryForObject("select min(sequence_no) from event_log where importance = :importance"
				+ " and sequence_no is not null and event_at >= :limit", params, Long.class);
		Long lastNumber = jdbc.queryForObject("select max(sequence_no) from event_log where importance = :importance"
				+ " and sequence_no is not null", params, Long.class);
		long n = firstYoung != null ? firstYoung - 1 : lastNumber != null ? lastNumber : 0;
		p.cleanedTo = Math.max(n, 0);
		params.addValue("cleanedTo", p.cleanedTo);
		Long rows = jdbc.queryForObject("select count(*) from event_log where importance = :importance and code not in"
				+ " (:kept) and ((sequence_no is not null and sequence_no <= :cleanedTo) or (sequence_no is null and"
				+ " occurred_at < :limit))", params, Long.class);
		p.rows = rows == null ? 0 : rows;
		return p;
	}

	private static MapSqlParameterSource params(int importance, LocalDate limit) {
		return new MapSqlParameterSource().addValue("importance", importance).addValue("limit", limit.atStartOfDay())
				.addValue("kept", EventCode.NEVER_CLEANED.stream().map(Enum::name).collect(Collectors.toList()));
	}

	/**
	 * Deletes what the durations say (the floor kept) and writes EVENTLOG_CLEANUP; null when a cleanup is already running.
	 * {@code by}: the user of the button; null: the night job.
	 */
	public Result run(UserAccount by) {
		if (!running.compareAndSet(false, true)) {
			return null;
		}
		try {
			Result result = new Result();
			result.by = by != null ? by.getUsername() : null;
			for (int importance : IMPORTANCES) {
				Part p = part(importance);
				if (p.limit != null) {
					p.rows = delete(importance, p);
				}
				result.parts.add(p);
				result.total += p.rows;
			}
			result.doneAt = LocalDateTime.now();
			writeEvent(result, by);
			last = result;
			log.info("Event log cleanup by {}: {} rows deleted", by != null ? by.getUsername() : "the night job", result.total);
			return result;
		} finally {
			running.set(false);
		}
	}

	/** Sets cleaned_to first (a cleanup cut short never shows missing numbers), then deletes in small groups. */
	private long delete(int importance, Part p) {
		MapSqlParameterSource params = params(importance, p.limit).addValue("cleanedTo", p.cleanedTo)
				.addValue("size", GROUP);
		if (p.cleanedTo > 0) {
			jdbc.update("update event_log_sequence set cleaned_to = :cleanedTo where importance = :importance"
					+ " and cleaned_to < :cleanedTo", params);
		}
		long deleted = 0;
		while (true) {
			List<Long> ids = jdbc.queryForList("select id from event_log where importance = :importance and code not in"
					+ " (:kept) and ((sequence_no is not null and sequence_no <= :cleanedTo) or (sequence_no is null and"
					+ " occurred_at < :limit)) order by id offset 0 rows fetch next :size rows only", params, Long.class);
			if (ids.isEmpty()) {
				return deleted;
			}
			deleted += jdbc.update("delete from event_log where id in (:ids)", new MapSqlParameterSource("ids", ids));
			pause();
		}
	}

	private void pause() {
		if (pauseMs <= 0) {
			return;
		}
		try {
			Thread.sleep(pauseMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void writeEvent(Result result, UserAccount by) {
		Map<Integer, String> names = new LinkedHashMap<>();
		names.put(EventImportance.ALERT, "Alertes");
		names.put(EventImportance.NOTABLE, "Notables");
		names.put(EventImportance.STEP, "Étapes");
		List<String> lines = new ArrayList<>();
		for (Part p : result.parts) {
			lines.add(p.limit == null ? names.get(p.importance) + " : conservées sans limite"
					: p.rows + " " + names.get(p.importance) + " d'avant le " + p.limit.format(DAY)
							+ (p.floored ? " (les 30 derniers jours gardés)" : ""));
		}
		String who = by != null ? com.digithink.zsretail.core.eventlog.service.AccessEvents.who(by) : "la tâche de nuit";
		NewEvent event = NewEvent.of(EventCode.EVENTLOG_CLEANUP).subject("EVENT_LOG", "Journal des événements")
				.amount((double) result.total)
				.details("Journal nettoyé par " + who + " : " + String.join(" ; ", lines) + ".");
		if (by != null) {
			event.user(by);
		}
		eventLog.writeNow(event);
	}

	/** The button: the cleanup started in its own thread; false when one is already running. */
	public boolean start(UserAccount by) {
		if (running.get()) {
			return false;
		}
		worker.submit(() -> {
			try {
				run(by);
			} catch (RuntimeException e) {
				log.warn("Event log cleanup failed ({})", e.toString());
			}
		});
		return true;
	}

	/** Each night at 03:30: only with the module on and EVENTLOG_AUTO_CLEANUP on (read again first); its own thread. */
	@Scheduled(cron = "0 30 3 * * *")
	public void nightly() {
		if (!eventLog.isOn()) {
			return;
		}
		settings.refresh();
		if (settings.autoCleanup()) {
			start(null);
		}
	}

	@javax.annotation.PreDestroy
	void stop() {
		worker.shutdownNow();
	}
}
