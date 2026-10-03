package com.digithink.zsretail.holink.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeLink;
import com.digithink.zsretail.holink.dto.HeadOfficeCallResult;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * State of the link to the head office (task 1.4), in memory only: it starts as PENDING at each start. Written by
 * the heartbeat thread, read through {@link #get()} (task 1.5 exposes it).
 */
@Component
@ConditionalOnHeadOfficeLink
public class HeadOfficeLinkStatus {

	static final String NO_HEARTBEAT_YET = "no heartbeat yet";

	private volatile Snapshot snapshot = new Snapshot(HeadOfficeLinkState.PENDING, null, null, NO_HEARTBEAT_YET, null,
			null, null, null);

	public Snapshot get() {
		return snapshot;
	}

	/**
	 * Records the result of a call made at {@code at} (store clock) and returns the previous state. A failure keeps
	 * the last success and its head office time. Step 4: the loyalty rights of the last answer that carried them are kept.
	 */
	public synchronized HeadOfficeLinkState record(HeadOfficeCallResult result, LocalDateTime at) {
		Snapshot previous = snapshot;
		boolean online = result.getState() == HeadOfficeLinkState.ONLINE;
		boolean rights = online && (result.getCanEditMembers() != null || result.getCanAdjustPoints() != null
				|| result.getRedeemRequiresOnline() != null);
		snapshot = new Snapshot(result.getState(), at, online ? at : previous.getLastSuccess(), result.getMessage(),
				online ? result.getServerTime() : previous.getServerTime(),
				rights ? result.getCanEditMembers() : previous.getCanEditMembers(),
				rights ? result.getCanAdjustPoints() : previous.getCanAdjustPoints(),
				rights ? result.getRedeemRequiresOnline() : previous.getRedeemRequiresOnline());
		return previous.getState();
	}

	/** Immutable view of the link. */
	@Getter
	@ToString
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static final class Snapshot {

		private final HeadOfficeLinkState state;

		/** Store clock; null before the first heartbeat. */
		private final LocalDateTime lastAttempt;

		/** Store clock of the last ONLINE answer; null until then. */
		private final LocalDateTime lastSuccess;

		/** Message of the last call; null when it was ONLINE. */
		private final String lastMessage;

		/** Head office time sent with the last ONLINE answer (ISO-8601 with offset); null until then. */
		private final String serverTime;

		/** Step 4: the store's loyalty rights from the last heartbeat answer that carried them; null until then. */
		private final Boolean canEditMembers;
		private final Boolean canAdjustPoints;

		/** Step 5: spending needs a fresh balance (null until the first heartbeat answer: not required). */
		private final Boolean redeemRequiresOnline;
	}
}
