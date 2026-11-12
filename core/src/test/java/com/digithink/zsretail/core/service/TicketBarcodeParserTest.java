package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.AcceptedTicket;
import com.digithink.zsretail.core.model.BarcodeLayout;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodBehaviour;
import com.digithink.zsretail.core.service.TicketBarcodeParser.Reading;
import com.digithink.zsretail.core.service.TicketBarcodeParser.Status;

/**
 * 3.2.0, step 3: TicketBarcodeParser on real tickets (Servimax Repas 7.000, Sodexo Cadeau 10.000) and the refusals; the
 * date is given, never today's.
 */
class TicketBarcodeParserTest {

	private static final String SERVIMAX = "0310070002500002897009796069";
	private static final String SODEXO = "0420100002508037484068000487";

	private final PaymentMethod meal = method(1L, "Ticket Restaurant");
	private final PaymentMethod gift = method(2L, "Chèque Cadeau");

	private static PaymentMethod method(Long id, String name) {
		PaymentMethod method = new PaymentMethod();
		method.setId(id);
		method.setName(name);
		method.setBehaviour(PaymentMethodBehaviour.SCANNED_TICKETS);
		return method;
	}

	private static AcceptedTicket line(long id, PaymentMethod method, String code, String issuer, int deduction) {
		AcceptedTicket line = new AcceptedTicket();
		line.setId(id);
		line.setPaymentMethod(method);
		line.setTicketCode(code);
		line.setIssuerName(issuer);
		line.setDeductionPercent(BigDecimal.valueOf(deduction));
		line.setValidityMonths(1);
		line.setLayout(BarcodeLayout.standard());
		line.setActive(true);
		return line;
	}

	private List<AcceptedTicket> lines() {
		return new ArrayList<>(Arrays.asList(line(31, meal, "031", "Servimax Repas", 10),
				line(42, gift, "042", "Sodexo Cadeau", 0)));
	}

	private static BigDecimal tnd(String value) {
		return new BigDecimal(value);
	}

	@Test
	@DisplayName("Servimax Repas: 031, 7.000, 2025, its serial, 10 % off: 6.300; valid on 2026-01-31")
	void servimax() {
		Reading reading = TicketBarcodeParser.read(SERVIMAX, LocalDate.of(2026, 1, 31), lines(), null);
		assertEquals(Status.OK, reading.getStatus());
		assertEquals(31L, reading.getTicketId());
		assertEquals(1L, reading.getPaymentMethodId());
		assertEquals("Ticket Restaurant", reading.getPaymentMethodName());
		assertEquals("Servimax Repas", reading.getIssuerName());
		assertEquals("031", reading.getTicketCode());
		assertEquals(tnd("7.000"), reading.getFaceValue());
		assertEquals(2025, reading.getYear());
		assertEquals("00002897009796069", reading.getSerial());
		assertEquals(LocalDate.of(2026, 1, 31), reading.getValidUntil());
		assertEquals(tnd("6.300"), reading.getCountedValue());
	}

	@Test
	@DisplayName("Servimax Repas on 2026-02-01: EXPIRED, the reading still filled")
	void servimaxExpired() {
		Reading reading = TicketBarcodeParser.read(SERVIMAX, LocalDate.of(2026, 2, 1), lines(), null);
		assertEquals(Status.EXPIRED, reading.getStatus());
		assertEquals(tnd("6.300"), reading.getCountedValue());
	}

	@Test
	@DisplayName("Sodexo Cadeau: 042, 10.000, 2025, 0 %: counted 10.000")
	void sodexo() {
		Reading reading = TicketBarcodeParser.read(SODEXO, LocalDate.of(2026, 1, 15), lines(), 2L);
		assertEquals(Status.OK, reading.getStatus());
		assertEquals("Sodexo Cadeau", reading.getIssuerName());
		assertEquals(tnd("10.000"), reading.getFaceValue());
		assertEquals(2025, reading.getYear());
		assertEquals("08037484068000487", reading.getSerial());
		assertEquals(tnd("10.000"), reading.getCountedValue());
	}

	@Test
	@DisplayName("Refusals: letters or empty, an unknown prefix, a wrong length (with the length expected)")
	void refusals() {
		LocalDate day = LocalDate.of(2026, 1, 1);
		assertEquals(Status.NOT_DIGITS, TicketBarcodeParser.read("03100700025000028970097960A9", day, lines(), null).getStatus());
		assertEquals(Status.NOT_DIGITS, TicketBarcodeParser.read("  ", day, lines(), null).getStatus());
		assertEquals(Status.NOT_DIGITS, TicketBarcodeParser.read(null, day, lines(), null).getStatus());
		assertEquals(Status.UNKNOWN_TICKET, TicketBarcodeParser.read("0990070002500002897009796069", day, lines(), null).getStatus());
		Reading shortOne = TicketBarcodeParser.read("031007000250000289700979606", day, lines(), null);
		assertEquals(Status.WRONG_LENGTH, shortOne.getStatus());
		assertEquals(28, shortOne.getExpectedLength());
		assertEquals("Servimax Repas", shortOne.getIssuerName());
		assertNull(shortOne.getFaceValue());
		assertEquals(Status.OK, TicketBarcodeParser.read(" " + SERVIMAX + " ", day, lines(), null).getStatus(), "trimmed");
	}

