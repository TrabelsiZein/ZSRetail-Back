package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.AcceptedTicket;
import com.digithink.zsretail.core.model.BarcodeLayout;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodBehaviour;
import com.digithink.zsretail.core.repository.AcceptedTicketRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.CommonTicketIssuers;
import com.digithink.zsretail.core.service.TicketBarcodeParser;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.service.PaymentMethodService.Refusal;

/**
 * 3.2.0, step 3: the rules of the accepted tickets (AcceptedTicketService), the table in memory: only under a
 * scanned-tickets method, the fields checked, no two overlapping codes of one barcode length (any method, active or
 * not), the common issuers added inactive with the overlapping codes skipped, a barcode tested with the method expected.
 */
class AcceptedTicketServiceTest {

	private final AcceptedTicketService service = new AcceptedTicketService();
	private final List<AcceptedTicket> table = new ArrayList<>();
	private final com.digithink.zsretail.store.repository.ScannedTicketPaymentRepository scanned = mock(
			com.digithink.zsretail.store.repository.ScannedTicketPaymentRepository.class);
	private long nextId = 100;

	private final PaymentMethod meal = method(1L, "TICKET_RESTAURANT", "Ticket Restaurant", PaymentMethodBehaviour.SCANNED_TICKETS);
	private final PaymentMethod gift = method(2L, "CHEQUE_CADEAU", "Chèque Cadeau", PaymentMethodBehaviour.SCANNED_TICKETS);
	private final PaymentMethod card = method(3L, "TPE", "Carte", PaymentMethodBehaviour.AMOUNT);

	@BeforeEach
	void setUp() throws Exception {
		AcceptedTicketRepository repository = mock(AcceptedTicketRepository.class);
		when(repository.findAll()).thenAnswer(i -> new ArrayList<>(table));
		when(repository.countPerPaymentMethod()).thenAnswer(i -> Arrays.asList(new Object[] { 1L, 6L, 1L },
				new Object[] { 2L, 3L, null }));
		when(repository.findByPaymentMethodOrderByTicketCode(any())).thenAnswer(i -> table.stream()
				.filter(l -> l.getPaymentMethod() == i.getArgument(0)).collect(Collectors.toList()));
		when(repository.save(any(AcceptedTicket.class))).thenAnswer(i -> {
			AcceptedTicket line = i.getArgument(0);
			if (line.getId() == null) {
				line.setId(nextId++);
				table.add(line);
			}
			return line;
		});
		Field field = AcceptedTicketService.class.getDeclaredField("acceptedTicketRepository");
		field.setAccessible(true);
		field.set(service, repository);
		Field scannedField = AcceptedTicketService.class.getDeclaredField("scannedTicketPaymentRepository");
		scannedField.setAccessible(true);
		scannedField.set(service, scanned);
		Field users = _BaseService.class.getDeclaredField("currentUserProvider");
		users.setAccessible(true);
		users.set(service, mock(CurrentUserProvider.class));
	}

	private static PaymentMethod method(Long id, String code, String name, PaymentMethodBehaviour behaviour) {
		PaymentMethod method = new PaymentMethod();
		method.setId(id);
		method.setCode(code);
		method.setName(name);
		method.setBehaviour(behaviour);
		return method;
	}

	private static AcceptedTicket sent(String code, String issuer, String deduction) {
		AcceptedTicket line = new AcceptedTicket();
		line.setTicketCode(code);
		line.setIssuerName(issuer);
		line.setDeductionPercent(new BigDecimal(deduction));
		return line;
	}

	private String reason(Executable call) {
		return assertThrows(Refusal.class, call::run).getReason();
	}

	interface Executable {
		void run() throws Exception;
	}

	@Test
	@DisplayName("A line: under a scanned-tickets method only; issuer, code digits, deduction 0-100 (2 decimals), validity, layout")
	void checks() throws Exception {
		AcceptedTicket added = service.add(meal, sent(" 031 ", " Servimax Repas ", "10"));
		assertEquals("031", added.getTicketCode());
		assertEquals("Servimax Repas", added.getIssuerName());
		assertTrue(added.getActive(), "a line the admin adds is active");
		assertEquals(BarcodeLayout.standard(), added.getLayout(), "the default layout");
		assertEquals(1, added.getValidityMonths());

		assertEquals("NOT_SCANNED_METHOD", reason(() -> service.add(card, sent("061", "X", "0"))));
		assertEquals("ISSUER_REQUIRED", reason(() -> service.add(meal, sent("061", " ", "0"))));
		assertEquals("CODE_INVALID", reason(() -> service.add(meal, sent("06A", "X", "0"))));
		assertEquals("CODE_INVALID", reason(() -> service.add(meal, sent("", "X", "0"))));
		assertEquals("DEDUCTION_INVALID", reason(() -> service.add(meal, sent("061", "X", "100.5"))));
		assertEquals("DEDUCTION_INVALID", reason(() -> service.add(meal, sent("061", "X", "-1"))));
		assertEquals("DEDUCTION_INVALID", reason(() -> service.add(meal, sent("061", "X", "10.125"))));
		AcceptedTicket validity = sent("061", "X", "0");
		validity.setValidityMonths(121);
		assertEquals("VALIDITY_INVALID", reason(() -> service.add(meal, validity)));
		AcceptedTicket layout = sent("061", "X", "0");
		layout.setLayout(new BarcodeLayout(28, 3, 6, 10, 2, 12, 17));
		assertEquals("LAYOUT_INVALID", reason(() -> service.add(meal, layout)));
		AcceptedTicket noValidity = sent("061", "X", "12.5");
		noValidity.setValidityMonths(null);
		assertEquals(null, service.add(meal, noValidity).getValidityMonths(), "empty: no validity check");
	}

