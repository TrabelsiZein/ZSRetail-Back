package com.digithink.zsretail.store.holink.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.store.holink.dto.StoreOrderInputDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.1.1, quantity ≤ available: the body of POST /store-order/draft/lines tells the variants window (availableQty sent,
 * even null: no limit) from the cart (none sent: the line keeps its own).
 */
class StoreOrderInputDTOTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	@DisplayName("availableQty sent with a figure, sent as null, or not sent (the cart)")
	void availabilitySent() throws Exception {
		StoreOrderInputDTO window = JSON.readValue("{\"itemId\":1,\"quantity\":2,\"availableQty\":5}", StoreOrderInputDTO.class);
		assertTrue(window.isAvailableQtySent());
		assertEquals(0, new BigDecimal("5").compareTo(window.getAvailableQty()));

		StoreOrderInputDTO unknown = JSON.readValue("{\"itemId\":1,\"quantity\":2,\"availableQty\":null}", StoreOrderInputDTO.class);
		assertTrue(unknown.isAvailableQtySent(), "null sent: no limit, replacing the line's");
		assertNull(unknown.getAvailableQty());

		StoreOrderInputDTO cart = JSON.readValue("{\"itemId\":1,\"quantity\":2}", StoreOrderInputDTO.class);
		assertFalse(cart.isAvailableQtySent(), "none sent: the line keeps its own");
		assertFalse(JSON.writeValueAsString(cart).contains("availableQtySent"), "the flag is not part of the body");
	}
}
