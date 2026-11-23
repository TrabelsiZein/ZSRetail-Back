package com.digithink.zsretail.core.eventlog.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Nationalized;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.4.0, the event log ("Journal des événements"): one row per event, inserted once and never updated (Immutable: an
 * update through JPA is ignored). It knows no ticket nor item: the subject is a type and an id as text, and the names
 * (user, role, machine, item label) are copied as text, so a row stays readable after a rename or a delete. No foreign
 * key. Written only through EventLog (core.eventlog.service), which numbers it per importance (event_log_sequence).
 * An append-only log with its own columns, not a _BaseEntity (as ItemCostHistory). Every text column is nvarchar: the
 * driver sends nvarchar parameters, a varchar column would turn the filters into scans. db/3.4.0/update.sql,
 * docs/modules/event-log.md.
 */
@Entity
@Immutable
@Table(name = "event_log",
		uniqueConstraints = @UniqueConstraint(name = "uk_event_log_uuid", columnNames = "event_uuid"),
		indexes = { @Index(name = "ix_event_log_at", columnList = "event_at"),
				@Index(name = "ix_event_log_subject", columnList = "subject_type, subject_id"),
				@Index(name = "ix_event_log_session", columnList = "session_id"),
				@Index(name = "ix_event_log_user", columnList = "user_id, event_at"),
				@Index(name = "ix_event_log_sequence", columnList = "importance, sequence_no") })
@Data
@NoArgsConstructor
public class EventLogEntry {

	public static final int UUID_LENGTH = 36;
	public static final int SUBJECT_ID_LENGTH = 100;
	public static final int LABEL_LENGTH = 255;
	public static final int NAME_LENGTH = 150;
	public static final int LOGIN_LENGTH = 100;
	public static final int MACHINE_NAME_LENGTH = 100;
	public static final int SESSION_NUMBER_LENGTH = 50;
	public static final int ADDRESS_LENGTH = 45;
	public static final int VALUE_LENGTH = 1000;
	public static final int DETAILS_LENGTH = 2000;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Once per event: made by the till for its events (a resend is not stored twice), by the server for the others. */
	@Nationalized
	@Column(name = "event_uuid", nullable = false, length = UUID_LENGTH)
	private String eventUuid;

	/** EventImportance: 10 STEP, 20 NOTABLE, 30 ALERT (from the catalogue, never from the caller). */
	@Column(name = "importance", nullable = false)
	private int importance;

	/**
	 * The number of the event within its importance, 1, 2, 3... without a hole: a missing number is a row deleted. Null
	 * for a row imported from before the module (the old badge scans).
	 */
	@Column(name = "sequence_no")
	private Long sequenceNo;

	/** EventCode, as text. */
	@Nationalized
	@Column(name = "code", nullable = false, length = 60)
	private String code;

	/** EventSubject, as text. */
	@Nationalized
	@Column(name = "subject_type", nullable = false, length = 30)
	private String subjectType;

	/** The subject's id as text: a ticket's id, a cart's UUID, a setting's code, a till action. */
	@Nationalized
	@Column(name = "subject_id", length = SUBJECT_ID_LENGTH)
	private String subjectId;

	/** What a person reads for the subject: a ticket number, an item's label, a user's name. */
	@Nationalized
	@Column(name = "subject_label", length = LABEL_LENGTH)
	private String subjectLabel;

	/** The server's clock when the event was written down (for a till's event: when it arrived). */
	@Column(name = "event_at", nullable = false)
	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
	private LocalDateTime eventAt;

	/** The till's clock when the gesture happened; null for an event of the server. */
	@Column(name = "front_at")
	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
	private LocalDateTime frontAt;

	@Column(name = "pos_machine_id")
	private Long posMachineId;

	@Nationalized
	@Column(name = "pos_machine_name", length = MACHINE_NAME_LENGTH)
	private String posMachineName;

	@Column(name = "session_id")
	private Long sessionId;

	@Nationalized
	@Column(name = "session_number", length = SESSION_NUMBER_LENGTH)
	private String sessionNumber;

	/** The cart's UUID (made by the till at its first line), on the cart's events and on the ticket it became. */
	@Nationalized
	@Column(name = "cart_id", length = UUID_LENGTH)
	private String cartId;

	/** Who did it. */
	@Column(name = "user_id")
	private Long userId;

	@Nationalized
	@Column(name = "user_login", length = LOGIN_LENGTH)
	private String userLogin;

	@Nationalized
	@Column(name = "user_name", length = NAME_LENGTH)
	private String userName;

	@Nationalized
	@Column(name = "user_role", length = LOGIN_LENGTH)
	private String userRole;

	/** Who allowed it with a badge (the badge's owner); empty when nobody did (the user's own role, or no right asked). */
	@Column(name = "authorised_by_id")
	private Long authorisedById;

	@Nationalized
	@Column(name = "authorised_by_login", length = LOGIN_LENGTH)
	private String authorisedByLogin;

	@Nationalized
	@Column(name = "authorised_by_name", length = NAME_LENGTH)
	private String authorisedByName;

	/** The till's events: the user whose login sent them (another user when sent again after a logout). */
	@Column(name = "sent_by_id")
	private Long sentById;

	@Nationalized
	@Column(name = "sent_by_login", length = LOGIN_LENGTH)
	private String sentByLogin;

	/** The address the request came from (a refused login from an unknown PC). */
	@Nationalized
	@Column(name = "client_address", length = ADDRESS_LENGTH)
	private String clientAddress;

	/** The till's own number of its events (per POS machine): a hole shows events the till never sent. */
	@Column(name = "machine_sequence_no")
	private Long machineSequenceNo;

	@Nationalized
	@Column(name = "old_value", length = VALUE_LENGTH)
	private String oldValue;

	@Nationalized
	@Column(name = "new_value", length = VALUE_LENGTH)
	private String newValue;

	@Column(name = "amount")
	private Double amount;

	/** One free text: a reason, a quantity, what the other columns do not say. */
	@Nationalized
	@Column(name = "details", length = DETAILS_LENGTH)
	private String details;

	/** A row copied from before the module (badge_scan_log), without a sequence number. */
	@Column(name = "imported", nullable = false, columnDefinition = "bit default 0")
	private boolean imported;
}
