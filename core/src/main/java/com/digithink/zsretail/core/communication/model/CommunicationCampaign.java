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

import com.digithink.zsretail.core.communication.enumeration.CommunicationCampaignStatus;
import com.digithink.zsretail.core.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 3.3.0, communication step 7: one template sent to every member who accepts messages, where this installation sends
 * (a store alone, a store that owns its loyalty, a head office). Written at its launch, RUNNING; the messages are made
 * in the background by CommunicationCampaignMaker, from {@link #lastMemberId} on (a restart goes on where it stopped),
 * once per campaign and address (comm_message.dedup_key CAMPAIGN:&lt;id&gt;:&lt;address&gt;). See
 * docs/modules/communication.md, "Step 7".
 */
@Entity
@Table(name = "comm_campaign",
		uniqueConstraints = @UniqueConstraint(name = "uk_comm_campaign_code", columnNames = "code"),
		indexes = @Index(name = "ix_comm_campaign_status", columnList = "status"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class CommunicationCampaign extends _BaseEntity {

	/** A campaign message is never sent more than this many hours after the launch, unless set otherwise. */
	public static final int DEFAULT_LIFETIME_HOURS = 24;

	/** CMP- and the launch instant, e.g. CMP-261121-103015-042. */
	@Nationalized
	@Column(name = "code", nullable = false, length = 30)
	private String code;

	@Nationalized
	@Column(name = "name", nullable = false, length = 100)
	private String name;

	/** comm_template.code, as it was launched (the template's channel). */
	@Nationalized
	@Column(name = "template_code", nullable = false, length = 50)
	private String templateCode;

	/** WHATSAPP, SMS or EMAIL. */
	@Nationalized
	@Column(name = "channel", nullable = false, length = 20)
	private String channel;

	/** The template's language, subject and text when it was launched: a template changed later changes nothing. */
	@Nationalized
	@Column(name = "language_code", length = 5)
	private String languageCode;

	@Nationalized
	@Column(name = "subject", length = 255)
	private String subject;

	@Nationalized
	@Column(name = "body", nullable = false, columnDefinition = "nvarchar(max)")
	private String body;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, columnDefinition = "nvarchar(12)")
	private CommunicationCampaignStatus status = CommunicationCampaignStatus.RUNNING;

	/** Its messages are not sent more than this many hours after the launch (comm_message.expires_at). */
	@Column(name = "lifetime_hours", nullable = false)
	private int lifetimeHours = DEFAULT_LIFETIME_HOURS;

	/** The members who were to receive it when it was launched (the count of the window). */
	@Column(name = "targeted", nullable = false)
	private int targeted;

	/** The messages made so far. */
	@Column(name = "made", nullable = false)
	private int made;

	/** The members dropped while the messages were made (consent withdrawn, address gone or shared, a value missing). */
	@Column(name = "left_out", nullable = false)
	private int leftOut;

	/** The last loyalty_member id handled: the making goes on after it (0: not started). */
	@Column(name = "last_member_id", nullable = false)
	private long lastMemberId;

	@Column(name = "launched_at", nullable = false)
	private LocalDateTime launchedAt;

	@Nationalized
	@Column(name = "launched_by", length = 100)
	private String launchedBy;

	/** When every message was made (null: still making). */
	@Column(name = "made_at")
	private LocalDateTime madeAt;

	/** DONE or CANCELLED. */
	@Column(name = "ended_at")
	private LocalDateTime endedAt;

	@Nationalized
	@Column(name = "cancelled_by", length = 100)
	private String cancelledBy;

	/**
	 * Step 8c, WhatsApp: the template at Meta when the campaign was launched, JSON {name, language, format} (as the
	 * text above, a snapshot); null for another channel.
	 */
	@Nationalized
	@Column(name = "provider_template", length = 400)
	private String providerTemplate;

	/** Step 8c: why the campaign was stopped (STOPPED: the provider refused its template). */
	@Nationalized
	@Column(name = "stop_reason", length = 1000)
	private String stopReason;
}
