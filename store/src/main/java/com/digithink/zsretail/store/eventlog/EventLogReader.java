package com.digithink.zsretail.store.eventlog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.eventlog.EventImportance;

import lombok.Data;

/**
 * 3.4.0, event log step 6: reads event_log for the Journal page, in SQL (the paging over the runs of refused logins is
 * not a JPA query). Read only; nothing it does is an event.
 * <ul>
 * <li>Order: the event's own time (occurred_at: the till's clock of the gesture, else the server's), newest first, then
 * the row's id (the arrival order: sequence_no restarts with each importance, it cannot order two importances).</li>
 * <li>The period is always given: ix_event_log_occurred (occurred_at, importance) bounds every query; the other filters
 * are read in that range (a session, a subject, a user have their own index besides).</li>
 * <li>A run of refused logins (group_key) is one line: its last event, with its count and its first and last times
 * (read for the runs of the page only). Asked by its key, its events come one per line.</li>
 * <li>The counters count events (a run counts each of its events) with every filter but the importance.</li>
 * </ul>
 * Plain SQL that SQL Server and the tests' H2 (MODE=MSSQLServer) both run: OFFSET / FETCH, LIKE ... ESCAPE.
 */
@Component
public class EventLogReader {

	public static final int PAGE_SIZE = 50;
	public static final int MAX_PAGE_SIZE = 200;

	/** The Excel file holds at most this many events (the first ones in the page's order); the file says so when cut. */
	public static final int EXCEL_LIMIT = 20_000;

	/** The missing ranges listed per importance (the count is always whole). */
	static final int MAX_GAPS = 20;

	private static final String COLUMNS = "e.id, e.importance, e.sequence_no, e.code, e.subject_type, e.subject_id,"
			+ " e.subject_label, e.occurred_at, e.event_at, e.front_at, e.pos_machine_id, e.pos_machine_name, e.session_id,"
			+ " e.session_number, e.cart_id, e.user_id, e.user_login, e.user_name, e.user_role, e.authorised_by_id,"
			+ " e.authorised_by_login, e.authorised_by_name, e.badge_owner_id, e.badge_owner_login, e.badge_owner_name,"
			+ " e.sent_by_id, e.sent_by_login, e.client_address, e.machine_sequence_no, e.install_id, e.doubt, e.old_value,"
			+ " e.new_value, e.amount, e.details, e.imported, e.group_key";

	private static final String ORDER = " order by e.occurred_at desc, e.id desc";

	private final NamedParameterJdbcTemplate jdbc;

	@Autowired
	public EventLogReader(JdbcTemplate jdbc) {
		this.jdbc = new NamedParameterJdbcTemplate(jdbc);
	}

	/** One page (from 0) of the lines, the number of lines and the counters. */
	public EventLogPage page(EventLogFilter filter, int page, int size) {
		int pageSize = size <= 0 ? PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
		int pageNo = Math.max(page, 0);
		MapSqlParameterSource params = new MapSqlParameterSource();
		String where = lines(filter, params);

		EventLogPage result = new EventLogPage();
		result.setPage(pageNo);
		result.setSize(pageSize);
		Long total = jdbc.queryForObject("select count(*) from event_log e where " + where, params, Long.class);
		result.setTotal(total == null ? 0 : total);
		if (result.getTotal() > (long) pageNo * pageSize) {
			params.addValue("offset", (long) pageNo * pageSize).addValue("size", pageSize);
			result.setRows(jdbc.query("select " + COLUMNS + " from event_log e where " + where + ORDER
					+ " offset :offset rows fetch next :size rows only", params, ROW));
			withRuns(result.getRows(), filter);
		}
		result.setCounts(counts(filter));
		return result;
	}

	/** The Excel file's events: every event of the filters (a run's events one by one), at most {@code limit}. */
	public List<EventLogRow> all(EventLogFilter filter, int limit) {
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("size", limit);
		String where = where("e", filter, params, true);
		return jdbc.query("select " + COLUMNS + " from event_log e where " + where + ORDER
				+ " offset 0 rows fetch next :size rows only", params, ROW);
	}

