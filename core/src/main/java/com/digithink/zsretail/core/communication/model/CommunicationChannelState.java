package com.digithink.zsretail.core.communication.model;

import java.time.LocalDateTime;

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
 * 3.3.0, communication step 8a part 2: what the sender knows of a channel, one row per channel (written where the
 * messages are sent, on its first pause or its first measure).
 * <ul>
 * <li>Its pause: a channel problem (a refused login, a server unreachable...) pauses the channel, not the messages.
 * paused_since: the first problem of the run (null: not paused); paused_until: the next try; problems: how many in a
 * row (the wait grows with it: 1, 5, 15, then every 60 min); pause_reason: the last problem's words.</li>
 * <li>Its speed: the messages handed to the provider and the time they took in the latest cycles (about the last 1,000
 * messages), with the provider they were measured on and when: the New campaign window's sending time.</li>
 * </ul>
 * See docs/modules/communication.md, "Step 8a, part 2".
 */
@Entity
@Table(name = "comm_channel_state",
		uniqueConstraints = @UniqueConstraint(name = "uk_comm_channel_state_channel", columnNames = "channel"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class CommunicationChannelState extends _BaseEntity {

	public static final int PAUSE_REASON_LENGTH = 1000;

	@Nationalized
	@Column(name = "channel", nullable = false, length = 20)
	private String channel;

	@Column(name = "paused_since")
	private LocalDateTime pausedSince;

	@Column(name = "paused_until")
	private LocalDateTime pausedUntil;

	@Column(name = "problems", nullable = false)
	private int problems;

	@Nationalized
	@Column(name = "pause_reason", length = PAUSE_REASON_LENGTH)
	private String pauseReason;

	@Column(name = "speed_messages", nullable = false)
	private long speedMessages;

	@Column(name = "speed_millis", nullable = false)
	private long speedMillis;

	@Nationalized
	@Column(name = "speed_provider", length = 20)
	private String speedProvider;

	@Column(name = "speed_at")
	private LocalDateTime speedAt;

	public boolean isPaused() {
		return pausedSince != null;
	}
}
