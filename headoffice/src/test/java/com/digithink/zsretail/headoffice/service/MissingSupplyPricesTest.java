package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.Item;

/**
 * Test log I-35 (3.1.0): a BL sent with items that have no supply price for its store is sent anyway, and its answer
 * lists their codes (HoSupplyInvoiceService.missingCodes, read by HoDeliveryService.validate) for the page's warning.
 */
class MissingSupplyPricesTest {

	@Test
	@DisplayName("The items without a price, by code, once each, in line order; the priced ones are not listed")
	void missingCodesInLineOrder() {
		Item priced = item(1L, "31ZA00~001");
		Item noPrice = item(2L, "14ZDU05~001");
		Item another = item(3L, "31ZA01~001");
		Map<Long, Double> prices = new HashMap<>();
		prices.put(1L, 16.5);

		assertEquals(Arrays.asList("14ZDU05~001", "31ZA01~001"),
				HoSupplyInvoiceService.missingCodes(Arrays.asList(noPrice, priced, another, noPrice), prices));
	}

	@Test
	@DisplayName("Every item priced: nothing to warn about; no price at all (I-35's head office): every item")
	void allOrNothing() {
		Item a = item(1L, "A");
		Item b = item(2L, "B");
		Map<Long, Double> prices = new HashMap<>();
		prices.put(1L, 1.0);
		prices.put(2L, 0.0); // a price of 0 is a price
		assertTrue(HoSupplyInvoiceService.missingCodes(Arrays.asList(a, b), prices).isEmpty());

		assertEquals(Arrays.asList("A", "B"), HoSupplyInvoiceService.missingCodes(Arrays.asList(a, b),
				Collections.emptyMap()));
	}

	private static Item item(Long id, String code) {
		Item item = new Item();
		item.setId(id);
		item.setItemCode(code);
		return item;
	}
}
