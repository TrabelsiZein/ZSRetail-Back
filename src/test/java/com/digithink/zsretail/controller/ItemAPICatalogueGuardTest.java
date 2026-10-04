package com.digithink.zsretail.controller;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.holink.model.LinkRight;
import com.digithink.zsretail.holink.repository.LinkRightRepository;
import com.digithink.zsretail.holink.service.CatalogueCopyWriter;
import com.digithink.zsretail.holink.service.CatalogueRights;
import com.digithink.zsretail.holink.service.HeadOfficeLinkStatus;
import com.digithink.zsretail.holink.service.StoreCatalogueGuard;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.SalesPriceRepository;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.service.ItemCompositionService;
import com.digithink.zsretail.service.ItemService;
import com.digithink.zsretail.service._BaseService;
import com.digithink.zsretail.support.InMemoryCatalogue;

/**
 * Head office plan, task 6.3 and 6.5: the real ItemAPI with and without the step 6 guard. With it (a store whose
 * catalogue is the head office's): a head office item answers 409 on edit, delete and pack flag, creating needs the
 * purchase right, the own price has its endpoints. Without it (every other store, a franchise customer): the same
 * requests answer as before. Real ItemService over an in-memory item table; no Spring context.
 */
class ItemAPICatalogueGuardTest {

	private InMemoryCatalogue store;
	private final Map<String, LinkRight> saved = new HashMap<>();
	private CatalogueRights rights;
	private Item hoItem;
	private Item ownItem;

	@BeforeEach
	void setUp() {
		store = new InMemoryCatalogue(1);
		rights = new CatalogueRights(proxy(LinkRightRepository.class, (method, args) -> {
			switch (method) {
				case "findByCode":
					return Optional.ofNullable(saved.get(args[0]));
				case "save":
					saved.put(((LinkRight) args[0]).getCode(), (LinkRight) args[0]);
					return args[0];
				default:
					return UNHANDLED;
			}
		}));
		hoItem = store.item("B001", 10.0, null);
		hoItem.setOrigin(RecordOrigin.HEAD_OFFICE);
		hoItem.setHeadOfficePrice(10.0);
		ownItem = store.item("OWN1", 5.0, null);
	}

