package com.digithink.zsretail.headoffice.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.TreeSet;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.headoffice.dto.HeadOfficePingDTO;
import com.digithink.zsretail.headoffice.model.Store;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Head office plan, task 1.3: GET /ho/ping answers the calling store's code and the server time, as a DTO (never
 * the Store principal, which carries the key hash), and writes nothing. Plain JUnit, no Spring context.
 */
class HeadOfficePingAPITest {

	@Test
	@DisplayName("Ping: the store code and the server time (ISO-8601, milliseconds, offset); the store is not changed")
	void ping() throws Exception {
		Store store = new Store();
		store.setId(1L);
		store.setCode("RS01");
		store.setName("Store Sousse");
		store.setApiKeyHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

		OffsetDateTime before = OffsetDateTime.now();
		HeadOfficePingDTO answer = new HeadOfficePingAPI().ping(store);

		assertEquals("RS01", answer.getStoreCode());
		assertTrue(answer.getServerTime().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}([+-]\\d{2}:\\d{2}|Z)"),
				answer.getServerTime());
		OffsetDateTime serverTime = OffsetDateTime.parse(answer.getServerTime());
		assertTrue(Duration.between(before, serverTime).abs().getSeconds() < 5, answer.getServerTime());
		assertNull(store.getLastContact());
		assertNull(store.getAppVersion());

		String json = new ObjectMapper().writeValueAsString(answer);
		assertEquals(new TreeSet<>(Arrays.asList("storeCode", "serverTime")), new TreeSet<>(new JSONObject(json).keySet()));
		assertFalse(json.contains(store.getApiKeyHash()), json);
	}
}
