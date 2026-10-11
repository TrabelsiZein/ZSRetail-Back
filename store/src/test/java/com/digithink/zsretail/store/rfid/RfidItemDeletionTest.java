package com.digithink.zsretail.store.rfid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;

import com.digithink.zsretail.core.controller.ItemAPI;
import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemComposition;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.ItemCompositionService;
import com.digithink.zsretail.core.service.ItemDeletionCheck;
import com.digithink.zsretail.core.service.ItemService;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.core.support.TestModes;
import com.digithink.zsretail.store.rfid.model.RfidTagStatus;
import com.digithink.zsretail.store.rfid.service.RfidItemDeletionCheck;
import com.digithink.zsretail.store.support.InMemoryRfid;

/**
 * RFID tags step: DELETE /item/{id} of an item with RFID tags answers 409 with the check's message (not the foreign
 * key error), the item kept; without tags, or without the RFID check (RFID off), the item is deleted as before.
 */
class RfidItemDeletionTest {

	private final InMemoryRfid rfid = new InMemoryRfid();

	private ItemAPI api(boolean withRfidCheck) throws Exception {
		ItemService service = new ItemService();
		set(service, ItemService.class, "itemRepository", rfid.items.itemRepository());
		set(service, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "admin";
			}
		});
		ItemCompositionService compositions = new ItemCompositionService() {
			@Override
			public java.util.List<ItemComposition> getCompositionsByComponentItemId(Long id) {
				return Collections.emptyList();
			}

			@Override
			public java.util.List<ItemComposition> getCompositionsByParentItemId(Long id) {
				return Collections.emptyList();
			}
		};
		ItemAPI api = new ItemAPI();
		set(api, _BaseController.class, "service", service);
		set(api, ItemAPI.class, "applicationModeService", TestModes.standalone());
		set(api, ItemAPI.class, "itemCompositionService", compositions);
		if (withRfidCheck) {
			set(api, ItemAPI.class, "deletionChecks", new StaticListableBeanFactory(
					Collections.singletonMap("rfid", new RfidItemDeletionCheck(rfid.tagService)))
					.getBeanProvider(ItemDeletionCheck.class));
		}
		return api;
	}

	private static void set(Object target, Class<?> declaring, String name, Object value) throws Exception {
		Field field = declaring.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	@Test
	@DisplayName("An item with tags (in stock or sold): 409 with the message, the item kept; without tags: deleted")
	void itemWithTagsKept() throws Exception {
		Item tagged = rfid.items.item("SHIRT", 20.0, null);
		Item sold = rfid.items.item("JEANS", 50.0, null);
		Item free = rfid.items.item("SOCKS", 5.0, null);
		rfid.tag("E2001111", tagged, RfidTagStatus.IN_STOCK);
		rfid.tag("E2002222", tagged, RfidTagStatus.IN_STOCK);
		rfid.tag("E2003333", sold, RfidTagStatus.SOLD);
		ItemAPI api = api(true);

		ResponseEntity<?> refused = api.deleteById(tagged.getId());
		assertEquals(409, refused.getStatusCodeValue());
		assertEquals("This item has 2 RFID tags: move them to another item or delete them first (RFID, Tags page).",
				refused.getBody());
		assertTrue(rfid.items.items.containsKey(tagged.getId()));
		assertEquals(409, api.deleteById(sold.getId()).getStatusCodeValue(), "a sold tag holds its item too");

		assertEquals(204, api.deleteById(free.getId()).getStatusCodeValue());
		assertFalse(rfid.items.items.containsKey(free.getId()));
	}

	@Test
	@DisplayName("Without the RFID check (RFID off): DELETE /item/{id} as before")
	void withoutTheCheck() throws Exception {
		Item item = rfid.items.item("SOCKS", 5.0, null);
		assertEquals(204, api(false).deleteById(item.getId()).getStatusCodeValue());
	}
}
