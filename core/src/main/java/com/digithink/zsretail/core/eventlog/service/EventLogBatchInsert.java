package com.digithink.zsretail.core.eventlog.service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.jdbc.core.JdbcTemplate;

import com.digithink.zsretail.core.eventlog.model.EventLogEntry;

/**
 * 3.4.0, event log step 4: a till's group of events inserted in one JDBC batch (event_log's id is an identity: JPA would
 * insert row by row). Run inside EventLogWriter's own transaction, after the group is numbered; the JPA transaction
 * manager gives JdbcTemplate the same connection. Every column of EventLogEntry but the id.
 */
public final class EventLogBatchInsert implements Consumer<List<EventLogEntry>> {

	public static final String SQL = "insert into event_log (event_uuid, importance, sequence_no, code, subject_type, subject_id,"
			+ " subject_label, event_at, front_at, pos_machine_id, pos_machine_name, session_id, session_number, cart_id,"
			+ " user_id, user_login, user_name, user_role, authorised_by_id, authorised_by_login, authorised_by_name,"
			+ " sent_by_id, sent_by_login, client_address, machine_sequence_no, install_id, doubt, old_value, new_value,"
			+ " amount, details, imported, occurred_at, badge_owner_id, badge_owner_login, badge_owner_name, group_key)"
			+ " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

	private final JdbcTemplate jdbc;

	public EventLogBatchInsert(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void accept(List<EventLogEntry> group) {
		jdbc.batchUpdate(SQL, group, group.size(), EventLogBatchInsert::bind);
	}

	/** One row's 37 values, in the order of {@link #SQL} (step 6: occurred_at, the badge's owner, the group key). */
	public static void bind(PreparedStatement ps, EventLogEntry e) throws SQLException {
		int i = 1;
		text(ps, i++, e.getEventUuid());
		ps.setInt(i++, e.getImportance());
		number(ps, i++, e.getSequenceNo());
		text(ps, i++, e.getCode());
		text(ps, i++, e.getSubjectType());
		text(ps, i++, e.getSubjectId());
		text(ps, i++, e.getSubjectLabel());
		time(ps, i++, e.getEventAt());
		time(ps, i++, e.getFrontAt());
		number(ps, i++, e.getPosMachineId());
		text(ps, i++, e.getPosMachineName());
		number(ps, i++, e.getSessionId());
		text(ps, i++, e.getSessionNumber());
		text(ps, i++, e.getCartId());
		number(ps, i++, e.getUserId());
		text(ps, i++, e.getUserLogin());
		text(ps, i++, e.getUserName());
		text(ps, i++, e.getUserRole());
		number(ps, i++, e.getAuthorisedById());
		text(ps, i++, e.getAuthorisedByLogin());
		text(ps, i++, e.getAuthorisedByName());
		number(ps, i++, e.getSentById());
		text(ps, i++, e.getSentByLogin());
		text(ps, i++, e.getClientAddress());
		number(ps, i++, e.getMachineSequenceNo());
		text(ps, i++, e.getInstallId());
		text(ps, i++, e.getDoubt());
		text(ps, i++, e.getOldValue());
		text(ps, i++, e.getNewValue());
		if (e.getAmount() == null) {
			ps.setNull(i++, Types.DOUBLE);
		} else {
			ps.setDouble(i++, e.getAmount());
		}
		text(ps, i++, e.getDetails());
		ps.setBoolean(i++, e.isImported());
		time(ps, i++, e.getOccurredAt() != null ? e.getOccurredAt() : EventLogEntry.occurredOf(e));
		number(ps, i++, e.getBadgeOwnerId());
		text(ps, i++, e.getBadgeOwnerLogin());
		text(ps, i++, e.getBadgeOwnerName());
		text(ps, i, e.getGroupKey());
	}

	/** nvarchar: the driver sends strings as Unicode (as Hibernate's @Nationalized columns). */
	private static void text(PreparedStatement ps, int i, String value) throws SQLException {
		if (value == null) {
			ps.setNull(i, Types.NVARCHAR);
		} else {
			ps.setString(i, value);
		}
	}

	private static void number(PreparedStatement ps, int i, Long value) throws SQLException {
		if (value == null) {
			ps.setNull(i, Types.BIGINT);
		} else {
			ps.setLong(i, value);
		}
	}

	private static void time(PreparedStatement ps, int i, LocalDateTime value) throws SQLException {
		if (value == null) {
			ps.setNull(i, Types.TIMESTAMP);
		} else {
			ps.setTimestamp(i, Timestamp.valueOf(value));
		}
	}
}
