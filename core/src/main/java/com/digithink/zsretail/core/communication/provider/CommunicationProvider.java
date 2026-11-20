package com.digithink.zsretail.core.communication.provider;

import com.digithink.zsretail.core.communication.model.CommunicationMessage;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

/**
 * 3.3.0, communication module, step 3: who delivers a message. Named as in communication.&lt;channel&gt;.provider (only
 * "test" in step 3; the real providers come later). Called by the sender on its own thread, one message at a time. A
 * throw is read as a temporary failure.
 */
public interface CommunicationProvider {

	/** The name of the properties, lower case, e.g. test. */
	String getName();

	SendResult send(CommunicationMessage message);

	enum Outcome {
		/** Taken by the provider: SENT. */
		ACCEPTED,
		/** Try again later (timeout, provider busy): PENDING with a wait, FAILED after the last attempt. */
		TEMPORARY_FAILURE,
		/** Never try again (address or text refused): FAILED at once. */
		PERMANENT_FAILURE
	}

	@Value
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	class SendResult {
		Outcome outcome;
		/** The provider's id of the message, when accepted. */
		String providerMessageId;
		/** Why it failed, when it did. */
		String error;

		public static SendResult accepted(String providerMessageId) {
			return new SendResult(Outcome.ACCEPTED, providerMessageId, null);
		}

		public static SendResult temporaryFailure(String error) {
			return new SendResult(Outcome.TEMPORARY_FAILURE, null, error);
		}

		public static SendResult permanentFailure(String error) {
			return new SendResult(Outcome.PERMANENT_FAILURE, null, error);
		}
	}
}
