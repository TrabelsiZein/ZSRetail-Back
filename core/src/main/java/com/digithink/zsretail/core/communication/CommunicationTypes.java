package com.digithink.zsretail.core.communication;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 3.3.0: the texts of the communication module's rows (plain texts, not enums: a row received from a store of another
 * version stays readable) and the JSON of an event's payload. See docs/modules/communication.md.
 */
public final class CommunicationTypes {

	public static final String TICKET_VALIDATED = "TICKET_VALIDATED";
	public static final String MEMBER_CREATED = "MEMBER_CREATED";

	public static final String SALES_HEADER = "SALES_HEADER";
	public static final String LOYALTY_MEMBER = "LOYALTY_MEMBER";

	/** The payloads: dates as ISO strings (2026-11-20T16:18:46), the keys in the order written. */
	private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	private CommunicationTypes() {
	}

	public static String json(Map<String, Object> values) {
		try {
			return PAYLOAD_MAPPER.writeValueAsString(values);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("payload not written: " + e.getOriginalMessage(), e);
		}
	}
}
