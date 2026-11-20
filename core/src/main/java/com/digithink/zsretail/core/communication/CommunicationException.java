package com.digithink.zsretail.core.communication;

import java.util.LinkedHashMap;
import java.util.Map;

import com.digithink.zsretail.core.exception.CodedRefusal;

/**
 * 3.3.0, communication step 4: a change of the Communication page refused, with its English message, its code and its
 * values (the front shows serverErrors.&lt;code&gt; in the screen's language) and the HTTP status of the answer (400, 404
 * for a row not found). Codes: COMM_MESSAGE_NOT_FOUND, COMM_RULE_NOT_FOUND, COMM_TEMPLATE_NOT_FOUND (code),
 * COMM_RESEND_STATUS (status), COMM_RESEND_NO_ADDRESS (card), COMM_CHANNEL_UNKNOWN (channel), COMM_CHANNEL_OFF
 * (channel), COMM_RULE_LIFETIME (min, max), COMM_RULE_TEMPLATE_CHANNEL (template, channel), COMM_RULE_TEMPLATE_INACTIVE
 * (template), COMM_TEMPLATE_CODE_INVALID, COMM_TEMPLATE_CODE_TAKEN (code), COMM_TEMPLATE_NAME_REQUIRED,
 * COMM_TEMPLATE_LANGUAGE, COMM_TEMPLATE_SUBJECT_REQUIRED, COMM_TEMPLATE_BODY_REQUIRED, COMM_TEMPLATE_UNKNOWN_FIELD
 * (field), COMM_TEMPLATE_TOO_LONG (field, max), COMM_TEMPLATE_USED (rule), COMM_TEMPLATE_USED_CHANNEL (rule).
 */
public class CommunicationException extends IllegalArgumentException implements CodedRefusal {

	private static final long serialVersionUID = 1L;

	private final int status;
	private final String code;
	private final Map<String, Object> params = new LinkedHashMap<>();

	public CommunicationException(int status, String code, String message, Object... keysAndValues) {
		super(message);
		this.status = status;
		this.code = code;
		for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
			params.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
		}
	}

	/** A 400 refusal. */
	public static CommunicationException refused(String code, String message, Object... keysAndValues) {
		return new CommunicationException(400, code, message, keysAndValues);
	}

	/** A 404: the row is not there. */
	public static CommunicationException notFound(String code, String message, Object... keysAndValues) {
		return new CommunicationException(404, code, message, keysAndValues);
	}

	public int getStatus() {
		return status;
	}

	@Override
	public String getCode() {
		return code;
	}

	@Override
	public Map<String, Object> getParams() {
		return params;
	}

	/** The body of the answer: {error, code, params}. */
	public Map<String, Object> body() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", getMessage());
		body.put("code", code);
		body.put("params", params);
		return body;
	}
}
