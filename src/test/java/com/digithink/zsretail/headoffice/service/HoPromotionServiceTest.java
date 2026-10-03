package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.digithink.zsretail.headoffice.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.headoffice.dto.PromotionTargetsDTO;
import com.digithink.zsretail.headoffice.dto.PromotionWithTargetsDTO;
import com.digithink.zsretail.headoffice.model.HoPromotionStore;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoPromotionStoreRepository;
import com.digithink.zsretail.headoffice.repository.HoTicketRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.Promotion;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.PromotionBenefitType;
import com.digithink.zsretail.model.enumeration.PromotionScope;
import com.digithink.zsretail.model.enumeration.PromotionType;
import com.digithink.zsretail.repository.PromotionRepository;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.service.PromotionHeadOfficeHooks;
import com.digithink.zsretail.service.PromotionService;
import com.digithink.zsretail.service._BaseService;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Head office plan, task 3.3: the head office side of the promotions. Every save, delete and target change reaches the
 * stores concerned through the copies down feed: every store by default (and a store created later), a list, a store
 * outside the list receives nothing (also when the promotion is created with its list), a store taken off gets a
 * removal, a renamed code is removed under its old code. The usage count of a head office is the consolidated tickets,
 * so the lock and the delete refusal work for the whole network. Real PromotionService, HoPromotionService and
 * CopiesDownFeed over in-memory tables; no Spring context.
 */
class HoPromotionServiceTest {

	private final InMemoryDownTables tables = new InMemoryDownTables();

	/** The head office promotion table, by id. */
	private final Map<Long, Promotion> promotions = new LinkedHashMap<>();
	private final List<HoPromotionStore> targetRows = new ArrayList<>();
	private final Map<Long, Store> stores = new LinkedHashMap<>();

	/** Consolidated tickets: promotion code to [headers, lines]. */
	private final Map<String, long[]> tickets = new HashMap<>();

	private long nextId;
	private PromotionService promotionService;
	private HoPromotionService service;
	private CopiesDownFeed feed;

	private Store a;
	private Store b;