	/** How many events the filters give (the Excel file says it when it holds fewer). */
	public long countAll(EventLogFilter filter) {
		MapSqlParameterSource params = new MapSqlParameterSource();
		Long n = jdbc.queryForObject("select count(*) from event_log e where " + where("e", filter, params, true), params,
				Long.class);
		return n == null ? 0 : n;
	}

	/** The counters: events per importance, with every filter but the importance. */
	EventLogPage.Counts counts(EventLogFilter filter) {
		MapSqlParameterSource params = new MapSqlParameterSource();
		String where = where("e", filter.withImportances(java.util.Collections.emptyList()), params, true);
		EventLogPage.Counts counts = new EventLogPage.Counts();
		jdbc.query("select e.importance, count(*) as n from event_log e where " + where + " group by e.importance", params,
				rs -> {
					int importance = rs.getInt("importance");
					long n = rs.getLong("n");
					if (importance == EventImportance.ALERT) {
						counts.setAlerts(n);
					} else if (importance == EventImportance.NOTABLE) {
						counts.setNotables(n);
					} else if (importance == EventImportance.STEP) {
						counts.setSteps(n);
					}
				});
		return counts;
	}

	/**
	 * The lines' condition: the filters, and a run of refused logins kept as its last event (its other events left out)
	 * unless one run is asked for.
	 */
	private static String lines(EventLogFilter filter, MapSqlParameterSource params) {
		String where = where("e", filter, params, true);
		if (filter.getGroupKey() != null) {
			return where;
		}
		return where + " and (e.group_key is null or e.id in (select max(g.id) from event_log g where "
				+ where("g", filter, params, false) + " and g.group_key is not null group by g.group_key))";
	}

	/**
	 * The filters on the alias {@code a}. The parameters have the same names for both aliases (the same values);
	 * {@code add} false: already added by the outer query.
	 */
	static String where(String a, EventLogFilter f, MapSqlParameterSource p, boolean add) {
		StringBuilder w = new StringBuilder(a + ".occurred_at >= :start and " + a + ".occurred_at < :end");
		if (add) {
			p.addValue("start", f.start()).addValue("end", f.end());
		}
		if (!f.getImportances().isEmpty()) {
			w.append(" and ").append(a).append(".importance in (:importances)");
			put(p, add, "importances", f.getImportances());
		}
		equal(w, p, add, a + ".user_id", "userId", f.getUserId());
		equal(w, p, add, a + ".authorised_by_id", "authorisedById", f.getAuthorisedById());
		equal(w, p, add, a + ".badge_owner_id", "badgeOwnerId", f.getBadgeOwnerId());
		equal(w, p, add, a + ".pos_machine_id", "posMachineId", f.getPosMachineId());
		if (!f.getSessionIds().isEmpty()) {
			w.append(" and ").append(a).append(".session_id in (:sessionIds)");
			put(p, add, "sessionIds", f.getSessionIds());
		}
		if (!f.getNewValues().isEmpty()) {
			w.append(" and ").append(a).append(".new_value in (:newValues)");
			put(p, add, "newValues", f.getNewValues());
		}
		equal(w, p, add, a + ".session_number", "sessionNumber", f.getSessionNumber());
		equal(w, p, add, a + ".subject_type", "subjectType", f.getSubjectType());
		equal(w, p, add, a + ".subject_id", "subjectId", f.getSubjectId());
		equal(w, p, add, a + ".cart_id", "cartId", f.getCartId());
		equal(w, p, add, a + ".group_key", "groupKey", f.getGroupKey());
		if (!f.getCodes().isEmpty()) {
			w.append(" and ").append(a).append(".code in (:codes)");
			put(p, add, "codes", f.getCodes());
		}
		if (f.isDoubtOnly()) {
			w.append(" and ").append(a).append(".doubt is not null");
		}
		if (f.getText() != null) {
			w.append(" and (").append(a).append(".details like :text escape '\\' or ").append(a)
					.append(".subject_label like :text escape '\\' or ").append(a)
					.append(".subject_id like :text escape '\\')");
			put(p, add, "text", "%" + likeEscaped(f.getText()) + "%");
		}
		return w.toString();
	}

