package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodBehaviour;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.repository.AcceptedTicketRepository;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.store.service.PaymentMethodService.Refusal;

/**
 * 3.2.0, steps 2 and 3: the rules in front of the generic CRUD of the payment methods (PaymentMethodService), with an
 * in-memory repository, the usage count and the accepted tickets' count mocked.
 */
class PaymentMethodServiceTest {

	private final PaymentMethodService service = new PaymentMethodService();
	private final PaymentMethodRepository repository = mock(PaymentMethodRepository.class);
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final AcceptedTicketRepository tickets = mock(AcceptedTicketRepository.class);

	private final PaymentMethod cheque = method(5L, "ENCAISS-CHEQUE", "Client Chèque", PaymentMethodType.CLIENT_CHEQUE);

	@BeforeEach
	void setUp() throws Exception {
		inject("paymentMethodRepository", repository);
		inject("jdbc", jdbc);
		inject("acceptedTicketRepository", tickets);
		cheque.setRequireTitleNumber(true);
		cheque.setRequireDrawerName(true);
		cheque.setRequireIssuingBank(true);
		cheque.setTitleNumberLength(7);
		when(repository.findByCode("ENCAISS-CHEQUE")).thenReturn(Optional.of(cheque));
		when(repository.findByCode("TR-SERVIMAX")).thenReturn(Optional.empty());
	}

	private void inject(String name, Object value) throws Exception {
		Field field = PaymentMethodService.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(service, value);
	}

	private static PaymentMethod method(Long id, String code, String name, PaymentMethodType type) {
		PaymentMethod method = new PaymentMethod();
		method.setId(id);
		method.setCode(code);
		method.setName(name);
		method.setType(type);
		return method;
	}

	private void uses(long id, int count) {
		when(jdbc.queryForObject(eq(PaymentMethodService.USES), eq(Integer.class), eq(id), eq(id), eq(id), eq(id)))
				.thenReturn(count);
	}

	@Test
	@DisplayName("Create: no type whatever is sent, behaviour AMOUNT by default, code and name trimmed, order 0, active")
	void createForcesType() {
		PaymentMethod sent = method(99L, " TR-SERVIMAX ", " Ticket Servimax ", PaymentMethodType.CLIENT_ESPECES);
		sent.setBehaviour(null);
		sent.setDisplayOrder(null);
		sent.setActive(null);
		sent.setAskTitleNumber(true);
		sent.setTitleNumberLength(6);
		PaymentMethod created = service.prepareCreate(sent);
		assertNull(created.getId(), "a new row");
		assertNull(created.getType());
		assertEquals(PaymentMethodBehaviour.AMOUNT, created.getBehaviour());
		assertEquals("TR-SERVIMAX", created.getCode());
		assertEquals("Ticket Servimax", created.getName());
		assertEquals(0, created.getDisplayOrder());
		assertTrue(created.getActive());
		assertTrue(created.getAskTitleNumber(), "its switches are its own");
		assertEquals(6, created.getTitleNumberLength());
	}

	@Test
	@DisplayName("Step 3, create a scanned-tickets method: no N° titre, length, due date, drawer or bank (the barcode is the title)")
	void createScannedTickets() {
		PaymentMethod sent = method(null, "TR-SERVIMAX", "Ticket Servimax", null);
		sent.setBehaviour(PaymentMethodBehaviour.SCANNED_TICKETS);
		sent.setRequireTitleNumber(true);
		sent.setTitleNumberLength(7);
		sent.setRequireDueDate(true);
		sent.setRequireDrawerName(true);
		sent.setRequireIssuingBank(true);
		PaymentMethod created = service.prepareCreate(sent);
		assertEquals(PaymentMethodBehaviour.SCANNED_TICKETS, created.getBehaviour());
		assertFalse(created.getRequireTitleNumber());
		assertFalse(created.getAskTitleNumber());
		assertNull(created.getTitleNumberLength());
		assertFalse(created.getRequireDueDate());
		assertFalse(created.getRequireDrawerName());
		assertFalse(created.getRequireIssuingBank());
	}

	@Test
	@DisplayName("Create refused: code or name missing, a code already used, a length out of 1-255; each with its reason")
	void createRefusals() {
		assertEquals("CODE_REQUIRED", reason(() -> service.prepareCreate(method(null, " ", "Name", null))));
		assertEquals("NAME_REQUIRED", reason(() -> service.prepareCreate(method(null, "TR-SERVIMAX", "", null))));
		Refusal taken = assertThrows(Refusal.class,
				() -> service.prepareCreate(method(null, "ENCAISS-CHEQUE", "Another", null)));
		assertEquals("CODE_TAKEN", taken.getReason());
		assertEquals("The code ENCAISS-CHEQUE is already used by the payment method Client Chèque.", taken.getMessage());
		PaymentMethod zero = method(null, "TR-SERVIMAX", "Ticket", null);
		zero.setTitleNumberLength(0);
		assertEquals("LENGTH_INVALID", reason(() -> service.prepareCreate(zero)));
		PaymentMethod tooLong = method(null, "TR-SERVIMAX", "Ticket", null);
		tooLong.setTitleNumberLength(256);
		assertEquals("LENGTH_INVALID", reason(() -> service.prepareCreate(tooLong)));
	}