	@BeforeEach
	void setUp() throws Exception {
		nextId = 100;
		a = store(1L, "RS01");
		b = store(2L, "RS02");
		promotionService = new PromotionService();
		inject(promotionService, PromotionService.class, "promotionRepository", promotionRepository());
		inject(promotionService, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "admin";
			}
		});
		service = new HoPromotionService(promotionRepository(), targetRepository(), storeRepository(),
				ticketRepository(), () -> feed, () -> promotionService);
		feed = tables.feed(Collections.singletonList(service));
		inject(promotionService, PromotionService.class, "headOfficeHooks",
				new StaticListableBeanFactory(Collections.singletonMap("hooks", service))
						.getBeanProvider(PromotionHeadOfficeHooks.class));
	}

	private Store store(Long id, String code) {
		Store store = new Store();
		store.setId(id);
		store.setCode(code);
		store.setName("Store " + code);
		stores.put(id, store);
		return store;
	}

	/** A new object, as a request body: never the stored instance. */
	private static Promotion promotion(Long id, String code) {
		Promotion p = new Promotion();
		p.setId(id);
		p.setCode(code);
		p.setName("Promo " + code);
		p.setPromotionType(PromotionType.SIMPLE_DISCOUNT);
		p.setScope(PromotionScope.CART);
		p.setBenefitType(PromotionBenefitType.PERCENTAGE_DISCOUNT);
		p.setDiscountPercentage(10.0);
		return p;
	}

	private Promotion create(String code) throws Exception {
		return promotionService.save(promotion(null, code));
	}

	private Promotion createFor(String code, Long... storeIds) throws Exception {
		PromotionWithTargetsDTO body = new PromotionWithTargetsDTO();
		body.setPromotion(promotion(null, code));
		body.setAllStores(false);
		body.setStoreIds(Arrays.asList(storeIds));
		return (Promotion) service.createWithTargets(body).get("promotion");
	}

	private PromotionTargetsDTO targets(Boolean all, Long... storeIds) {
		PromotionTargetsDTO dto = new PromotionTargetsDTO();
		dto.setAllStores(all);
		dto.setStoreIds(Arrays.asList(storeIds));
		return dto;
	}

	private CopiesDownAnswerDTO pull(Store store, String cursor) {
		return feed.pull(store, "PROMOTIONS", cursor, null);
	}

	private static List<String> codes(CopiesDownAnswerDTO answer) {
		return answer.getRecords().stream().map(r -> r.get("code").asText()).collect(Collectors.toList());
	}

	private static void assertNothing(CopiesDownAnswerDTO answer) {
		assertTrue(answer.getRecords().isEmpty(), "records " + answer.getRecords());
		assertTrue(answer.getRemoved().isEmpty(), "removed " + answer.getRemoved());
	}

	@Test
	@DisplayName("Every store by default: a promotion created reaches each store and a store created later; its edit reaches them")
	void allStoresByDefault() throws Exception {
		Promotion p = create("P1");
		CopiesDownAnswerDTO forA = pull(a, "");
		assertEquals(Collections.singletonList("P1"), codes(forA));
		assertEquals(Collections.singletonList("P1"), codes(pull(b, "")));
		assertEquals(Collections.singletonList("P1"), codes(pull(store(9L, "RS09"), "")));

		Promotion edit = promotion(p.getId(), "P1");
		edit.setActive(false); // deactivated
		promotionService.save(edit);
		CopiesDownAnswerDTO next = pull(a, forA.getCursor());
		assertEquals(Collections.singletonList("P1"), codes(next));
		assertFalse(next.getRecords().get(0).get("active").asBoolean());
	}

	@Test
	@DisplayName("A list: only its stores receive the promotion, also when it is created with the list; edits reach only them")
	void list() throws Exception {
		Promotion p = createFor("P1", a.getId());
		CopiesDownAnswerDTO forA = pull(a, "");
		assertEquals(Collections.singletonList("P1"), codes(forA));
		CopiesDownAnswerDTO forB = pull(b, "");
		assertNothing(forB);

		promotionService.save(promotion(p.getId(), "P1"));
		assertEquals(Collections.singletonList("P1"), codes(pull(a, forA.getCursor())));
		assertNothing(pull(b, forB.getCursor()));
	}

	@Test
	@DisplayName("Targets changed: the store taken off gets a removal, the store added gets the promotion; same targets record nothing")
	void targetsChanged() throws Exception {
		Promotion p = create("P1");
		String cursorA = pull(a, "").getCursor();
		String cursorB = pull(b, "").getCursor();

		service.setTargets(p.getId(), targets(false, a.getId()));
		CopiesDownAnswerDTO forB = pull(b, cursorB);
		assertEquals(Collections.singletonList("P1"), forB.getRemoved());
		assertTrue(forB.getRecords().isEmpty());
		CopiesDownAnswerDTO forA = pull(a, cursorA);
		assertEquals(Collections.singletonList("P1"), codes(forA));

		service.setTargets(p.getId(), targets(false, b.getId()));
		assertEquals(Collections.singletonList("P1"), pull(a, forA.getCursor()).getRemoved());
		assertEquals(Collections.singletonList("P1"), codes(pull(b, forB.getCursor())));

		long version = tables.sequences.get(DataDomain.PROMOTIONS);
		PromotionTargetsDTO same = service.setTargets(p.getId(), targets(false, b.getId()));
		assertEquals(version, (long) tables.sequences.get(DataDomain.PROMOTIONS), "unchanged targets: no change");
		assertEquals(Collections.singletonList(b.getId()), same.getStoreIds());
	}

	@Test
	@DisplayName("Deleted: its stores get a removal, the others nothing; its target rows are deleted")
	void deleted() throws Exception {
		Promotion p = createFor("P1", a.getId());
		String cursorA = pull(a, "").getCursor();
		String cursorB = pull(b, "").getCursor();
		promotionService.deleteById(p.getId());
		assertEquals(Collections.singletonList("P1"), pull(a, cursorA).getRemoved());
		assertNothing(pull(b, cursorB));
		assertTrue(targetRows.isEmpty());
		assertFalse(promotions.containsKey(p.getId()));
	}

	@Test
	@DisplayName("Code changed (unused promotion): the old code is removed, the new one arrives")
	void renamed() throws Exception {
		Promotion p = create("P1");
		String cursor = pull(a, "").getCursor();
		promotionService.save(promotion(p.getId(), "P1X"));
		CopiesDownAnswerDTO answer = pull(a, cursor);
		assertEquals(Collections.singletonList("P1"), answer.getRemoved());
		assertEquals(Collections.singletonList("P1X"), codes(answer));
	}

	@Test
	@DisplayName("Payload: codes only (item, group items sorted, benefit item), never a database id")
	void payloadByCodes() throws Exception {
		Promotion p = promotion(null, "Q1");
		p.setPromotionType(PromotionType.QUANTITY_PROMOTION);
		p.setScope(PromotionScope.ITEM_GROUP);
		p.getGroupItems().add(item(501L, "I-B"));
		p.getGroupItems().add(item(502L, "I-A"));
		p.setGetItem(item(503L, "I-GIFT"));
		p.setMinimumQuantity(2);
		promotionService.save(p);
		JsonNode copy = pull(a, "").getRecords().get(0);
		assertEquals("[\"I-A\",\"I-B\"]", copy.get("groupItemCodes").toString());
		assertEquals("I-GIFT", copy.get("getItemCode").asText());
		assertTrue(copy.get("itemCode").isNull());
		Iterator<String> names = copy.fieldNames();
		while (names.hasNext()) {
			String name = names.next();
			assertFalse(name.equals("id") || name.endsWith("Id"), name);
		}
		assertFalse(copy.toString().contains("501"), "no item id");
	}

	@Test
	@DisplayName("Targets: empty list, unknown store and unknown promotion refused; get and list; create with every store")
	void targetRequests() throws Exception {
		Promotion p = create("P1");
		IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
				() -> service.setTargets(p.getId(), targets(false)));
		assertEquals(HoPromotionService.LIST_REQUIRED, empty.getMessage());
		assertThrows(IllegalArgumentException.class, () -> service.setTargets(p.getId(), null));
		IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
				() -> service.setTargets(p.getId(), targets(false, a.getId(), 9L)));
		assertEquals("Unknown store id(s): [9]", unknown.getMessage());
		assertThrows(NoSuchElementException.class, () -> service.setTargets(999L, targets(true)));
		assertThrows(NoSuchElementException.class, () -> service.getTargets(999L));
		assertTrue(targetRows.isEmpty(), "nothing written");

		service.setTargets(p.getId(), targets(false, b.getId(), a.getId()));
		PromotionTargetsDTO view = service.getTargets(p.getId());
		assertFalse(view.getAllStores());
		assertEquals(Arrays.asList(1L, 2L), view.getStoreIds());
		assertEquals(Arrays.asList("RS01", "RS02"),
				view.getStores().stream().map(PromotionTargetsDTO.StoreOptionDTO::getCode).collect(Collectors.toList()));
		Promotion q = create("Q1");
		List<PromotionTargetsDTO> list = service.listTargets();
		assertEquals(2, list.size());
		assertTrue(list.stream().anyMatch(t -> t.getPromotionId().equals(q.getId()) && t.getAllStores()));

		PromotionWithTargetsDTO body = new PromotionWithTargetsDTO();
		body.setPromotion(promotion(null, "ALL1"));
		Map<String, Object> created = service.createWithTargets(body);
		assertTrue(((PromotionTargetsDTO) created.get("targets")).getAllStores(), "no targets given: every store");
		assertThrows(IllegalArgumentException.class, () -> service.createWithTargets(new PromotionWithTargetsDTO()));
	}

	@Test
	@DisplayName("Usage count on a head office: tickets and lines of every store with the code; lock and delete refusal follow it")
	void networkUsageCount() throws Exception {
		Promotion p = create("P1");
		assertEquals(0, promotionService.getUsageCount(p.getId()));
		tickets.put("P1", new long[] { 2, 3 });
		assertEquals(5, promotionService.getUsageCount(p.getId()));
		assertEquals(0, promotionService.getLocalUsageCount(p.getId()), "a head office never sells");

		Promotion changed = promotion(p.getId(), "P1");
		changed.setDiscountPercentage(50.0);
		IllegalStateException locked = assertThrows(IllegalStateException.class,
				() -> promotionService.validateUpdateAllowed(p.getId(), changed));
		assertTrue(locked.getMessage().startsWith("Promotion has been used in 5 sale(s)"), locked.getMessage());
		Promotion renamedOnly = promotion(p.getId(), "P1");
		renamedOnly.setName("Other name");
		promotionService.validateUpdateAllowed(p.getId(), renamedOnly);
	}

	@Test
	@DisplayName("Startup backfill: promotions made before step 3 reach their stores")
	void backfill() throws Exception {
		Promotion old = promotion(null, "OLD");
		old.setId(nextId++);
		promotions.put(old.getId(), old);
		HoPromotionStore row = new HoPromotionStore();
		row.setPromotionId(old.getId());
		row.setStoreId(a.getId());
		targetRows.add(row);
		feed.initialise();
		assertEquals(Collections.singletonList("OLD"), codes(pull(a, "")));
		assertNothing(pull(b, ""));
	}

	// ─── Stubs ────────────────────────────────────────────────────

	private static Item item(Long id, String code) {
		Item item = new Item();
		item.setId(id);
		item.setItemCode(code);
		item.setName(code);
		return item;
	}

	private PromotionRepository promotionRepository() {
		return InMemoryDownTables.proxy(PromotionRepository.class, (method, args) -> {
			switch (method) {
				case "findById":
					return Optional.ofNullable(promotions.get(args[0]));
				case "findByCode":
					return promotions.values().stream().filter(p -> p.getCode().equals(args[0])).findFirst();
				case "findByCodeIn":
					Collection<?> codes = (Collection<?>) args[0];
					return promotions.values().stream().filter(p -> codes.contains(p.getCode()))
							.collect(Collectors.toList());
				case "findAll":
					return new ArrayList<>(promotions.values());
				case "save":
					Promotion saved = (Promotion) args[0];
					if (saved.getId() == null) {
						saved.setId(nextId++);
					}
					promotions.put(saved.getId(), saved);
					return saved;
				case "deleteById":
					promotions.remove(args[0]);
					return null;
				case "countUsages":
					return 0L;
				default:
					throw new UnsupportedOperationException(method);
			}
		});
	}

	private HoPromotionStoreRepository targetRepository() {
		return InMemoryDownTables.proxy(HoPromotionStoreRepository.class, (method, args) -> {
			switch (method) {
				case "findByPromotionId":
					return targetRows.stream().filter(r -> r.getPromotionId().equals(args[0])).collect(Collectors.toList());
				case "findByPromotionIdIn":
					Collection<?> ids = (Collection<?>) args[0];
					return targetRows.stream().filter(r -> ids.contains(r.getPromotionId())).collect(Collectors.toList());
				case "save":
					targetRows.add((HoPromotionStore) args[0]);
					return args[0];
				case "deleteAll":
					for (Object row : (Iterable<?>) args[0]) {
						targetRows.removeIf(r -> r == row);
					}
					return null;
				default:
					throw new UnsupportedOperationException(method);
			}
		});
	}

	private StoreRepository storeRepository() {
		return InMemoryDownTables.proxy(StoreRepository.class, (method, args) -> {
			if ("findAllById".equals(method)) {
				List<Store> found = new ArrayList<>();
				for (Object id : (Iterable<?>) args[0]) {
					if (stores.containsKey(id)) {
						found.add(stores.get(id));
					}
				}
				return found;
			}
			throw new UnsupportedOperationException(method);
		});
	}

	private HoTicketRepository ticketRepository() {
		return InMemoryDownTables.proxy(HoTicketRepository.class, (method, args) -> {
			long[] counts = tickets.getOrDefault(args[0], new long[2]);
			switch (method) {
				case "countByPromotionCode":
					return counts[0];
				case "countLinesByPromotionCode":
					return counts[1];
				default:
					throw new UnsupportedOperationException(method);
			}
		});
	}

	private static void inject(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
