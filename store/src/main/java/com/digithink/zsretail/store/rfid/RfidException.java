package com.digithink.zsretail.store.rfid;

/**
 * RFID: a refusal with its message for the user, and its reason, which gives the HTTP answer (RFID step 7). The
 * message never holds a password or a key.
 */
public class RfidException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/** Why the request was refused. */
	public enum Reason {
		/** A value breaks a rule (a name taken, a port out of range...). */
		INVALID,
		/** No reader, or no POS machine, of that id or that address. */
		NOT_FOUND,
		/** The machine, or its reader, cannot read: inactive, or no reader linked. */
		NOT_READY,
		/** The reader is already reading for another request. */
		BUSY,
		/** The reader does not answer at its address. */
		UNREACHABLE,
		/** The reader answered with an error. */
		READER_ERROR
	}

	private final Reason reason;

	public RfidException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public RfidException(Reason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
	}

	public Reason getReason() {
		return reason;
	}
}
