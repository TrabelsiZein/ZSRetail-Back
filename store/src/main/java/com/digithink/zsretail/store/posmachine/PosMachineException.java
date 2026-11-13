package com.digithink.zsretail.store.posmachine;

/**
 * 3.2.0, POS machines step 1: a refusal of the POS machines page with its message for the user, and its reason, which
 * gives the HTTP answer (PosMachineAPI: INVALID 400, NOT_FOUND 404), the same {"error", "reason"} body as the RFID
 * endpoints. docs/modules/pos-machines.md.
 */
public class PosMachineException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/** Why the request was refused. */
	public enum Reason {
		/** A value breaks a rule (a name taken, not an IP address, a reader that does not exist...). */
		INVALID,
		/** No POS machine of that id. */
		NOT_FOUND
	}

	private final Reason reason;

	public PosMachineException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason getReason() {
		return reason;
	}
}