	@Test
	@DisplayName("Overlap refused at one length, whatever the method and the active switch; another length is apart")
	void overlaps() throws Exception {
		AcceptedTicket servimax = service.add(meal, sent("031", "Servimax Repas", "10"));
		servimax.setActive(false);
		assertEquals("CODE_CONFLICT", reason(() -> service.add(gift, sent("031", "Again", "0"))));
		AcceptedTicket longerCode = sent("0312", "Longer", "0");
		longerCode.setLayout(new BarcodeLayout(28, 5, 6, 11, 2, 13, 16)); // a 4-digit code: its fields after it
		Refusal longer = assertThrows(Refusal.class, () -> service.add(gift, longerCode));
		assertEquals("CODE_CONFLICT", longer.getReason());
		assertTrue(longer.getMessage().contains("031 of Servimax Repas (Ticket Restaurant)"), longer.getMessage());
		// Step 3b: the ticket it overlaps, for the screen's text
		assertEquals("Servimax Repas", longer.getDetails().get("issuer"));
		assertEquals("031", longer.getDetails().get("code"));
		assertEquals("Ticket Restaurant", longer.getDetails().get("method"));
		assertEquals("CODE_CONFLICT", reason(() -> service.add(gift, sent("03", "Shorter", "0"))));
		AcceptedTicket otherLength = sent("031", "Short tickets", "0");
		otherLength.setLayout(new BarcodeLayout(20, 4, 6, 10, 2, 12, 9));
		service.add(gift, otherLength);
		assertEquals(2, table.size());
		// Editing a line does not conflict with itself
		AcceptedTicket edit = sent("031", "Servimax Repas", "5");
		assertEquals(new BigDecimal("5"), service.edit(servimax, edit).getDeductionPercent());
	}

	@Test
	@DisplayName("Common issuers: the six of the kind, inactive, default layout; a code already there skipped")
	void commonIssuers() throws Exception {
		service.add(meal, sent("031", "Servimax (mine)", "8"));
		Map<String, List<String>> result = service.addCommon(meal, CommonTicketIssuers.Kind.MEAL);
		assertEquals(Arrays.asList("011", "021", "041", "051", "081"), result.get("added"));
		assertEquals(Arrays.asList("031"), result.get("skipped"));
		List<AcceptedTicket> lines = service.lines(meal);
		assertEquals(6, lines.size());
		AcceptedTicket bonus = lines.stream().filter(l -> "011".equals(l.getTicketCode())).findFirst().get();
		assertEquals("Bonus Repas", bonus.getIssuerName());
		assertFalse(bonus.getActive());
		assertEquals(0, new BigDecimal("10").compareTo(bonus.getDeductionPercent()));
		assertEquals(1, bonus.getValidityMonths());
		assertEquals(BarcodeLayout.standard(), bonus.getLayout());
		assertEquals("Servimax (mine)", lines.stream().filter(l -> "031".equals(l.getTicketCode())).findFirst().get()
				.getIssuerName(), "the admin's line kept");
		assertEquals("NOT_SCANNED_METHOD", reason(() -> service.addCommon(card, CommonTicketIssuers.Kind.GIFT)));
		Map<String, List<String>> again = service.addCommon(meal, CommonTicketIssuers.Kind.MEAL);
		assertTrue(again.get("added").isEmpty(), "a second time adds nothing");
	}

	@Test
	@DisplayName("Step 4: a line that paid sales is not deleted (switch it off), French message; one that did not may be")
	void deletionOfAUsedLine() throws Exception {
		AcceptedTicket servimax = service.add(meal, sent("031", "Servimax Repas", "10"));
		AcceptedTicket sodexo = service.add(gift, sent("042", "Sodexo Cadeau", "0"));
		when(scanned.countByAcceptedTicket(servimax)).thenReturn(3L);
		Refusal refusal = service.deletionRefusal(servimax).get();
		assertEquals("USED_BY_SALES", refusal.getReason());
		assertEquals("Le ticket « Servimax Repas » (031) a déjà payé 3 vente(s) : il ne se supprime pas, désactivez-le.",
				refusal.getMessage());
		assertFalse(service.deletionRefusal(sodexo).isPresent());
	}

	@Test
	@DisplayName("Step 3b, the counts per method: total and active (\"1 actif / 6\")")
	void counts() {
		Map<Long, Map<String, Long>> counts = service.countPerMethod();
		assertEquals(6L, counts.get(1L).get("total"));
		assertEquals(1L, counts.get(1L).get("active"));
		assertEquals(0L, counts.get(2L).get("active"), "no active line: 0");
	}

	@Test
	@DisplayName("Test a barcode: read against every line, the method of the screen expected")
	void testBarcode() throws Exception {
		AcceptedTicket servimax = service.add(meal, sent("031", "Servimax Repas", "10"));
		service.add(gift, sent("042", "Sodexo Cadeau", "0"));
		TicketBarcodeParser.Reading reading = service.test(meal, "0310070002500002897009796069", LocalDate.of(2026, 1, 31));
		assertEquals(TicketBarcodeParser.Status.OK, reading.getStatus());
		assertEquals(servimax.getId(), reading.getTicketId());
		assertEquals(new BigDecimal("6.300"), reading.getCountedValue());
		assertEquals(TicketBarcodeParser.Status.OTHER_METHOD,
				service.test(meal, "0420100002508037484068000487", LocalDate.of(2026, 1, 31)).getStatus());
	}
}