	@Test
	@DisplayName("Update of a fixed type: type, behaviour and switches kept; code, name, order, active and length change")
	void updateFixedType() {
		PaymentMethod sent = method(5L, "ENCAISS-CHQ", "Chèque", null);
		sent.setBehaviour(PaymentMethodBehaviour.AMOUNT);
		sent.setRequireTitleNumber(false);
		sent.setRequireDrawerName(false);
		sent.setAskTitleNumber(true);
		sent.setDisplayOrder(3);
		sent.setActive(false);
		sent.setTitleNumberLength(8);
		when(repository.findByCode("ENCAISS-CHQ")).thenReturn(Optional.empty());
		PaymentMethod updated = service.prepareUpdate(cheque, sent);
		assertEquals(PaymentMethodType.CLIENT_CHEQUE, updated.getType());
		assertEquals(PaymentMethodBehaviour.AMOUNT, updated.getBehaviour());
		assertTrue(updated.getRequireTitleNumber());
		assertTrue(updated.getRequireDrawerName());
		assertTrue(updated.getRequireIssuingBank());
		assertFalse(updated.getAskTitleNumber());
		assertEquals("ENCAISS-CHQ", updated.getCode());
		assertEquals("Chèque", updated.getName());
		assertEquals(3, updated.getDisplayOrder());
		assertFalse(updated.getActive());
		assertEquals(8, updated.getTitleNumberLength());
	}

	@Test
	@DisplayName("Update of a method without type: its switches change; its own code is not taken")
	void updateMethodWithoutType() {
		PaymentMethod stored = method(20L, "TR-SERVIMAX", "Ticket Servimax", null);
		when(repository.findByCode("TR-SERVIMAX")).thenReturn(Optional.of(stored));
		PaymentMethod sent = method(20L, "TR-SERVIMAX", "Ticket Servimax", PaymentMethodType.CLIENT_ESPECES);
		sent.setAskTitleNumber(true);
		sent.setRequireDueDate(true);
		PaymentMethod updated = service.prepareUpdate(stored, sent);
		assertNull(updated.getType(), "never given a type");
		assertEquals(PaymentMethodBehaviour.AMOUNT, updated.getBehaviour(), "no behaviour sent: kept");
		assertTrue(updated.getAskTitleNumber());
		assertFalse(updated.getRequireTitleNumber());
		assertTrue(updated.getRequireDueDate());
		assertEquals("CODE_TAKEN", reason(() -> service.prepareUpdate(stored, method(20L, "ENCAISS-CHEQUE", "x", null))));
	}

	@Test
	@DisplayName("Step 3, the behaviour changes on a method without type, no payment and no accepted ticket; else BEHAVIOUR_LOCKED")
	void behaviourChange() {
		PaymentMethod fresh = method(20L, "TR-SERVIMAX", "Ticket Servimax", null);
		fresh.setAskTitleNumber(true);
		uses(20L, 0);
		when(tickets.countByPaymentMethod(fresh)).thenReturn(0L);
		PaymentMethod sent = method(20L, "TR-SERVIMAX", "Ticket Servimax", null);
		sent.setBehaviour(PaymentMethodBehaviour.SCANNED_TICKETS);
		sent.setAskTitleNumber(true);
		PaymentMethod updated = service.prepareUpdate(fresh, sent);
		assertEquals(PaymentMethodBehaviour.SCANNED_TICKETS, updated.getBehaviour());
		assertFalse(updated.getAskTitleNumber(), "a scanned-tickets method asks no N° titre");

		// Back to AMOUNT while it has accepted tickets: refused
		when(tickets.countByPaymentMethod(fresh)).thenReturn(6L);
		PaymentMethod back = method(20L, "TR-SERVIMAX", "Ticket Servimax", null);
		back.setBehaviour(PaymentMethodBehaviour.AMOUNT);
		assertEquals("BEHAVIOUR_LOCKED", reason(() -> service.prepareUpdate(fresh, back)));

		// A method with payments: refused
		PaymentMethod paid = method(21L, "TR-PAID", "Paid", null);
		uses(21L, 1);
		PaymentMethod toScanned = method(21L, "TR-PAID", "Paid", null);
		toScanned.setBehaviour(PaymentMethodBehaviour.SCANNED_TICKETS);
		assertEquals("BEHAVIOUR_LOCKED", reason(() -> service.prepareUpdate(paid, toScanned)));

		// A fixed type: refused
		PaymentMethod chequeToScanned = method(5L, "ENCAISS-CHEQUE", "Client Chèque", null);
		chequeToScanned.setBehaviour(PaymentMethodBehaviour.SCANNED_TICKETS);
		assertEquals("BEHAVIOUR_LOCKED", reason(() -> service.prepareUpdate(cheque, chequeToScanned)));
	}

	@Test
	@DisplayName("Delete refused for a fixed type, a used method (payments, changes, counts), a method with accepted tickets")
	void deletion() {
		assertEquals("FIXED_TYPE", service.deletionRefusal(cheque).get().getReason());
		PaymentMethod used = method(20L, "TR-SERVIMAX", "Ticket Servimax", null);
		uses(20L, 3);
		Refusal refusal = service.deletionRefusal(used).get();
		assertEquals("IN_USE", refusal.getReason());
		assertTrue(refusal.getMessage().contains("switch it off instead"), refusal.getMessage());
		PaymentMethod withTickets = method(22L, "TR-LINES", "Lines", null);
		uses(22L, 0);
		when(tickets.countByPaymentMethod(withTickets)).thenReturn(2L);
		assertEquals("HAS_TICKETS", service.deletionRefusal(withTickets).get().getReason());
		PaymentMethod unused = method(21L, "TR-NEW", "New", null);
		uses(21L, 0);
		assertFalse(service.deletionRefusal(unused).isPresent());
		assertTrue(PaymentMethodService.USES.contains("FROM payment WHERE payment_method_id = ?"));
		assertTrue(PaymentMethodService.USES.contains("old_payment_method_id = ? OR new_payment_method_id = ?"));
		assertTrue(PaymentMethodService.USES.contains("FROM session_cash_count WHERE payment_method_id = ?"));
	}

	private static String reason(Runnable call) {
		return assertThrows(Refusal.class, call::run).getReason();
	}
}
