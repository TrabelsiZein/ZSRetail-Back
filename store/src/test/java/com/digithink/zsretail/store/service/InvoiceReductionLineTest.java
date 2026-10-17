package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.InvoiceHeader;
import com.digithink.zsretail.core.model.InvoiceLine;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;

/**
 * Test log I-63 (3.1.0): an invoice made from tickets adds one "Remise" line per ticket with a ticket-wide reduction
 * (InvoiceService.reductionLine): what was paid (I-48) minus the ticket's lines, so the invoice adds up to the tickets.
 */
class InvoiceReductionLineTest {

	@Test
	@DisplayName("Cart promotion -10 % on 4 x 23.681 (ZS01261017013): Remise -7.960 HT, -1.512 VAT, -9.472 TTC")
	void cartPromotion() {
		SalesLine line = line(79.6, 15.124, 94.724);
		// the header as stored before I-48 (the till's amounts before the reduction): same Remise line
		SalesHeader ticket = ticket("ZS01261017013", 79.6, 15.124, 9.4724, null, 85.2516);

		InvoiceLine remise = InvoiceService.reductionLine(new InvoiceHeader(), ticket, Collections.singletonList(line));

		assertEquals("Remise ticket ZS01261017013", remise.getLineDescription());
		assertEquals(1, remise.getQuantity());
		assertEquals(-7.960, remise.getSubtotal(), 1e-9);
		assertEquals(-1.512, remise.getTaxAmount(), 1e-9);
		assertEquals(-9.472, remise.getTotalAmount(), 1e-9);
		assertEquals(-9.472, remise.getLineTotalIncludingVat(), 1e-9);
		assertNull(remise.getVatPercent(), "spread over the ticket's rates");
		// the invoice: the line plus the Remise = what was paid, 71.640 + 13.612 = 85.252
		assertEquals(71.640, line.getLineTotal() + remise.getSubtotal(), 1e-9);
		assertEquals(13.612, line.getVatAmount() + remise.getTaxAmount(), 1e-9);
		assertEquals(85.252, line.getLineTotalIncludingVat() + remise.getTotalAmount(), 1e-9);
		assertEquals(79.6, line.getLineTotal(), 0.0, "the ticket's line is not touched");

		// stored since I-48 (header 71.640 + 13.612): the same line
		SalesHeader since = ticket("ZS01261017013", 71.64, 13.612, 9.472, null, 85.252);
		InvoiceLine same = InvoiceService.reductionLine(new InvoiceHeader(), since, Collections.singletonList(line));
		assertEquals(-7.960, same.getSubtotal(), 1e-9);
		assertEquals(-1.512, same.getTaxAmount(), 1e-9);
		assertEquals(-9.472, same.getTotalAmount(), 1e-9);
	}

	@Test
	@DisplayName("110 points (1.100 TND) on 23.681 (ZS01261017012): Remise -0.924 HT, -0.176 VAT, -1.100 TTC")
	void loyaltyPoints() {
		List<SalesLine> lines = Collections.singletonList(line(19.9, 3.781, 23.681));
		InvoiceLine remise = InvoiceService.reductionLine(new InvoiceHeader(),
				ticket("ZS01261017012", 19.9, 3.781, 0.0, 1.1, 22.581), lines);

		assertEquals(-0.924, remise.getSubtotal(), 1e-9);
		assertEquals(-0.176, remise.getTaxAmount(), 1e-9);
		assertEquals(-1.100, remise.getTotalAmount(), 1e-9);
	}

	@Test
	@DisplayName("No ticket-wide reduction: no Remise line, the invoice of before")
	void noReduction() {
		assertNull(InvoiceService.reductionLine(new InvoiceHeader(),
				ticket("ZS01261017014", 16.915, 3.214, 0.0, null, 20.129),
				Collections.singletonList(line(16.915, 3.214, 20.129))));
		assertNull(InvoiceService.reductionLine(new InvoiceHeader(),
				ticket("ZS01261017001", 14.0, 2.66, null, null, 16.66),
				Collections.singletonList(line(14.0, 2.66, 16.66))));
	}

	private static SalesLine line(double ht, double vat, double ttc) {
		SalesLine line = new SalesLine();
		line.setLineTotal(ht);
		line.setVatAmount(vat);
		line.setLineTotalIncludingVat(ttc);
		line.setVatPercent(19);
		return line;
	}

	private static SalesHeader ticket(String number, double subtotal, double tax, Double discount, Double points,
			double total) {
		SalesHeader ticket = new SalesHeader();
		ticket.setSalesNumber(number);
		ticket.setSubtotal(subtotal);
		ticket.setTaxAmount(tax);
		ticket.setDiscountAmount(discount);
		ticket.setLoyaltyDeductionAmount(points);
		ticket.setTotalAmount(total);
		return ticket;
	}
}
