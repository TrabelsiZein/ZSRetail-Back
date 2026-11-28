package com.digithink.zsretail.core.convention;

import java.util.LinkedHashMap;
import java.util.Map;

import com.digithink.zsretail.core.exception.CodedRefusal;

/**
 * 3.6.0, conventions step 1, part B3: a change of the Conventions pages refused, with its English message, its code and
 * its values (the front shows serverErrors.&lt;code&gt; in the screen's language) and the HTTP status of the answer.
 * docs/modules/conventions.md lists the codes:
 * <ul>
 * <li>400: CONVENTION_CODE_REQUIRED, CONVENTION_CODE_INVALID (code), CONVENTION_CODE_TAKEN (code), CONVENTION_CODE_FIXED
 * (code, asked), CONVENTION_NAME_REQUIRED, CONVENTION_ORGANISATION_REQUIRED, CONVENTION_TOO_LONG (field, max),
 * CONVENTION_START_REQUIRED, CONVENTION_END_BEFORE_START (start, end), CONVENTION_LIMIT_INVALID (field),
 * CONVENTION_RECOGNITION_UNKNOWN (value), CONVENTION_PREFIX_INVALID (prefix), CONVENTION_CARD_DIGITS (min, max),
 * CONVENTION_CARD_SETTINGS_FIXED (field, code), CONVENTION_STATUS_UNKNOWN (value), CONVENTION_STATE_UNKNOWN (value,
 * the list's filter), CONVENTION_HAS_CARDS (code, cards); part B4, the cards: CONVENTION_NO_CARDS (code),
 * CONVENTION_EXPIRED (code, end), CONVENTION_GENERATE_COUNT (min, max), CONVENTION_NUMBERS_EXHAUSTED (code, left,
 * digits), CONVENTION_CARD_NUMBER_REQUIRED, CONVENTION_CARD_NUMBER_INVALID (number), CONVENTION_CARD_NUMBER_TAKEN
 * (number, convention), CONVENTION_CARD_NUMBER_RESERVED (number, kind), CONVENTION_CARD_IDS (min, max),
 * CONVENTION_CARD_STATUS_UNKNOWN (value);</li>
 * <li>403: CONVENTION_FORBIDDEN (permission); 404: CONVENTION_NOT_FOUND (id), CONVENTION_CARD_NOT_FOUND (ids); 409:
 * CONVENTION_OWNED_BY_HEAD_OFFICE (a store whose promotions are the head office's: decision 2), CONVENTION_CARDS_BUSY
 * (code: a number taken during a generation, try again).</li>
 * </ul>
 */
public class ConventionException extends IllegalArgumentException implements CodedRefusal {

	private static final long serialVersionUID = 1L;

	private final int status;
	private final String code;
	private final Map<String, Object> params = new LinkedHashMap<>();

	public ConventionException(int status, String code, String message, Object... keysAndValues) {
		super(message);
		this.status = status;
		this.code = code;
		for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
			params.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
		}
	}

	/** A 400 refusal. */
	public static ConventionException refused(String code, String message, Object... keysAndValues) {
		return new ConventionException(400, code, message, keysAndValues);
	}

	/** A 404: the convention is not there. */
	public static ConventionException notFound(long id) {
		return new ConventionException(404, "CONVENTION_NOT_FOUND", "No convention number " + id + ".", "id", id);
	}

	/** Decision 2: a 409, the conventions of this store are its head office's (they follow the promotions). */
	public static ConventionException ownedByHeadOffice() {
		return new ConventionException(409, "CONVENTION_OWNED_BY_HEAD_OFFICE", "The promotions of this store are its head"
				+ " office's: so are its conventions, they cannot be changed here.");
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
