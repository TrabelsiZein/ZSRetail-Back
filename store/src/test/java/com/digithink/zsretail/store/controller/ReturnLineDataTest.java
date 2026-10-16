package com.digithink.zsretail.store.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ReturnLine;
import com.digithink.zsretail.core.model.SalesLine;

/**
 * 2.2.2: a return line carries the VAT rate of the line sold, so the voucher prints 19% for 0.2 of an item at 19%, never
 * the 19.02% worked back from the rounded amounts (2.682 excluding VAT, 3.192 including VAT). Item variants (ZEN), step
 * 11c: the item of a return screen or voucher line carries its variant description. Plain JUnit.
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

	@Test
	@DisplayName("Item variants (ZEN), step 11c: the voucher line's item carries its variant description (null without variant)")
	void voucherItemKeys() {
		ReturnLine variant = line(19, 19);
		variant.getItem().setVariantDescription("S / Noir / 1er Choix");
		Map<String, Object> item = itemOf(ReturnHeaderAPI.returnLineData(variant));
		assertEquals(new HashSet<>(Arrays.asList("id", "name", "itemCode", "variantDescription")), item.keySet());
		assertEquals("S / Noir / 1er Choix", item.get("variantDescription"));
		assertEquals("BOUGIE", item.get("name"));

		Map<String, Object> plain = itemOf(ReturnHeaderAPI.returnLineData(line(19, 19)));
		assertTrue(plain.containsKey("variantDescription"));
		assertNull(plain.get("variantDescription"), "EMTOP, Happyness: null, the name shown alone");

		ReturnLine noItem = line(19, 19);
		noItem.setItem(null);
		assertFalse(ReturnHeaderAPI.returnLineData(noItem).containsKey("item"), "no item: no key, as before");
	}

	@Test
	@DisplayName("Step 11c: a sold line that cannot be returned lists its item with the variant description too")
	void notReturnableItemKeys() throws Exception {
		Method notReturnable = ReturnHeaderAPI.class.getDeclaredMethod("notReturnable", SalesLine.class, BigDecimal.class);
		notReturnable.setAccessible(true);
		SalesLine sold = new SalesLine();
		sold.setItem(line(19, 19).getItem());
		sold.getItem().setVariantDescription("M / Blanc");
		@SuppressWarnings("unchecked")
		Map<String, Object> listed = (Map<String, Object>) notReturnable.invoke(null, sold, BigDecimal.ZERO);
		assertEquals("M / Blanc", itemOf(listed).get("variantDescription"));

		sold.setItem(null);
		@SuppressWarnings("unchecked")
		Map<String, Object> withoutItem = (Map<String, Object>) notReturnable.invoke(null, sold, BigDecimal.ZERO);
		assertTrue(withoutItem.containsKey("item"));
		assertNull(withoutItem.get("item"), "no item: null, as before");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> itemOf(Map<String, Object> lineData) {
		return (Map<String, Object>) lineData.get("item");
	}
}