	private ItemAPI api(boolean withGuard) throws Exception {
		ApplicationModeService mode = new ApplicationModeService();
		set(mode, ApplicationModeService.class, "standalone", true);
		ItemService service = new ItemService();
		set(service, ItemService.class, "itemRepository", store.itemRepository());
		set(service, ItemService.class, "applicationModeService", mode);
		set(service, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "admin";
			}
		});
		ItemCompositionService compositions = new ItemCompositionService() {
			@Override
			public java.util.List<com.digithink.zsretail.model.ItemComposition> getCompositionsByComponentItemId(Long id) {
				return Collections.emptyList();
			}

			@Override
			public java.util.List<com.digithink.zsretail.model.ItemComposition> getCompositionsByParentItemId(Long id) {
				return Collections.emptyList();
			}
		};
		ItemAPI api = new ItemAPI();
		set(api, _BaseController.class, "service", service);
		set(api, ItemAPI.class, "applicationModeService", mode);
		set(api, ItemAPI.class, "itemCompositionService", compositions);
		if (withGuard) {
			SalesPriceRepository salesPrices = proxy(SalesPriceRepository.class, (method, args) -> UNHANDLED);
			CatalogueCopyWriter writer = new CatalogueCopyWriter(store.familyRepository(), store.subFamilyRepository(),
					store.itemRepository(), store.barcodeRepository(), store.compositionRepository());
			StoreCatalogueGuard guard = new StoreCatalogueGuard(rights, store.itemRepository(), store.familyRepository(),
					store.subFamilyRepository(), store.barcodeRepository(), salesPrices, writer, new HeadOfficeLinkStatus());
			set(api, ItemAPI.class, "catalogueGuard", new StaticListableBeanFactory(Collections.singletonMap("guard", guard))
					.getBeanProvider(StoreCatalogueGuard.class));
		}
		return api;
	}

	private static void set(Object target, Class<?> declaring, String name, Object value) throws Exception {
		Field field = declaring.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static Item body(String code, double price) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName("Edited " + code);
		item.setUnitPrice(price);
		return item;
	}

	@Test
	@DisplayName("With the guard: a head office item answers 409 on edit, delete and pack flag; an own item is edited")
	void headOfficeItemConsultOnly() throws Exception {
		ItemAPI api = api(true);
		assertEquals(409, api.update(hoItem.getId(), body("B001", 99.0)).getStatusCodeValue());
		assertEquals(10.0, hoItem.getUnitPrice(), "nothing written");
		assertEquals(409, api.deleteById(hoItem.getId()).getStatusCodeValue());
		assertTrue(store.items.containsKey(hoItem.getId()));
		assertEquals(409, api.setPackageFlag(hoItem.getId(), Collections.singletonMap("isPackage", true))
				.getStatusCodeValue());
		ResponseEntity<?> own = api.update(ownItem.getId(), body("OWN1", 6.0));
		assertEquals(200, own.getStatusCodeValue());
		assertEquals(6.0, store.items.get(ownItem.getId()).getUnitPrice());
	}

	@Test
	@DisplayName("With the guard: creating needs the purchase right, and never with a head office code")
	void createNeedsTheRight() throws Exception {
		ItemAPI api = api(true);
		assertEquals(409, api.create(body("NEW1", 1.0)).getStatusCodeValue());
		rights.received(false, true, LocalDateTime.now());
		assertEquals(409, api.create(body("B001", 1.0)).getStatusCodeValue(), "a head office code");
		assertEquals(201, api.create(body("NEW1", 1.0)).getStatusCodeValue());
		Item created = store.itemByCode("NEW1").get();
		assertNull(created.getOrigin(), "an own item is local");
	}

	@Test
	@DisplayName("With the guard: own price with the right, given back; an update keeps own price and head office price")
	void ownPrice() throws Exception {
		ItemAPI api = api(true);
		assertEquals(409, api.setOwnPrice(hoItem.getId(), Collections.singletonMap("unitPrice", 12.0)).getStatusCodeValue());
		rights.received(true, false, LocalDateTime.now());
		assertEquals(400, api.setOwnPrice(hoItem.getId(), Collections.singletonMap("unitPrice", "x")).getStatusCodeValue());
		assertEquals(400, api.setOwnPrice(ownItem.getId(), Collections.singletonMap("unitPrice", 12.0)).getStatusCodeValue());
		assertEquals(404, api.setOwnPrice(999L, Collections.singletonMap("unitPrice", 12.0)).getStatusCodeValue());
		assertEquals(200, api.setOwnPrice(hoItem.getId(), Collections.singletonMap("unitPrice", 12.0)).getStatusCodeValue());
		assertEquals(12.0, hoItem.getUnitPrice());
		assertEquals(200, api.giveBackPrice(hoItem.getId()).getStatusCodeValue());
		assertEquals(10.0, hoItem.getUnitPrice());
	}

	@Test
	@DisplayName("Without the guard (every other store, a franchise customer): as before, whatever the origin")
	void withoutTheGuard() throws Exception {
		ItemAPI api = api(false);
		assertEquals(404, api.setOwnPrice(hoItem.getId(), Collections.singletonMap("unitPrice", 12.0)).getStatusCodeValue());
		assertEquals(404, api.giveBackPrice(hoItem.getId()).getStatusCodeValue());
		assertEquals(201, api.create(body("NEW1", 1.0)).getStatusCodeValue());
		hoItem.setOwnPrice(true);
		ResponseEntity<?> edited = api.update(hoItem.getId(), body("B001", 99.0));
		assertEquals(200, edited.getStatusCodeValue(), "a store that owns its catalogue edits every item");
		Item after = store.items.get(hoItem.getId());
		assertEquals(99.0, after.getUnitPrice());
		assertEquals(Boolean.TRUE, after.getOwnPrice(), "never read from JSON: kept");
		assertEquals(10.0, after.getHeadOfficePrice());
		assertEquals(204, api.deleteById(ownItem.getId()).getStatusCodeValue());
	}

	@Test
	@DisplayName("Step 6: GET /item/search gives origin for each item (HEAD_OFFICE, null for an own item)")
	@SuppressWarnings("unchecked")
	void searchGivesOrigin() throws Exception {
		ItemAPI api = api(false);
		ItemService search = new ItemService() {
			@Override
			public Page<Item> searchItemsForPurchase(String text, Pageable pageable) {
				return new PageImpl<>(Arrays.asList(hoItem, ownItem), pageable, 2);
			}
		};
		set(api, _BaseController.class, "service", search);
		ResponseEntity<?> answer = api.searchItems("", 0, 10);
		assertEquals(200, answer.getStatusCodeValue());
		java.util.List<Map<String, Object>> content = (java.util.List<Map<String, Object>>) ((Map<String, Object>) answer
				.getBody()).get("content");
		assertEquals("HEAD_OFFICE", content.get(0).get("origin"));
		assertTrue(content.get(1).containsKey("origin"));
		assertNull(content.get(1).get("origin"));
		assertEquals("B001", content.get(0).get("itemCode"));
	}
}
