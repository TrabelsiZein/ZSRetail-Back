package com.digithink.zsretail.core.communication.enumeration;

/** Where an event of the communication module stands (3.3.0). See docs/modules/communication.md. */
public enum CommunicationEventStatus {

	/** Found (or received from a store) and waiting to be handled. */
	PENDING,

	/** Handled: its messages made here, or accepted by the head office when the head office owns loyalty. */
	PROCESSED,

	/** Nothing to send (no rule, no consent, no address...): kept, never handled again. */
	SKIPPED,

	/** Could not be handled, or the head office rejected it: retried on later cycles, the reason in last_error. */
	ERROR
}
