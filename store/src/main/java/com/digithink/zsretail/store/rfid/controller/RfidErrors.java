package com.digithink.zsretail.store.rfid.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.digithink.zsretail.store.rfid.RfidException;

/**
 * RFID step 6: the answer of a refusal, for every RFID endpoint: {"error": its message, "reason": its reason}, with the
 * status of its reason.
 *
 * <pre>
 * INVALID 400 · NOT_FOUND 404 · NOT_READY 409 · BUSY 409 · READER_ERROR 502 · UNREACHABLE 503
 * </pre>
 */
public final class RfidErrors {

	private RfidErrors() {
	}

	public static HttpStatus statusOf(RfidException.Reason reason) {
		switch (reason) {
		case INVALID:
			return HttpStatus.BAD_REQUEST;
		case NOT_FOUND:
			return HttpStatus.NOT_FOUND;
		case NOT_READY:
		case BUSY:
			return HttpStatus.CONFLICT;
		case READER_ERROR:
			return HttpStatus.BAD_GATEWAY;
		case UNREACHABLE:
			return HttpStatus.SERVICE_UNAVAILABLE;
		default:
			return HttpStatus.INTERNAL_SERVER_ERROR;
		}
	}

	public static ResponseEntity<Map<String, String>> answer(RfidException e) {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("error", e.getMessage());
		body.put("reason", e.getReason().name());
		return ResponseEntity.status(statusOf(e.getReason())).body(body);
	}
}
