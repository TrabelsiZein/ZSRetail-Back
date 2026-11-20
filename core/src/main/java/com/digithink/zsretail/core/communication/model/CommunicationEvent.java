package com.digithink.zsretail.core.communication.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import org.hibernate.annotations.Nationalized;

import com.digithink.zsretail.core.communication.enumeration.CommunicationEventStatus;
import com.digithink.zsretail.core.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 3.3.0, communication module, step 1: "what happened" (a member enrolled, a ticket validated for a member), one row per
 * event. Found by a query where it happens (nothing is hooked into the selling services); at a store whose loyalty is the
 * head office's it is sent up and handled there. The dedup key makes it once per installation: found twice, or sent up
 * twice, it is not written twice. Types are plain texts, not Java enums: a row received from a store of another version
 * must stay readable. A store and a head office alike. See docs/modules/communication.md.
 */
@Entity
@Table(name = "comm_event",
		uniqueConstraints = @UniqueConstraint(name = "uk_comm_event_dedup", columnNames = "dedup_key"),
		indexes = @Index(name = "ix_comm_event_queue", columnList = "status, created_at"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class CommunicationEvent extends _BaseEntity {

	public static final int LAST_ERROR_LENGTH = 1000;

	/** What happened, e.g. MEMBER_CREATED, TICKET_VALIDATED. */
	@Nationalized
	@Column(name = "event_type", nullable = false, length = 50)
	private String eventType;

	/** What it happened to, e.g. LOYALTY_MEMBER, SALES_HEADER. */
	@Nationalized
	@Column(name = "source_type", nullable = false, length = 50)
	private String sourceType;

	/** Id of the source in the database of origin_store_code (a store's own id at the head office). */
	@Column(name = "source_id")
	private Long sourceId;

	/** Once per event, e.g. TICKET_VALIDATED:STORE-B:12345 (the store code keeps it unique across the network). */
	@Nationalized
	@Column(name = "dedup_key", nullable = false, length = 200)
	private String dedupKey;

	/** Who it concerns, e.g. LOYALTY_MEMBER; null for an event without a recipient. */
	@Nationalized
	@Column(name = "recipient_type", length = 30)
	private String recipientType;

	/** The recipient's reference, e.g. the card number (the same at the store and at the head office). */
	@Nationalized
	@Column(name = "recipient_ref", length = 100)
	private String recipientRef;

	/** The values at that moment (JSON), e.g. ticket number, amount, points earned, balance. */
	@Nationalized
	@Column(name = "payload", columnDefinition = "nvarchar(max)")
	private String payload;

	/** Code of the store where it happened (DEFAULT_LOCATION); at a head office, the store that sent it. */
	@Nationalized
	@Column(name = "origin_store_code", length = 50)
	private String originStoreCode;

	/** Clock of the installation where it happened. */
	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, columnDefinition = "nvarchar(10)")
	private CommunicationEventStatus status = CommunicationEventStatus.PENDING;

	@Column(name = "attempts", nullable = false)
	private int attempts;

	@Nationalized
	@Column(name = "last_error", length = LAST_ERROR_LENGTH)
	private String lastError;

	/** When it left PENDING / ERROR: messages made here, accepted by the head office, or skipped. */
	@Column(name = "processed_at")
	private LocalDateTime processedAt;
}
