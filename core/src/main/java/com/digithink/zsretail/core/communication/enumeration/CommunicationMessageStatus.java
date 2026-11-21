package com.digithink.zsretail.core.communication.enumeration;

/** Where a message of the communication module stands (3.3.0). See docs/modules/communication.md. */
public enum CommunicationMessageStatus {

	/** Waiting to be sent, at next_attempt_at (null: at once); also after a temporary failure. */
	PENDING,

	/**
	 * Step 8a: given to the provider, its answer not written yet. Saved before the provider is called: a message still
	 * SENDING at the next start may have left, it is never sent again by itself (FAILED "interrupted", sent again by
	 * hand).
	 */
	SENDING,

	/** Accepted by the provider (not "delivered": no delivery report in version 1). */
	SENT,

	/** Refused for good (bad address, refused text) or too many attempts: never sent again. */
	FAILED,

	/** Not sent before expires_at: dropped, never sent late. */
	EXPIRED,

	/** Not to be sent (consent withdrawn, module off at send time...): kept for the log. */
	SKIPPED
}