	private static void equal(StringBuilder w, MapSqlParameterSource p, boolean add, String column, String name,
			Object value) {
		if (value != null) {
			w.append(" and ").append(column).append(" = :").append(name);
			put(p, add, name, value);
		}
	}

	private static void put(MapSqlParameterSource p, boolean add, String name, Object value) {
		if (add) {
			p.addValue(name, value);
		}
	}

	/** The text searched as typed: %, _ and [ are not wildcards (escaped with \). */
	static String likeEscaped(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").replace("[", "\\[");
	}

	/** The runs of refused logins on the page: their counts, first and last times (in the filters). */
	private void withRuns(List<EventLogRow> rows, EventLogFilter filter) {
		if (filter.getGroupKey() != null) {
			return;
		}
		Set<String> keys = new LinkedHashSet<>();
		for (EventLogRow row : rows) {
			if (row.getGroupKey() != null) {
				keys.add(row.getGroupKey());
			}
		}
		if (keys.isEmpty()) {
			return;
		}
		MapSqlParameterSource params = new MapSqlParameterSource();
		String where = where("e", filter, params, true);
		params.addValue("keys", new ArrayList<>(keys));
		Map<String, Object[]> runs = new HashMap<>();
		jdbc.query("select e.group_key, count(*) as n, min(e.occurred_at) as first_at, max(e.occurred_at) as last_at"
				+ " from event_log e where " + where + " and e.group_key in (:keys) group by e.group_key", params,
				rs -> {
					runs.put(rs.getString("group_key"), new Object[] { rs.getLong("n"), time(rs, "first_at"),
							time(rs, "last_at") });
				});
		for (EventLogRow row : rows) {
			Object[] run = row.getGroupKey() == null ? null : runs.get(row.getGroupKey());
			if (run != null) {
				row.setGroupCount((Long) run[0]);
				row.setGroupFirstAt((LocalDateTime) run[1]);
				row.setGroupLastAt((LocalDateTime) run[2]);
			}
		}
	}

	// ─── Missing numbers ─────────────────────────────────────────────

	/**
	 * Per importance: the last number given (event_log_sequence), the numbered rows present, how many are missing (a
	 * row deleted in the database) and the first missing ranges. Over the whole table, not the period: a deleted row
	 * leaves no trace in its period. An event being written at that very moment (its number taken, not yet committed)
	 * can show as missing for an instant.
	 */
	public List<Missing> missing() {
		Map<Integer, Long> last = new HashMap<>();
		jdbc.getJdbcTemplate().query("select importance, last_value from event_log_sequence",
				rs -> {
					last.put(rs.getInt("importance"), rs.getLong("last_value"));
				});
		Map<Integer, long[]> present = new HashMap<>();
		jdbc.getJdbcTemplate().query("select importance, count(sequence_no) as n, min(sequence_no) as lo,"
				+ " max(sequence_no) as hi from event_log where sequence_no is not null group by importance",
				rs -> {
					present.put(rs.getInt("importance"), new long[] { rs.getLong("n"), rs.getLong("lo"), rs.getLong("hi") });
				});
		List<Missing> result = new ArrayList<>();
		for (int importance : Arrays.asList(EventImportance.ALERT, EventImportance.NOTABLE, EventImportance.STEP)) {
			Missing m = new Missing();
			m.importance = importance;
			m.lastNumber = last.getOrDefault(importance, 0L);
			long[] p = present.getOrDefault(importance, new long[] { 0, 0, 0 });
			m.present = p[0];
			m.missing = Math.max(0, m.lastNumber - m.present);
			if (m.missing > 0) {
				m.ranges = ranges(importance, p, m.lastNumber);
			}
			result.add(m);
		}
		return result;
	}

	private List<long[]> ranges(int importance, long[] present, long lastNumber) {
		List<long[]> ranges = new ArrayList<>();
		if (present[0] == 0) {
			ranges.add(new long[] { 1, lastNumber });
			return ranges;
		}
		if (present[1] > 1) {
			ranges.add(new long[] { 1, present[1] - 1 });
		}
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("importance", importance)
				.addValue("size", MAX_GAPS);
		ranges.addAll(jdbc.query("select x.sequence_no + 1 as gap_from, x.next_no - 1 as gap_to from (select sequence_no,"
				+ " lead(sequence_no) over (order by sequence_no) as next_no from event_log where importance = :importance"
				+ " and sequence_no is not null) x where x.next_no > x.sequence_no + 1 order by x.sequence_no"
				+ " offset 0 rows fetch next :size rows only", params,
				(rs, i) -> new long[] { rs.getLong("gap_from"), rs.getLong("gap_to") }));
		if (present[2] < lastNumber) {
			ranges.add(new long[] { present[2] + 1, lastNumber });
		}
		return ranges.size() > MAX_GAPS ? new ArrayList<>(ranges.subList(0, MAX_GAPS)) : ranges;
	}

	/** The numbers of one importance. */
	@Data
	public static class Missing {
		private int importance;
		/** The last number given (the counter). */
		private long lastNumber;
		/** The numbered rows present. */
		private long present;
		private long missing;
		/** The first missing ranges, [from, to]. */
		private List<long[]> ranges = new ArrayList<>();
	}

	// ─── Rows ────────────────────────────────────────────────────────

	static final RowMapper<EventLogRow> ROW = (rs, i) -> {
		EventLogRow r = new EventLogRow();
		r.setId(rs.getLong("id"));
		r.setImportance(rs.getInt("importance"));
		r.setSequenceNo(number(rs, "sequence_no"));
		r.setCode(rs.getString("code"));
		r.setCodeLabel(EventCode.labelOf(r.getCode()));
		r.setSubjectType(rs.getString("subject_type"));
		r.setSubjectId(rs.getString("subject_id"));
		r.setSubjectLabel(rs.getString("subject_label"));
		r.setOccurredAt(time(rs, "occurred_at"));
		r.setEventAt(time(rs, "event_at"));
		r.setFrontAt(time(rs, "front_at"));
		r.setPosMachineId(number(rs, "pos_machine_id"));
		r.setPosMachineName(rs.getString("pos_machine_name"));
		r.setSessionId(number(rs, "session_id"));
		r.setSessionNumber(rs.getString("session_number"));
		r.setCartId(rs.getString("cart_id"));
		r.setUserId(number(rs, "user_id"));
		r.setUserLogin(rs.getString("user_login"));
		r.setUserName(rs.getString("user_name"));
		r.setUserRole(rs.getString("user_role"));
		r.setAuthorisedById(number(rs, "authorised_by_id"));
		r.setAuthorisedByLogin(rs.getString("authorised_by_login"));
		r.setAuthorisedByName(rs.getString("authorised_by_name"));
		r.setBadgeOwnerId(number(rs, "badge_owner_id"));
		r.setBadgeOwnerLogin(rs.getString("badge_owner_login"));
		r.setBadgeOwnerName(rs.getString("badge_owner_name"));
		r.setSentById(number(rs, "sent_by_id"));
		r.setSentByLogin(rs.getString("sent_by_login"));
		r.setClientAddress(rs.getString("client_address"));
		r.setMachineSequenceNo(number(rs, "machine_sequence_no"));
		r.setInstallId(rs.getString("install_id"));
		r.setDoubt(rs.getString("doubt"));
		r.setOldValue(rs.getString("old_value"));
		r.setNewValue(rs.getString("new_value"));
		double amount = rs.getDouble("amount");
		if (!rs.wasNull()) {
			if (EventCode.amountIsCount(r.getCode())) {
				r.setCount(Math.round(amount)); // step 7: a count, never money
			} else {
				r.setAmount(EventLogRow.rounded(amount));
			}
		}
		r.setDetails(rs.getString("details"));
		r.setImported(rs.getBoolean("imported"));
		r.setGroupKey(rs.getString("group_key"));
		if (r.getGroupKey() != null) {
			r.setGroupFirstAt(r.getOccurredAt());
			r.setGroupLastAt(r.getOccurredAt());
		}
		return r;
	};

	private static Long number(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toLocalDateTime();
	}
}
