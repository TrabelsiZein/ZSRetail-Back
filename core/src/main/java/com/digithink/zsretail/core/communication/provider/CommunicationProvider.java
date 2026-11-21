package com.digithink.zsretail.core.communication.provider;

import com.digithink.zsretail.core.communication.model.CommunicationMessage;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

/**
 * 3.3.0, communication module, step 3: who delivers a message. Named as in communication.&lt;channel&gt;.provider (only
 * "test" in step 3; the real providers come later). Called by the sender on its own thread. A throw is read as a
 * temporary failure. Step 8a part 2: the sender gives a cycle's messages to one {@link Batch} per provider (one SMTP
 * connection for the cycle), and a provider answers {@link Outcome#CHANNEL_PROBLEM} when the channel itself cannot send
 * (the same rule for every channel: the channel pauses, the messages wait without using their attempts).
 */
public interface CommunicationProvider {

	/** The name of the properties, lower case, e.g. test. */
	String getName();

	/** One message alone (its own connection, when the provider has one): the Canaux tab's test. */
	SendResult send(CommunicationMessage message);

	/**
	 * Step 8a part 2: the sends of one cycle, sharing what the provider can share (smtp: one connection, opened at the
	 * first message, closed by {@link Batch#close()}). Default: each message sent alone.
	 */
	default Batch openBatch() {
		return this::send;
	}

	/**
	 * Step 8a part 2: the sends of one cycle on one provider. Used by one thread; closed at the end of the cycle, also
	 * when a send failed. A send after a channel problem may try to open the connection again.
	 */
	interface Batch extends AutoCloseable {

		SendResult send(CommunicationMessage message);

		@Override
		default void close() {
			// nothing to close by default
		}
	}

	/**
	 * Step 8a: why this provider cannot send now (e.g. smtp without a host), null when it can. A channel on with a
	 * provider that cannot send is treated as off, with one WARN line at the start (CommunicationSettings).
	 */
	default String unavailableReason() {
		return null;
	}

	/**
	 * Step 8b: whether this provider sends this channel's messages (smtp: email only; http: SMS only). A channel set to
	 * a provider that does not send it is off, with one WARN line at the start (CommunicationSettings).
	 */
	default boolean serves(String channel) {
		return true;
	}

	/** Step 8b: the sender the Canaux tab shows (an email's sender, an SMS's sender name); null when none. */
	default String sender() {
		return null;
	}

	/**
	 * Step 8a: what the Canaux tab shows of this provider (host, port, sender...), never a password or a key: only
	 * whether it is set. Empty for a provider without settings.
	 */
	default java.util.Map<String, Object> describe() {
		return java.util.Collections.emptyMap();
	}

	enum Outcome {
		/** Taken by the provider: SENT. */
		ACCEPTED,
		/** Try again later (timeout, provider busy): PENDING with a wait, FAILED after the last attempt. */
		TEMPORARY_FAILURE,
		/** Never try again (address or text refused): FAILED at once. */
		PERMANENT_FAILURE,
		/**
		 * Step 8a part 2: the channel cannot send now, whatever the message (a refused login, a server unreachable, the
		 * sender refused, the server congested): not the message's fault. The message keeps its attempts and waits (until
		 * its own expiry); the sender stops the channel for the cycle and pauses it (1, 5, 15, then every 60 min, or the
		 * wait the provider gave, step 8b); a message accepted, or a test accepted on the Canaux tab, lifts the pause.
		 */
		CHANNEL_PROBLEM,
		/**
		 * Step 8b: the message left and no answer came back (a read timeout after the request, the connection cut after an
		 * email's final "."): the provider may have taken it. Never sent again by itself (an SMS costs, an email twice
		 * annoys): FAILED "no answer from the provider, check before sending it again", sent again by hand only; the
		 * channel pauses as for a channel problem.
		 */
		UNCERTAIN
	}

	@Value
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	class SendResult {
		Outcome outcome;
		/** The provider's id of the message, when accepted. */
		String providerMessageId;
		/** Why it failed, when it did. */
		String error;
		/** Step 8b: the wait the provider asked for (HTTP 429 Retry-After), null when it gave none. */
		java.time.Duration retryAfter;

		public static SendResult accepted(String providerMessageId) {
			return new SendResult(Outcome.ACCEPTED, providerMessageId, null, null);
		}

		public static SendResult temporaryFailure(String error) {
			return new SendResult(Outcome.TEMPORARY_FAILURE, null, error, null);
		}

		public static SendResult permanentFailure(String error) {
			return new SendResult(Outcome.PERMANENT_FAILURE, null, error, null);
		}

		/** Step 8a part 2: the channel's problem, not the message's. */
		public static SendResult channelProblem(String error) {
			return new SendResult(Outcome.CHANNEL_PROBLEM, null, error, null);
		}

		/** Step 8b: the channel's problem with the wait the provider asked for (null: the pause's own wait). */
		public static SendResult channelProblem(String error, java.time.Duration retryAfter) {
			return new SendResult(Outcome.CHANNEL_PROBLEM, null, error, retryAfter);
		}

		/** Step 8b: sent, no answer: the provider may have taken it. */
		public static SendResult uncertain(String error) {
			return new SendResult(Outcome.UNCERTAIN, null, error, null);
		}
	}
}
