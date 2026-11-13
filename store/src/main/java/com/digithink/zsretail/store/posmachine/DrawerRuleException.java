package com.digithink.zsretail.store.posmachine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.digithink.zsretail.core.exception.CodedRefusal;

/**
 * 3.2.0, POS machines step 2, part B: a refusal of a rule of the drawer (DrawerOwner.POS_MACHINE, or the choice itself):
 * its English message, its code and the values of the message, answered 409 by the session opening and the selling
 * guard, 400 by General setup ({"error", "reason", "code", "params"}). An IllegalStateException, so an endpoint that does
 * not know it still refuses with its message. docs/modules/pos-machines.md.
 */
public class DrawerRuleException extends IllegalStateException implements CodedRefusal {

	private static final long serialVersionUID = 1L;

	private final String code;
	private final Map<String, Object> params;

	/**
	 * @param params the values of the message, as name, value, name, value...
	 */
	public DrawerRuleException(String code, String message, Object... params) {
		super(message);
		this.code = code;
		Map<String, Object> values = new LinkedHashMap<>();
		for (int i = 0; i + 1 < params.length; i += 2) {
			values.put(String.valueOf(params[i]), params[i + 1]);
		}
		this.params = Collections.unmodifiableMap(values);
	}

	@Override
	public String getCode() {
		return code;
	}

	@Override
	public Map<String, Object> getParams() {
		return params;
	}

	/** The body of the answer: {"error", "reason" (the code), "code", "params"}. */
	public Map<String, Object> body() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", getMessage());
		body.put("reason", code);
		body.put("code", code);
		body.put("params", params);
		return body;
	}
}
