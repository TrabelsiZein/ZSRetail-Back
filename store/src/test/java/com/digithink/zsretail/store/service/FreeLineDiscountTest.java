package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.store.dto.ProcessSaleRequestDTO;

/**
 * 3.1.0: the free-item line of a FREE_QUANTITY promotion stores its discount TTC, for the whole line, as every other
 * line does (SalesHeaderService.freeLineDiscountAmount, used by the sale and by a parked ticket completed).
 */
class FreeLineDiscountTest {

	@Test
	@DisplayName("2 bought, 1 free of an item at 10.000 HT and 19 %: the free line's discount is 11.900 TTC, not 10.000 HT")
	void freeLineDiscountIsTtc() {
		ProcessSaleRequestDTO.SaleLineDTO line = new ProcessSaleRequestDTO.SaleLineDTO();
		line.setUnitPrice(10.0);
		line.setVatPercent(19);
		line.setUnitPriceIncludingVat(11.9);
		line.setFreeQuantity(1);
		assertEquals(11.9, SalesHeaderService.freeLineDiscountAmount(line), 1e-9);

		line.setFreeQuantity(2);
		assertEquals(23.8, SalesHeaderService.freeLineDiscountAmount(line), 1e-9, "two free pieces");

		line.setUnitPriceIncludingVat(null);
		assertEquals(0.0, SalesHeaderService.freeLineDiscountAmount(line), 1e-9, "no TTC price sent: nothing, as before");
	}
}
