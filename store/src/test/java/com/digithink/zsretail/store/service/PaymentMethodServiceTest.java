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
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.store.service.PaymentMethodService.Refusal;

/**
 * 3.2.0, step 2: the rules in front of the generic CRUD of the payment methods (PaymentMethodService), with an in-memory
 * repository and the usage count mocked.
 */
class PaymentMethodServiceTest {

	private final PaymentMethodService service = new PaymentMethodService();
	private final PaymentMethodRepository repository = mock(PaymentMethodRepository.class);
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

	private final PaymentMethod cheque = method(5L, "ENCAISS-CHEQUE", "Client Chèque", PaymentMethodType.CLIENT_CHEQUE);

	@BeforeEach
	void setUp() throws Exception {
		inject("paymentMethodRepository", repository);
		inject("jdbc", jdbc);
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

	@Test
	@DisplayName("Create: no type and behaviour AMOUNT whatever is sent, code and name trimmed, order 0 and active by default")
	void createForcesTypeAndBehaviour() {
		PaymentMethod sent = method(99L, " TR-SERVIMAX ", " Ticket Servimax ", PaymentMethodType.CLIENT_ESPECES);
		sent.setBehaviour(PaymentMethodBehaviour.SCANNED_TICKETS);
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
		sent.setBehaviour(PaymentMethodBehaviour.SCANNED_TICKETS);
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
	@DisplayName("Update of a method without type: its switches change, its behaviour does not; its own code is not taken")
	void updateMethodWithoutType() {
		PaymentMethod stored = method(20L, "TR-SERVIMAX", "Ticket Servimax", null);
		when(repository.findByCode("TR-SERVIMAX")).thenReturn(Optional.of(stored));
		PaymentMethod sent = method(20L, "TR-SERVIMAX", "Ticket Servimax", PaymentMethodType.CLIENT_ESPECES);
		sent.setBehaviour(PaymentMethodBehaviour.SCANNED_TICKETS);
		sent.setAskTitleNumber(true);
		sent.setRequireDueDate(true);
		PaymentMethod updated = service.prepareUpdate(stored, sent);
		assertNull(updated.getType(), "never given a type");
		assertEquals(PaymentMethodBehaviour.AMOUNT, updated.getBehaviour());
		assertTrue(updated.getAskTitleNumber());
		assertFalse(updated.getRequireTitleNumber());
		assertTrue(updated.getRequireDueDate());
		assertEquals("CODE_TAKEN", reason(() -> service.prepareUpdate(stored, method(20L, "ENCAISS-CHEQUE", "x", null))));
	}

	@Test
	@DisplayName("Delete refused for a fixed type and for a used method (payments, changes, counts); allowed otherwise")
	void deletion() {
		assertEquals("FIXED_TYPE", service.deletionRefusal(cheque).get().getReason());
		PaymentMethod used = method(20L, "TR-SERVIMAX", "Ticket Servimax", null);
		when(jdbc.queryForObject(eq(PaymentMethodService.USES), eq(Integer.class), eq(20L), eq(20L), eq(20L), eq(20L)))
				.thenReturn(3);
		Refusal refusal = service.deletionRefusal(used).get();
		assertEquals("IN_USE", refusal.getReason());
		assertTrue(refusal.getMessage().contains("switch it off instead"), refusal.getMessage());
		PaymentMethod unused = method(21L, "TR-NEW", "New", null);
		when(jdbc.queryForObject(eq(PaymentMethodService.USES), eq(Integer.class), eq(21L), eq(21L), eq(21L), eq(21L)))
				.thenReturn(0);
		assertFalse(service.deletionRefusal(unused).isPresent());
		assertTrue(PaymentMethodService.USES.contains("FROM payment WHERE payment_method_id = ?"));
		assertTrue(PaymentMethodService.USES.contains("old_payment_method_id = ? OR new_payment_method_id = ?"));
		assertTrue(PaymentMethodService.USES.contains("FROM session_cash_count WHERE payment_method_id = ?"));
	}

	private static String reason(Runnable call) {
		return assertThrows(Refusal.class, call::run).getReason();
	}
}
