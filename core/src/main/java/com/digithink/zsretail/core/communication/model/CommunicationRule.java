package com.digithink.zsretail.core.communication.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import org.hibernate.annotations.Nationalized;

import com.digithink.zsretail.core.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 3.3.0, communication module, step 3: "this event, to this recipient, by this channel, with this template". Each active
 * rule of an event's type whose channel is on makes one message. Active or not: the column active of every entity. See
 * docs/modules/communication.md.
 */
@Entity
@Table(name = "comm_rule", uniqueConstraints = @UniqueConstraint(name = "uk_comm_rule_code", columnNames = "code"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class CommunicationRule extends _BaseEntity {

	public static final int DEFAULT_LIFETIME_HOURS = 24;

	@Nationalized
	@Column(name = "code", nullable = false, length = 50)
	private String code;

	/** MEMBER_CREATED, TICKET_VALIDATED. */
	@Nationalized
	@Column(name = "event_type", nullable = false, length = 50)
	private String eventType;

	/** LOYALTY_MEMBER. */
	@Nationalized
	@Column(name = "recipient_type", nullable = false, length = 30)
	private String recipientType;

	/** WHATSAPP, SMS or EMAIL. */
	@Nationalized
	@Column(name = "channel", nullable = false, length = 20)
	private String channel;

	/** comm_template.code; the template's channel must be the rule's. */
	@Nationalized
	@Column(name = "template_code", nullable = false, length = 50)
	private String templateCode;

	/** A message is never sent more than this many hours after its event happened (comm_message.expires_at). */
	@Column(name = "lifetime_hours", nullable = false)
	private int lifetimeHours = DEFAULT_LIFETIME_HOURS;
}
