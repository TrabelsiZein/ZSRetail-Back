package com.digithink.zsretail.core.tickettemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import com.digithink.zsretail.core.exception.CodedRefusal;

/**
 * 3.5.0, ticket templates step 2: a template refused, with its English message, its code and its values (the front shows
 * serverErrors.&lt;code&gt; in the screen's language) and its HTTP status. Codes: TICKET_TEMPLATE_TYPE_UNKNOWN (404,
 * type), TICKET_TEMPLATE_NOT_AVAILABLE (type), step 2b: TICKET_TEMPLATE_OPTION (section, element, option, value: a layout
 * option when section is null), TICKET_TEMPLATE_UNKNOWN_OPTION (section, element, option),
 * TICKET_TEMPLATE_TEXT (section, element, maxLines, maxLength), TICKET_TEMPLATE_UNKNOWN_SECTION (section),
 * TICKET_TEMPLATE_UNKNOWN_ELEMENT (section, element),
 * TICKET_TEMPLATE_DUPLICATE (section, element), TICKET_TEMPLATE_LOCKED (section, element), TICKET_TEMPLATE_VAT_NEEDED,
 * TICKET_TEMPLATE_FORBIDDEN (403, permission).
 */
public class TicketTemplateException extends IllegalArgumentException implements CodedRefusal {

	private static final long serialVersionUID = 1L;

	private final int status;
	private final String code;
	private final Map<String, Object> params = new LinkedHashMap<>();

	public TicketTemplateException(int status, String code, String message, Object... keysAndValues) {
		super(message);
		this.status = status;
		this.code = code;
		for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
			params.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
		}
	}

	/** A 400 refusal. */
	public static TicketTemplateException refused(String code, String message, Object... keysAndValues) {
		return new TicketTemplateException(400, code, message, keysAndValues);
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
