package com.digithink.zsretail.store.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.service.PaymentMethodService;
import com.digithink.zsretail.store.service.PaymentMethodService.Refusal;

/**
 * 3.2.0, step 2: POST, PUT and DELETE of /payment-method need write:admin-payment-methods (403, nothing written); a
 * refused method answers 400, a refused deletion 409, with {error, reason}. The reads stay the generic ones, open to a
 * logged user.
 */
class PaymentMethodAPITest {

	private final PaymentMethodAPI api = new PaymentMethodAPI();
	private final PaymentMethodService service = mock(PaymentMethodService.class);
	private final CurrentUserProvider users = mock(CurrentUserProvider.class);
	private final PaymentMethod stored = new PaymentMethod();

	@BeforeEach
	void setUp() throws Exception {
		Field serviceField = _BaseController.class.getDeclaredField("service");
		serviceField.setAccessible(true);
		serviceField.set(api, service);
		Field usersField = PaymentMethodAPI.class.getDeclaredField("currentUserProvider");
		usersField.setAccessible(true);
		usersField.set(api, users);
		stored.setId(20L);
		stored.setCode("TR-SERVIMAX");
		when(service.findById(20L)).thenReturn(Optional.of(stored));
	}

	private void right(boolean granted) {
		when(users.currentUserHasPermission(PaymentMethodService.WRITE_PERMISSION)).thenReturn(granted);
	}

	@Test
	@DisplayName("Without the right: 403 on create, update and delete, nothing checked or written")
	void forbiddenWithoutTheRight() throws Exception {
		right(false);
		assertEquals(403, api.create(new PaymentMethod()).getStatusCodeValue());
		assertEquals(403, api.update(20L, new PaymentMethod()).getStatusCodeValue());
		ResponseEntity<?> delete = api.deleteById(20L);
		assertEquals(403, delete.getStatusCodeValue());
		assertEquals("FORBIDDEN", ((Map<?, ?>) delete.getBody()).get("reason"));
		verify(service, never()).prepareCreate(any());
		verify(service, never()).save(any());
		verify(service, never()).deleteById(any());
	}

	@Test
	@DisplayName("With the right: created through the rules (201), a refusal is 400 with its reason")
	void createWithTheRight() throws Exception {
		right(true);
		PaymentMethod sent = new PaymentMethod();
		when(service.save(sent)).thenReturn(sent);
		assertEquals(201, api.create(sent).getStatusCodeValue());
		verify(service).prepareCreate(sent);

		PaymentMethod refused = new PaymentMethod();
		refused.setCode("ENCAISS-CHEQUE"); // not equal to the first one (Lombok's equals compares the fields)
		when(service.prepareCreate(refused)).thenThrow(new Refusal("CODE_TAKEN", "taken"));
		ResponseEntity<?> response = api.create(refused);
		assertEquals(400, response.getStatusCodeValue());
		assertEquals("CODE_TAKEN", ((Map<?, ?>) response.getBody()).get("reason"));
		assertEquals("taken", ((Map<?, ?>) response.getBody()).get("error"));
		verify(service, never()).save(refused);
	}

	@Test
	@DisplayName("Update saves the stored method as the rules made it, not the body sent")
	void updateSavesTheStoredMethod() throws Exception {
		right(true);
		PaymentMethod sent = new PaymentMethod();
		when(service.save(stored)).thenReturn(stored);
		assertEquals(200, api.update(20L, sent).getStatusCodeValue());
		verify(service).prepareUpdate(stored, sent);
		verify(service).save(stored);
		verify(service, never()).save(sent);
		assertEquals(404, api.update(21L, sent).getStatusCodeValue());
	}

	@Test
	@DisplayName("Delete: 409 with the reason when refused, 204 otherwise")
	void delete() {
		right(true);
		when(service.deletionRefusal(stored)).thenReturn(Optional.of(new Refusal("IN_USE", "used")));
		ResponseEntity<?> refused = api.deleteById(20L);
		assertEquals(409, refused.getStatusCodeValue());
		assertEquals("IN_USE", ((Map<?, ?>) refused.getBody()).get("reason"));
		verify(service, never()).deleteById(20L);

		when(service.deletionRefusal(stored)).thenReturn(Optional.empty());
		assertEquals(204, api.deleteById(20L).getStatusCodeValue());
		verify(service).deleteById(20L);
	}
}