	@Test
	@DisplayName("An inactive line: LINE_INACTIVE; a line of another method than the one expected: OTHER_METHOD")
	void inactiveAndOtherMethod() {
		List<AcceptedTicket> lines = lines();
		lines.get(0).setActive(false);
		LocalDate day = LocalDate.of(2026, 1, 1);
		assertEquals(Status.LINE_INACTIVE, TicketBarcodeParser.read(SERVIMAX, day, lines, null).getStatus());
		Reading other = TicketBarcodeParser.read(SODEXO, day, lines(), 1L);
		assertEquals(Status.OTHER_METHOD, other.getStatus());
		assertEquals("Chèque Cadeau", other.getPaymentMethodName());
	}

	@Test
	@DisplayName("The longest prefix wins (lines a hand-edited table could hold); a shorter barcode length is apart")
	void longestPrefix() {
		List<AcceptedTicket> lines = lines();
		AcceptedTicket longer = line(310, meal, "0310", "Servimax Repas spécial", 0);
		// a 4-digit code: its fields after position 4 (the default layout's face value at 4 would overlap the code)
		longer.setLayout(new BarcodeLayout(28, 5, 6, 11, 2, 13, 16));
		lines.add(longer);
		assertEquals("0310", TicketBarcodeParser.read(SERVIMAX, LocalDate.of(2026, 1, 1), lines, null).getTicketCode());
		// a line whose layout cannot read its own code is passed over: the shorter code reads the barcode
		longer.setLayout(BarcodeLayout.standard());
		assertEquals("031", TicketBarcodeParser.read(SERVIMAX, LocalDate.of(2026, 1, 1), lines, null).getTicketCode());
		AcceptedTicket shortLayout = line(5, meal, "05", "Court", 0);
		shortLayout.setLayout(new BarcodeLayout(12, 3, 5, 8, 2, 10, 3));
		lines.add(shortLayout);
		Reading reading = TicketBarcodeParser.read("051000025123", LocalDate.of(2026, 1, 1), lines, null);
		assertEquals(Status.OK, reading.getStatus());
		assertEquals(tnd("10.000"), reading.getFaceValue());
		assertEquals("123", reading.getSerial());
	}

	@Test
	@DisplayName("Validity: 1 month = 31 January next year, 0 = 31 December, empty = no check; 4-digit years")
	void validity() {
		assertEquals(LocalDate.of(2026, 1, 31), TicketBarcodeParser.validUntil(2025, 1));
		assertEquals(LocalDate.of(2025, 12, 31), TicketBarcodeParser.validUntil(2025, 0));
		assertEquals(LocalDate.of(2026, 2, 28), TicketBarcodeParser.validUntil(2025, 2));
		assertNull(TicketBarcodeParser.validUntil(2025, null));
		List<AcceptedTicket> lines = lines();
		lines.get(0).setValidityMonths(null);
		Reading noCheck = TicketBarcodeParser.read(SERVIMAX, LocalDate.of(2030, 1, 1), lines, null);
		assertEquals(Status.OK, noCheck.getStatus());
		assertNull(noCheck.getValidUntil());
		AcceptedTicket fourDigits = line(7, meal, "07", "Année longue", 0);
		fourDigits.setLayout(new BarcodeLayout(14, 3, 5, 8, 4, 12, 3));
		Reading reading = TicketBarcodeParser.read("07050002025999", LocalDate.of(2026, 1, 1), Arrays.asList(fourDigits), null);
		assertEquals(2025, reading.getYear());
		assertEquals(tnd("5.000"), reading.getFaceValue());
	}

	@Test
	@DisplayName("Counted value: half up to the millime")
	void counted() {
		assertEquals(tnd("6.300"), TicketBarcodeParser.counted(tnd("7.000"), BigDecimal.TEN));
		assertEquals(tnd("3.300"), TicketBarcodeParser.counted(tnd("3.333"), new BigDecimal("1.0")), "3.29967");
		assertEquals(tnd("0.503"), TicketBarcodeParser.counted(tnd("1.005"), BigDecimal.valueOf(50)), "0.5025, half up");
		assertEquals(tnd("0.000"), TicketBarcodeParser.counted(tnd("7.000"), BigDecimal.valueOf(100)));
	}

	@Test
	@DisplayName("Layout problems: length, face value digits, year digits, outside the barcode, overlap with the code or a field")
	void layoutProblems() {
		assertNull(TicketBarcodeParser.layoutProblem("031", BarcodeLayout.standard()));
		assertTrue(TicketBarcodeParser.layoutProblem("031", new BarcodeLayout(3, 4, 6, 10, 2, 12, 17)).contains("longer"));
		assertTrue(TicketBarcodeParser.layoutProblem("031", new BarcodeLayout(28, 4, 10, 14, 2, 16, 13)).contains("1 to 9"));
		assertTrue(TicketBarcodeParser.layoutProblem("031", new BarcodeLayout(28, 4, 6, 10, 3, 13, 16)).contains("2 or 4"));
		assertTrue(TicketBarcodeParser.layoutProblem("031", new BarcodeLayout(28, 4, 6, 10, 2, 12, 18)).contains("inside"));
		assertTrue(TicketBarcodeParser.layoutProblem("031", new BarcodeLayout(28, 3, 6, 10, 2, 12, 17)).contains("overlaps"));
		assertTrue(TicketBarcodeParser.layoutProblem("031", new BarcodeLayout(28, 4, 6, 9, 2, 12, 17)).contains("overlaps"));
		assertNull(TicketBarcodeParser.layoutProblem("031", new BarcodeLayout(11, 4, 6, 10, 2, null, 0)), "no serial");
	}
}
