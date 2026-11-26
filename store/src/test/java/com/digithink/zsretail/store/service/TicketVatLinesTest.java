package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.VatLine;

/**
 * 3.5.0, ticket templates step 2: a ticket's VAT by rate for the block "TVA par taux" (SessionReportService.ticketVatLines):
 * the tickets X / Z's spread, the stamp left out, adding up to the ticket's printed VAT (a millime per line at most moved
 * to the largest rate).
 */
class TicketVatLinesTest {

	@Test
	@DisplayName("Two rates, no reduction: each rate's HT, VAT and TTC; they add up to the ticket's VAT")
	void twoRates() {
		List<VatLine> vat = SessionReportService.ticketVatLines(ticket(2.6, 22.6),
				Arrays.asList(line(11.9, 19), line(10.7, 7)));
		assertEquals(2, vat.size());
		assertLine(vat.get(0), 7, 10.0, 0.7, 10.7);
		assertLine(vat.get(1), 19, 10.0, 1.9, 11.9);
	}

	@Test
	@DisplayName("The till's VAT rounded line by line (3 x 0.160) differs by a millime: it goes to the largest rate, HT the"
			+ " other way, the TTC unchanged")
	void millimeToTheLargestRate() {
		List<VatLine> vat = SessionReportService.ticketVatLines(ticket(0.48, 3.0),
				Arrays.asList(line(1.0, 19), line(1.0, 19), line(1.0, 19)));
		assertEquals(1, vat.size());
		assertLine(vat.get(0), 19, 2.52, 0.48, 3.0);
	}

	@Test
	@DisplayName("The fiscal stamp is not a rate; a ticket-wide reduction spread over the rates (I-48's VAT)")
	void stampAndReduction() {
		Item stampItem = new Item();
		stampItem.setItemCode("TAX_STAMP");
		SalesLine stamp = line(0.1, 0);
		stamp.setItem(stampItem);
		List<SalesLine> lines = Arrays.asList(line(11.9, 19), line(10.7, 7), stamp);
		SalesHeader ticket = ticket(2.6, 20.44);
		ticket.setDiscountAmount(2.26);
		assertTrue(SalesHeaderService.applyTicketReductionToTotals(ticket, lines));
		List<VatLine> vat = SessionReportService.ticketVatLines(ticket, lines);
		assertEquals(2, vat.size(), "no line for the stamp");
		assertLine(vat.get(0), 7, 9.0, 0.63, 9.63);
		assertLine(vat.get(1), 19, 9.0, 1.71, 10.71);
		assertEquals(ticket.getTaxAmount(), vat.get(0).getVatAmount() + vat.get(1).getVatAmount(), 1e-9);
	}

	@Test
	@DisplayName("A VAT far from the spread (old data) is left as computed; no lines: no rates")
	void farAndEmpty() {
		List<VatLine> vat = SessionReportService.ticketVatLines(ticket(5.0, 22.6),
				Arrays.asList(line(11.9, 19), line(10.7, 7)));
		assertLine(vat.get(1), 19, 10.0, 1.9, 11.9);
		assertTrue(SessionReportService.ticketVatLines(ticket(0.0, 0.0), Collections.emptyList()).isEmpty());
	}

	private static void assertLine(VatLine line, int rate, double ht, double vat, double ttc) {
		assertEquals(rate, line.getRate());
		assertEquals(ht, line.getBaseHT(), 1e-9, "HT");
		assertEquals(vat, line.getVatAmount(), 1e-9, "VAT");
		assertEquals(ttc, line.getTotalTTC(), 1e-9, "TTC");
	}

	private static SalesLine line(double ttc, int rate) {
		SalesLine line = new SalesLine();
		line.setLineTotalIncludingVat(ttc);
		line.setVatPercent(rate);
		return line;
	}

	private static SalesHeader ticket(double tax, double total) {
		SalesHeader ticket = new SalesHeader();
		ticket.setTaxAmount(tax);
		ticket.setTotalAmount(total);
		ticket.setSubtotal(total - tax);
		return ticket;
	}
}
