package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;

/**
 * I-48 (3.1.0): a ticket-wide reduction (ticket discount, cart promotion, loyalty points) gives the ticket the HT and VAT
 * of what was paid, spread over the rates as the ticket X / Z spread it (SalesHeaderService.applyTicketReductionToTotals);
 * a ticket without one keeps the till's values. The lines are not touched.
 */
class TicketReductionTotalsTest {

	@Test
	@DisplayName("Cart promotion -10 % on 4 x 23.681 (ZS01261017013): 85.252 = 71.640 HT + 13.612 VAT")
	void cartPromotion() {
		SalesLine line = line(94.724, 19);
		SalesHeader ticket = ticket(79.6, 15.124, 9.4724, null, 85.2516);

		assertTrue(SalesHeaderService.applyTicketReductionToTotals(ticket, Collections.singletonList(line)));
		assertEquals(71.640, ticket.getSubtotal(), 1e-9);
		assertEquals(13.612, ticket.getTaxAmount(), 1e-9);
		assertEquals(94.724, line.getLineTotalIncludingVat(), 1e-9, "the line keeps its amount before the reduction");
	}

	@Test
	@DisplayName("Ticket discount 5 TND on 30.821 (ZS01261017003): 25.821 = 21.698 HT + 4.123 VAT")
	void ticketDiscount() {
		SalesHeader ticket = ticket(25.9, 4.921, 5.0, null, 25.821);

		assertTrue(SalesHeaderService.applyTicketReductionToTotals(ticket, Collections.singletonList(line(30.821, 19))));
		assertEquals(21.698, ticket.getSubtotal(), 1e-9);
		assertEquals(4.123, ticket.getTaxAmount(), 1e-9);
	}

	@Test
	@DisplayName("110 points (1.100 TND) on 23.681 (ZS01261017012): 22.581 = 18.976 HT + 3.605 VAT")
	void loyaltyPoints() {
		SalesHeader ticket = ticket(19.9, 3.781, 0.0, 1.1, 22.581);

		assertTrue(SalesHeaderService.applyTicketReductionToTotals(ticket, Collections.singletonList(line(23.681, 19))));
		assertEquals(18.976, ticket.getSubtotal(), 1e-9);
		assertEquals(3.605, ticket.getTaxAmount(), 1e-9);
	}

	@Test
	@DisplayName("Two rates and a fiscal stamp, -10 %: each rate in proportion to its TTC, the stamp kept in the subtotal")
	void twoRatesAndStamp() {
		Item stampItem = new Item();
		stampItem.setItemCode("TAX_STAMP");
		SalesLine stamp = line(0.1, 0);
		stamp.setItem(stampItem);
		List<SalesLine> lines = Arrays.asList(line(11.9, 19), line(10.7, 7), stamp);
		// goods 22.600, -2.260, stamp 0.100: 19 % 10.710 = 9.000 + 1.710; 7 % 9.630 = 9.000 + 0.630
		SalesHeader ticket = ticket(19.1, 2.6, 2.26, null, 20.44);

		assertTrue(SalesHeaderService.applyTicketReductionToTotals(ticket, lines));
		assertEquals(2.340, ticket.getTaxAmount(), 1e-9);
		assertEquals(18.100, ticket.getSubtotal(), 1e-9, "18.000 HT of goods + 0.100 stamp");
	}

	@Test
	@DisplayName("Rounding: the remainder goes to one rate, so HT + VAT = the total to the millime")
	void roundingRemainder() {
		// 10.001 over two equal TTC: 5.0005 each, so the two rates rounded to the millime leave a 0.001 remainder
		SalesHeader ticket = ticket(8.0, 1.0, 0.009, null, 10.001);

		assertTrue(SalesHeaderService.applyTicketReductionToTotals(ticket,
				Arrays.asList(line(5.005, 19), line(5.005, 7))));
		assertEquals(10.001, ticket.getSubtotal() + ticket.getTaxAmount(), 1e-9);
	}

	@Test
	@DisplayName("No ticket-wide reduction: the till's HT and VAT stay exactly as sent")
	void noReductionKeepsTillValues() {
		SalesHeader ticket = ticket(16.915, 3.21385, 0.0, null, 20.12885);

		assertFalse(SalesHeaderService.applyTicketReductionToTotals(ticket, Collections.singletonList(line(20.12885, 19))));
		assertEquals(16.915, ticket.getSubtotal(), 0.0);
		assertEquals(3.21385, ticket.getTaxAmount(), 0.0);

		SalesHeader noFields = ticket(16.915, 3.21385, null, null, 20.12885);
		assertFalse(SalesHeaderService.applyTicketReductionToTotals(noFields, Collections.singletonList(line(20.12885, 19))));
		assertEquals(16.915, noFields.getSubtotal(), 0.0);
		assertEquals(3.21385, noFields.getTaxAmount(), 0.0);
	}

	private static SalesLine line(double ttc, int rate) {
		SalesLine line = new SalesLine();
		line.setLineTotalIncludingVat(ttc);
		line.setVatPercent(rate);
		return line;
	}

	private static SalesHeader ticket(double subtotal, double tax, Double discount, Double points, double total) {
		SalesHeader ticket = new SalesHeader();
		ticket.setSubtotal(subtotal);
		ticket.setTaxAmount(tax);
		ticket.setDiscountAmount(discount);
		ticket.setLoyaltyDeductionAmount(points);
		ticket.setTotalAmount(total);
		return ticket;
	}
}
