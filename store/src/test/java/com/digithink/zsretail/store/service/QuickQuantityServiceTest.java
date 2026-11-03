package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.controller.QuickQuantityAPI;
import com.digithink.zsretail.store.model.QuickQuantity;
import com.digithink.zsretail.store.repository.QuickQuantityRepository;

/**
 * 3.1.2, bulk sale, step 1: the quick quantities. A label (trimmed, at most 100 characters), a quantity above 0 with at
 * most 3 decimals (stored without trailing zeros), an optional linked item read by its id; the API answers 400 with the
 * reason before anything is written; an item used as a linked item is not deleted (its deletion check).
 */
class QuickQuantityServiceTest {

	private QuickQuantityRepository repository;
	private ItemRepository items;
	private QuickQuantityService service;
	private Item flacon;

	@BeforeEach
	void setUp() throws Exception {
		repository = mock(QuickQuantityRepository.class);
		when(repository.save(any(QuickQuantity.class))).thenAnswer(call -> call.getArgument(0));
		items = mock(ItemRepository.class);
		flacon = new Item();
		flacon.setId(7L);
		flacon.setItemCode("FLACON-100");
		when(items.findById(anyLong())).thenReturn(Optional.empty());
		when(items.findById(7L)).thenReturn(Optional.of(flacon));
		service = new QuickQuantityService();
		set(service, QuickQuantityService.class, "quickQuantityRepository", repository);
		set(service, QuickQuantityService.class, "itemRepository", items);
		set(service, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "admin";
			}
		});
	}

	private static void set(Object target, Class<?> declaring, String name, Object value) throws Exception {
		Field field = declaring.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static QuickQuantity quick(String label, String quantity, Long linkedItemId) {
		QuickQuantity quick = new QuickQuantity();
		quick.setLabel(label);
		quick.setQuantity(quantity == null ? null : new BigDecimal(quantity));
		if (linkedItemId != null) {
			Item linked = new Item();
			linked.setId(linkedItemId);
			quick.setLinkedItem(linked);
		}
		quick.setDisplayOrder(null);
		return quick;
	}

	@Test
	@DisplayName("Saved: the label trimmed, 0.100 kept as 0.1, the linked item read by its id, the order 0 when missing")
	void saved() throws Exception {
		QuickQuantity saved = service.save(quick("  100 ml ", "0.100", 7L));
		assertEquals("100 ml", saved.getLabel());
		assertEquals("0.1", saved.getQuantity().toPlainString());
		assertSame(flacon, saved.getLinkedItem());
		assertEquals(0, saved.getDisplayOrder());
		assertEquals("1", service.save(quick("1 L", "1.000", null)).getQuantity().toPlainString());
		assertNull(service.save(quick("50 ml", "0.05", null)).getLinkedItem());
	}

	@Test
	@DisplayName("Refused: no label, a label too long, 0 or below, a 4th decimal, an unknown linked item; nothing saved")
	void refused() {
		assertThrows(IllegalArgumentException.class, () -> service.save(quick("  ", "0.1", null)));
		assertThrows(IllegalArgumentException.class, () -> service.save(quick(null, "0.1", null)));
		StringBuilder longLabel = new StringBuilder();
		for (int i = 0; i <= QuickQuantity.LABEL_LENGTH; i++) {
			longLabel.append('x');
		}
		assertThrows(IllegalArgumentException.class, () -> service.save(quick(longLabel.toString(), "0.1", null)));
		assertThrows(IllegalArgumentException.class, () -> service.save(quick("0", "0", null)));
		assertThrows(IllegalArgumentException.class, () -> service.save(quick("-1", "-1", null)));
		assertThrows(IllegalArgumentException.class, () -> service.save(quick("none", null, null)));
		IllegalArgumentException decimals = assertThrows(IllegalArgumentException.class,
				() -> service.save(quick("x", "0.1005", null)));
		assertTrue(decimals.getMessage().contains("0.1005"), decimals.getMessage());
		assertThrows(IllegalArgumentException.class, () -> service.save(quick("x", "0.1", 99L)));
		verify(repository, never()).save(any(QuickQuantity.class));
	}

	@Test
	@DisplayName("The API: 400 with the reason, nothing written; a good one 201")
	void apiAnswers() throws Exception {
		QuickQuantityAPI api = new QuickQuantityAPI();
		set(api, _BaseController.class, "service", service);
		assertEquals(400, api.create(quick("x", "0.1005", null)).getStatusCodeValue());
		assertEquals(400, api.create(quick("", "0.1", null)).getStatusCodeValue());
		when(repository.findById(3L)).thenReturn(Optional.of(quick("old", "0.2", null)));
		assertEquals(400, api.update(3L, quick("x", "0", null)).getStatusCodeValue());
		verify(repository, never()).save(any(QuickQuantity.class));
		assertEquals(201, api.create(quick("100 ml", "0.1", 7L)).getStatusCodeValue());
		assertEquals(200, api.update(3L, quick("200 ml", "0.2", null)).getStatusCodeValue());
	}

	@Test
	@DisplayName("An item used as a linked item is not deleted: its deletion check says why; another item: no refusal")
	void deletionCheck() {
		QuickQuantityItemDeletionCheck check = new QuickQuantityItemDeletionCheck(repository);
		when(repository.countByLinkedItemId(7L)).thenReturn(2L);
		when(repository.countByLinkedItemId(8L)).thenReturn(1L);
		assertTrue(check.refusal(7L).contains("2 quick quantities"), check.refusal(7L));
		assertTrue(check.refusal(8L).contains("1 quick quantity:"), check.refusal(8L));
		assertNull(check.refusal(9L));
		assertNull(check.refusal(null));
	}
}
