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

import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 3.3.0, communication module, step 1: "what to send", one row per message, made where the rules and the sending live
 * (the owner of loyalty). Self-contained: the address and the text are copied here when the message is made, so the
 * sender reads nothing else. Channel and provider are plain texts (WHATSAPP, SMS, EMAIL; TEST first, then the real
 * providers). A store and a head office alike. See docs/modules/communication.md.
 */
@Entity
@Table(name = "comm_message",
		uniqueConstraints = @UniqueConstraint(name = "uk_comm_message_dedup", columnNames = "dedup_key"),
		indexes = { @Index(name = "ix_comm_message_queue", columnList = "status, next_attempt_at"),
				@Index(name = "ix_comm_message_event", columnList = "event_id"),
				@Index(name = "ix_comm_message_campaign", columnList = "campaign_id, status") })
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class CommunicationMessage extends _BaseEntity {

	public static final int LAST_ERROR_LENGTH = 1000;

	/** comm_event id of the event that made it; null for a campaign message. */
	@Column(name = "event_id")
	private Long eventId;

	/** Step 7: comm_campaign id of the campaign that made it; null for an automatic message (an event's). */
	@Column(name = "campaign_id")
	private Long campaignId;

	/** WHATSAPP, SMS or EMAIL. */
	@Nationalized
	@Column(name = "channel", nullable = false, length = 20)
	private String channel;

	/** Who sent it or will send it, e.g. TEST; null until a provider takes it. */
	@Nationalized
	@Column(name = "provider", length = 30)
	private String provider;

	@Nationalized
	@Column(name = "recipient_type", nullable = false, length = 30)
	private String recipientType;

	/** The recipient's reference, e.g. the card number: consent is read again at send time. */
	@Nationalized
	@Column(name = "recipient_ref", length = 100)
	private String recipientRef;

	/** The address when the message was made: a phone as +21612345678, or an email. */
	@Nationalized
	@Column(name = "address", nullable = false, length = 255)
	private String address;

	/** Email only. */
	@Nationalized
	@Column(name = "subject", length = 255)
	private String subject;

	@Nationalized
	@Column(name = "body", columnDefinition = "nvarchar(max)")
	private String body;

	@Nationalized
	@Column(name = "template_code", length = 50)
	private String templateCode;

	/** ar, fr or en. */
	@Nationalized
	@Column(name = "language_code", length = 5)
	private String languageCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, columnDefinition = "nvarchar(10)")
	private CommunicationMessageStatus status = CommunicationMessageStatus.PENDING;

	@Column(name = "attempts", nullable = false)
	private int attempts;

	/** Not sent before; null: at once. */
	@Column(name = "next_attempt_at")
	private LocalDateTime nextAttemptAt;

	/** Not sent after (EXPIRED); null: no limit. */
	@Column(name = "expires_at")
	private LocalDateTime expiresAt;

	@Nationalized
	@Column(name = "provider_message_id", length = 255)
	private String providerMessageId;

	@Nationalized
	@Column(name = "last_error", length = LAST_ERROR_LENGTH)
	private String lastError;

	/** Once per message, e.g. the event's key with the channel and the address. */
	@Nationalized
	@Column(name = "dedup_key", nullable = false, length = 200)
	private String dedupKey;

	/** When the provider accepted it. */
	@Column(name = "sent_at")
	private LocalDateTime sentAt;
}
