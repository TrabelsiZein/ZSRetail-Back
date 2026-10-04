package com.digithink.zsretail.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Head office plan, task 9.4a: the franchise fields left the entities (their columns stay), but older screens still send
 * them in the item and customer payloads. They are ignored, never refused: even a mapper that fails on unknown
 * properties reads the payload, and the answers no longer carry them.
 */
class LegacyFranchiseFieldsTest {

	/** Stricter than Spring Boot's mapper: an unknown property would fail. */
	private final ObjectMapper strict = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	@Test
	@DisplayName("An item payload with franchiseSalesPrice and fromFranchiseAdmin is read; the answer has neither")
	void itemPayload() throws Exception {
		Item item = strict.readValue("{\"itemCode\":\"B001\",\"name\":\"Bread\",\"unitPrice\":2.5,"
				+ "\"franchiseSalesPrice\":3.0,\"fromFranchiseAdmin\":true}", Item.class);
		assertEquals("B001", item.getItemCode());
		assertEquals(Double.valueOf(2.5), item.getUnitPrice());
		JsonNode answer = strict.valueToTree(item);
		assertFalse(answer.has("franchiseSalesPrice"));
		assertFalse(answer.has("fromFranchiseAdmin"));
	}

	@Test
	@DisplayName("A customer payload with defaultLocation is read; the answer does not carry it")
	void customerPayload() throws Exception {
		Customer customer = strict.readValue("{\"customerCode\":\"C001\",\"name\":\"Client\",\"defaultLocation\":\"STORE-B\"}",
				Customer.class);
		assertEquals("C001", customer.getCustomerCode());
		assertFalse(strict.valueToTree(customer).has("defaultLocation"));
	}
}
