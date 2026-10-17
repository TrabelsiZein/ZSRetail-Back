package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;

/**
 * Test log I-07 (3.1.0): a ticket's amounts, lines and payments are stored to the millime, half up (the entities'
 * roundAmounts, run by JPA on every save); paid vs total and the change are taken on the rounded values, with the till's
 * tolerance of 0.010 (SalesHeaderService.checkPaidCoversTotal, changeAmount). The examples are the log's.
 */
class MoneyRoundingTest {

	@Test
	@DisplayName("ZS01261017002: total 50.9558, VAT 8.1358 stored 50.956 and 8.136; the line 42.6258 / 4.7362 to the millime")
	void ticketAndLineAmounts() {
		SalesHeader ticket = new SalesHeader();
		ticket.setSubtotal(42.82);
		ticket.setTaxAmount(8.1358);
		ticket.setDiscountAmount(0.0);
		ticket.setDiscountPercentage(12.3456);
		ticket.setTotalAmount(50.9558);
		ticket.setPaidAmount(50.956);
		ticket.setChangeAmount(2.0000000000663931E-4);
		ticket.roundAmounts();
		assertEquals(42.820, ticket.getSubtotal(), 0.0);
		assertEquals(8.136, ticket.getTaxAmount(), 0.0);
		assertEquals(50.956, ticket.getTotalAmount(), 0.0);
		assertEquals(50.956, ticket.getPaidAmount(), 0.0);
		assertEquals(0.0, ticket.getChangeAmount(), 0.0, "0.0002 of change is no change");
		assertEquals(12.3456, ticket.getDiscountPercentage(), 0.0, "a percentage is not an amount");
		assertNull(ticket.getLoyaltyDeductionAmount(), "null stays null");

		SalesLine line = new SalesLine();
		line.setUnitPrice(19.9);
		line.setUnitPriceIncludingVat(23.6809);
		line.setLineTotal(35.82);
		line.setVatAmount(6.8058);
		line.setLineTotalIncludingVat(42.6258);
		line.setDiscountAmount(4.7362);
		line.roundAmounts();
		assertEquals(42.626, line.getLineTotalIncludingVat(), 0.0);
		assertEquals(4.736, line.getDiscountAmount(), 0.0);
		assertEquals(6.806, line.getVatAmount(), 0.0);
		assertEquals(23.6809, line.getUnitPriceIncludingVat(), 0.0, "a unit price keeps its precision");
		assertEquals(19.9, line.getUnitPrice(), 0.0);

		Payment voucher = new Payment();
		voucher.setTotalAmount(21.3129);
		voucher.roundAmounts();
		assertEquals(21.313, voucher.getTotalAmount(), 0.0);
	}

	@Test
	@DisplayName("1.0005 stored as 1.000499... rounds up, half up")
	void halfUp() {
		Payment payment = new Payment();
		payment.setTotalAmount(1.0005);
		payment.roundAmounts();
		assertEquals(1.001, payment.getTotalAmount(), 0.0);
	}

	@Test
	@DisplayName("Paid vs total on rounded values, the till's tolerance 0.010: ZS01261017004 and Store A's 2-decimal payments pass")
	void paidCoversTotal() {
		// ZS01261017004: voucher 21.3129 + cash 2.368 for 23.681, 0.0001 short unrounded, paid 23.681 rounded
		assertDoesNotThrow(() -> SalesHeaderService.checkPaidCoversTotal(21.313 + 2.368, 23.681));
		// Store A SHOWROOM-S260519008: total 314.141 paid 314.14 (the till's 2 decimals), 0.001 short
		assertDoesNotThrow(() -> SalesHeaderService.checkPaidCoversTotal(314.14, 314.141));
		assertDoesNotThrow(() -> SalesHeaderService.checkPaidCoversTotal(50.95, 50.96), "0.010 short: the till accepts it");
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> SalesHeaderService.checkPaidCoversTotal(50.945, 50.956));
		assertEquals("Paid amount (50.945) is less than the ticket total (50.956)", refused.getMessage());
	}

	@Test
	@DisplayName("The change: rounded paid - rounded total, cash only, never 0.0002 or 3.5E-15")
	void change() {
		assertEquals(0.0, SalesHeaderService.changeAmount(50.956, 50.9558, true), 0.0, "ZS01261017002");
		assertEquals(0.0, SalesHeaderService.changeAmount(23.681, 23.680999999999997, true), 0.0, "ZS01261017005");
		assertEquals(24.179, SalesHeaderService.changeAmount(50.0, 25.821, true), 0.0, "ZS01261017003");
		assertEquals(0.0, SalesHeaderService.changeAmount(50.0, 25.821, false), 0.0, "no cash, no change");
		assertEquals(0.0, SalesHeaderService.changeAmount(314.14, 314.141, true), 0.0, "short: no change");
	}
}
