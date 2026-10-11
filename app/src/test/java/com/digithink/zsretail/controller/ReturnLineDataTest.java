package com.digithink.zsretail.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ReturnLine;
import com.digithink.zsretail.model.SalesLine;

/**
 * 2.2.2: a return line carries the VAT rate of the line sold, so the voucher prints 19% for 0.2 of an item at 19%, never
 * the 19.02% worked back from the rounded amounts (2.682 excluding VAT, 3.192 including VAT). Plain JUnit.
 */
class ReturnLineDataTest {

	private static ReturnLine line(Integer soldRate, Integer itemRate) {
		Item item = new Item();
		item.setId(12L);
		item.setItemCode("3801701010009");
		item.setName("BOUGIE");
		item.setDefaultVAT(itemRate);
		SalesLine sold = new SalesLine();
		sold.setVatPercent(soldRate);
		ReturnLine line = new ReturnLine();
		line.setItem(item);
		line.setOriginalSalesLine(sold);
		line.setQuantity(new BigDecimal("0.200"));
		line.setUnitPrice(13.41);
		line.setUnitPriceIncludingVat(15.9579);
		line.setLineTotal(2.682);
		line.setLineTotalIncludingVat(3.192);
		return line;
	}

	@Test
	@DisplayName("The rate of the line sold, whatever the item's rate is today")
	void soldLineRate() {
		Map<String, Object> data = ReturnHeaderAPI.returnLineData(line(19, 7));
		assertEquals(19, data.get("vatPercent"));
		assertEquals(new BigDecimal("0.2"), data.get("quantity"));
		assertEquals(3.192, data.get("lineTotalIncludingVat"));
		@SuppressWarnings("unchecked")
		Map<String, Object> item = (Map<String, Object>) data.get("item");
		assertEquals("3801701010009", item.get("itemCode"));
	}

	@Test
	@DisplayName("A sold line without a rate: the item's rate; neither: null (the voucher then works it out as before)")
	void fallbacks() {
		assertEquals(19, ReturnHeaderAPI.returnLineData(line(null, 19)).get("vatPercent"));
		assertNull(ReturnHeaderAPI.returnLineData(line(null, null)).get("vatPercent"));
	}
}
