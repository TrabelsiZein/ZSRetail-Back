package com.digithink.zsretail.store.cashmovement;

import java.util.LinkedHashMap;
import java.util.Map;

import com.digithink.zsretail.core.exception.CodedRefusal;

/**
 * 3.2.0, POS machines step 5: a cash movement refused (400): its English message, its code and values (the front shows
 * serverErrors.&lt;code&gt; in the screen's language). Codes: CASH_MOVEMENT_TYPE_INVALID, CASH_MOVEMENT_AMOUNT_INVALID,
 * CASH_MOVEMENT_NOTE_REQUIRED, CASH_MOVEMENT_NOTE_TOO_LONG (max), CASH_MOVEMENT_NO_OPEN_SESSION,
 * CASH_MOVEMENT_NOT_YOUR_SESSION.
 */
public class CashMovementException extends IllegalArgumentException implements CodedRefusal {

	private static final long serialVersionUID = 1L;

	private final String code;
	private final Map<String, Object> params = new LinkedHashMap<>();

	public CashMovementException(String code, String message, Object... keysAndValues) {
		super(message);
		this.code = code;
		for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
			params.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
		}
	}

	@Override
	public String getCode() {
		return code;
	}

	@Override
	public Map<String, Object> getParams() {
		return params;
	}

	/** The 400 body: {error, reason: the code, code, params}. */
	public Map<String, Object> body() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", getMessage());
		body.put("reason", code);
		body.put("code", code);
		body.put("params", params);
		return body;
	}
}
