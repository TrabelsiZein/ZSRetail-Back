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

	/** Step 3: the channels, as written in comm_rule, comm_template and comm_message (lower case in the properties). */
	public static final String WHATSAPP = "WHATSAPP";
	public static final String SMS = "SMS";
	public static final String EMAIL = "EMAIL";
	public static final java.util.List<String> CHANNELS = java.util.Collections
			.unmodifiableList(java.util.Arrays.asList(WHATSAPP, SMS, EMAIL));

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

	/** Step 3: a payload read back (empty for none); an unreadable one throws. */
	public static Map<String, Object> values(String json) {
		if (json == null || json.trim().isEmpty()) {
			return new java.util.LinkedHashMap<>();
		}
		try {
			return PAYLOAD_MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
			});
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("payload not readable: " + e.getOriginalMessage(), e);
		}
	}
}
