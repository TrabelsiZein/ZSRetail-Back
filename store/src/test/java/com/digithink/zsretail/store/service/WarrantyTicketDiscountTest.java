package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;

/**
 * 3.5.0, the warranty ticket: the sale's ticket discount on the covered amount, the returns screen's rule
 * (WarrantyService.ticketDiscountFactor). Plain JUnit.
 */
class WarrantyTicketDiscountTest {

	private static SalesLine line(double ttc) {
		SalesLine l = new SalesLine();
		l.setLineTotalIncludingVat(ttc);
		return l;
	}

	private static SalesHeader ticket(Double pct, Double amount, Double total, Double points) {
		SalesHeader h = new SalesHeader();
		h.setDiscountPercentage(pct);
		h.setDiscountAmount(amount);
		h.setTotalAmount(total);
		h.setLoyaltyDeductionAmount(points);
		return h;
	}

	@Test
	@DisplayName("The ticket's %; a ticket amount (its total over its lines' TTC); none (null, the lines never read); a line discount only: none")
	void factor() {
		AtomicInteger reads = new AtomicInteger();
		Supplier<List<SalesLine>> lines = () -> {
			reads.incrementAndGet();
			return Arrays.asList(line(100), line(50));
		};
		assertEquals(0.95, WarrantyService.ticketDiscountFactor(ticket(5.0, 7.5, 142.5, null), lines), 1e-12);
		assertEquals(0, reads.get(), "a percentage: the lines not read");
		assertEquals(0.9, WarrantyService.ticketDiscountFactor(ticket(null, 15.0, 135.0, null), lines), 1e-12);
		assertEquals(1, reads.get());
		assertNull(WarrantyService.ticketDiscountFactor(ticket(null, 0.0, 150.0, null), lines), "no ticket discount");
		assertNull(WarrantyService.ticketDiscountFactor(ticket(0.0, null, 150.0, null), lines));
		assertEquals(1, reads.get(), "no ticket discount: the lines not read");
		assertNull(WarrantyService.ticketDiscountFactor(null, lines));
	}

	@Test
	@DisplayName("A ticket paid partly with points: the ticket discount only (the points are a payment), as the returns screen")
	void points() {
		Supplier<List<SalesLine>> lines = () -> Arrays.asList(line(100), line(50));
		// 150 of goods, 5 % ticket discount (142.5), 20 paid in points: total 122.5
		assertEquals(ReturnHeaderService.loyaltyHeaderFactor(ticket(5.0, 7.5, 122.5, 20.0), lines.get()),
				WarrantyService.ticketDiscountFactor(ticket(5.0, 7.5, 122.5, 20.0), lines), 1e-12);
		assertEquals(0.95, WarrantyService.ticketDiscountFactor(ticket(5.0, 7.5, 122.5, 20.0), lines), 1e-9);
		// points only, no ticket discount: none
		assertNull(WarrantyService.ticketDiscountFactor(ticket(null, 0.0, 130.0, 20.0), lines));
	}
}
