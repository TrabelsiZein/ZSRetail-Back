package com.digithink.zsretail.store.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.digithink.zsretail.core.model.enumeration.BadgePermission;

/**
 * Step D (3.1.0): a request does a till action the logged user's role does not hold, and no valid grant of a badge
 * scan came with it. The endpoint answers 403 {@code {error: TILL_RIGHT_REQUIRED, action, reason, lineIndex?}}; the
 * till asks for a badge and sends the request again with the new grant. Not an IllegalStateException: the endpoints
 * map those to 400 or 409. Thrown inside the request's transaction, so nothing of the request is kept (grants used
 * before it included).
 */
public class TillRightRequiredException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public static final String ERROR = "TILL_RIGHT_REQUIRED";

	/** Why no grant counted: none sent, unknown, already used, expired, of another session or of another action. */
	public enum Reason {
		NO_GRANT, GRANT_UNKNOWN, GRANT_USED, GRANT_EXPIRED, GRANT_OTHER_SESSION, GRANT_OTHER_ACTION
	}

	private final BadgePermission action;
	private final Reason reason;
	private final Integer lineIndex;

	public TillRightRequiredException(BadgePermission action, Reason reason, Integer lineIndex) {
		super("Till right required: " + action + " (" + reason + (lineIndex != null ? ", line " + lineIndex : "") + ")");
		this.action = action;
		this.reason = reason;
		this.lineIndex = lineIndex;
	}

	public BadgePermission getAction() {
		return action;
	}

	public Reason getReason() {
		return reason;
	}

	/** The index of the request's line whose discount needs the right (a line discount), else null. */
	public Integer getLineIndex() {
		return lineIndex;
	}

	public Map<String, Object> body() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", ERROR);
		body.put("action", action.name());
		body.put("reason", reason.name());
		if (lineIndex != null) {
			body.put("lineIndex", lineIndex);
		}
		body.put("message", getMessage());
		return body;
	}

	/** The 403 answer of an endpoint. */
	public ResponseEntity<Map<String, Object>> response() {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body());
	}
}
