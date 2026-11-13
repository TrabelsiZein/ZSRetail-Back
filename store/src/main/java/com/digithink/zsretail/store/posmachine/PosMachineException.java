package com.digithink.zsretail.store.posmachine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 3.2.0, POS machines step 1: a refusal of the POS machines page with its message for the user, and its reason, which
 * gives the HTTP answer (PosMachineAPI: INVALID 400, NOT_FOUND 404), the same {"error", "reason"} body as the RFID
 * endpoints. Step 2: also its code and the values of its message ({"code", "params"}), which the front shows in the
 * language of the screen (serverErrors.&lt;code&gt;); the English message stays for the logs and an older front.
 * docs/modules/pos-machines.md.
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
	private final String code;
	private final Map<String, Object> params;

	/**
	 * @param params the values of the message, as name, value, name, value...
	 */
	public PosMachineException(Reason reason, String code, String message, Object... params) {
		super(message);
		this.reason = reason;
		this.code = code;
		Map<String, Object> values = new LinkedHashMap<>();
		for (int i = 0; i + 1 < params.length; i += 2) {
			values.put(String.valueOf(params[i]), params[i + 1]);
		}
		this.params = Collections.unmodifiableMap(values);
	}

	public Reason getReason() {
		return reason;
	}

	/** The message's code (POS_MACHINE_NAME_TAKEN...): the front's key serverErrors.&lt;code&gt;. */
	public String getCode() {
		return code;
	}

	/** The values of the message ({name}, {address}...). */
	public Map<String, Object> getParams() {
		return params;
	}
}
