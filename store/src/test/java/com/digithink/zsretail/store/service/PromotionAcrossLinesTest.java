package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.dto.PricingResult;
import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.Promotion;
import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.model.enumeration.PromotionBenefitType;
import com.digithink.zsretail.core.model.enumeration.PromotionScope;
import com.digithink.zsretail.core.model.enumeration.PromotionType;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.PromotionRepository;
import com.digithink.zsretail.core.service.CustomerService;
import com.digithink.zsretail.core.service.PricingService;
import com.digithink.zsretail.store.dto.CartCalculateRequestDTO;
import com.digithink.zsretail.store.dto.CartCalculateResponseDTO;
import com.digithink.zsretail.store.dto.CrossProductAdjustmentDTO;
import com.digithink.zsretail.store.dto.PriceCalculateResponseDTO;

/**
 * 3.1.0, promotions for variants step 2 (rule 6a and decisions B, C of 2026-10-23): a quantity promotion counts the
 * whole pieces of every cart line of its target (NAV item, group, section, famille, all items...), repeats per group,
 * gives its free pieces cheapest unit price first then cart order, the pieces still to scan on its last line only;
 * level by level a line taken by a more specific promotion leaves the next levels; a free promotion giving at least 1
 * free piece takes every line it counts. One line: exactly as before. Plain JUnit, in-memory stubs mirroring the JPQL.
 */
class PromotionAcrossLinesTest {

	private final List<Promotion> promotions = new ArrayList<>();
	private final Map<Long, Item> items = new HashMap<>();
	private PromotionCalculationService engine;
	private long nextId;

	private final ItemFamily shirts = family(1);
	private final ItemFamily trousers = family(2);
	private final ItemSection sleeves = section(1, "MANCHE");

	@BeforeEach
	void setUp() throws Exception {
		nextId = 100;
		engine = new PromotionCalculationService();
		inject(engine, "itemRepository", itemRepository());
		inject(engine, "promotionRepository", promotionRepository());
		inject(engine, "customerService", new CustomerService() {
			@Override
			public Customer getDefaultCustomer() {
				return new Customer();
			}
		});
		inject(engine, "pricingService", new PricingService() {
			@Override
			public PricingResult calculateItemPrice(Item item, Customer customer, BigDecimal quantity,
					String responsibilityCenter) {
				PricingResult erp = new PricingResult();
				erp.setUnitPrice(item.getUnitPrice());
				erp.setPriceIncludesVat(true);
				erp.setSource("BASE_PRICE");
				return erp;
			}
		});
		item(10, "N1", shirts, null, 100.0); // N1 size S
		item(11, "N1", shirts, null, 80.0); // N1 size M, cheaper
		item(12, "N2", shirts, null, 100.0);
		item(30, null, trousers, null, 50.0);
		item(31, null, trousers, null, 40.0);
		item(32, null, trousers, "MANCHE", 60.0);
		item(33, null, trousers, "MANCHE", 60.0);
		item(70, null, null, null, 20.0); // a buy item of cross-products
		item(71, null, null, null, 20.0);
	}

	// ─── NAV item (two variants) ──────────────────────────────────

	@Test
	@DisplayName("NAV N1 buy 2 get 1: S x1 + M x2 = 3 pieces, 1 free on M (cheaper); S taken with 0 free (decision C)")
	void navTwoVariants() {
		Promotion p = free("NAV21", PromotionScope.ITEM, 2, 1);
		p.setNavItemNo("N1");
		List<CartCalculateRequestDTO.CartItemDTO> cart = Arrays.asList(line(10, 1, 100), line(11, 2, 80));

		Map<Long, PriceCalculateResponseDTO> lines = lineResults(cart);
		assertFree(p, 0, 0, lines.get(10L));
		assertFree(p, 1, 0, lines.get(11L));
		assertTrue(lines.get(10L).getAcrossLines());
		assertTrue(lines.get(11L).getAcrossLines());

		PriceCalculateResponseDTO asked = engine.calculateItemPrice(11L, BigDecimal.valueOf(2), null, codes(), cart);
		assertFree(p, 1, 0, asked);
		assertTrue(asked.getAcrossLines(), "M alone: 2 pieces, no free piece yet");

		PriceCalculateResponseDTO alone = engine.calculateItemPrice(11L, BigDecimal.valueOf(2), null, codes());
		assertNull(alone.getPromotionId(), "alone: as before, 0 free, the hint");
		assertEquals(0, alone.getFreeQuantity().intValue());
		assertEquals(1, alone.getFreeQuantityAvailable().intValue());
		assertNull(alone.getAcrossLines());
	}

	@Test
	@DisplayName("Repeats per group: 6 pieces of N1, buy 2 get 1 = 2 free, all on the cheapest line first")
	void repeatsPerGroup() {
		Promotion p = free("NAV21", PromotionScope.ITEM, 2, 1);
		p.setNavItemNo("N1");
		Map<Long, PriceCalculateResponseDTO> lines = lineResults(Arrays.asList(line(10, 4, 100), line(11, 2, 80)));
		assertFree(p, 0, 0, lines.get(10L));
		assertFree(p, 2, 0, lines.get(11L));

		// 5 pieces = 3 + 2: 1 free (on M, the cheaper), 1 more piece free to scan, said on the cart's last line (M)
		lines = lineResults(Arrays.asList(line(10, 4, 100), line(11, 1, 80)));
		assertFree(p, 0, 0, lines.get(10L));
		assertFree(p, 1, 1, lines.get(11L));

		// 9 pieces: 3 free; M holds 1, the 2 others on S
		lines = lineResults(Arrays.asList(line(10, 8, 100), line(11, 1, 80)));
		assertFree(p, 2, 0, lines.get(10L));
		assertFree(p, 1, 0, lines.get(11L));
	}

	@Test
	@DisplayName("The pieces still to scan only on the cart's last line of the target")
	void hintOnLastLineOnly() {
		Promotion p = free("NAV21", PromotionScope.ITEM, 2, 1);
		p.setNavItemNo("N1");
		Map<Long, PriceCalculateResponseDTO> lines = lineResults(Arrays.asList(line(10, 1, 100), line(11, 1, 80)));
		for (PriceCalculateResponseDTO answer : lines.values()) {
			assertNull(answer.getPromotionId(), "2 pieces: not taken, the hint");
			assertEquals(p.getId(), answer.getFreePromotionId());
		}
		assertEquals(0, lines.get(10L).getFreeQuantityAvailable().intValue(), "first line");
		assertEquals(1, lines.get(11L).getFreeQuantityAvailable().intValue(), "last line: 1 more piece is free");
	}

	@Test
	@DisplayName("A line taken by its own row promotion does not count in its NAV item's Q (6b)")
	void rowLineLeavesNav() {
		Promotion nav = free("NAV21", PromotionScope.ITEM, 2, 1);
		nav.setNavItemNo("N1");
		Promotion row = percent("ROW20", PromotionScope.ITEM, 20);
		row.setItem(items.get(10L));
		Map<Long, PriceCalculateResponseDTO> lines = lineResults(Arrays.asList(line(10, 2, 100), line(11, 1, 80)));
		assertEquals(row.getId(), lines.get(10L).getPromotionId());
		assertEquals(20.0, lines.get(10L).getDiscountPercentage());
		assertNull(lines.get(11L).getPromotionId(), "N1 counts only M: 1 piece");
		assertNull(lines.get(11L).getFreeQuantity(), "1 piece, below N: no free piece, no hint (as alone)");
	}

	// ─── Group, section, famille, all items ───────────────────────

	@Test
	@DisplayName("Group of rows, minimum 3: 2 + 1 pieces of two items reach it, both lines get the discount")
	void groupMinimum() {
		Promotion group = percent("GRP10", PromotionScope.ITEM_GROUP, 10);
		group.setPromotionType(PromotionType.QUANTITY_PROMOTION);
		group.setMinimumQuantity(3);
		group.getGroupItems().addAll(Arrays.asList(items.get(30L), items.get(31L)));
		Map<Long, PriceCalculateResponseDTO> lines = lineResults(Arrays.asList(line(30, 2, 50), line(31, 1, 40)));
		assertEquals(group.getId(), lines.get(30L).getPromotionId());
		assertEquals(group.getId(), lines.get(31L).getPromotionId());
		assertEquals(10.0, lines.get(31L).getDiscountPercentage());
		assertNull(engine.calculateItemPrice(30L, BigDecimal.valueOf(2), null, codes()).getPromotionId(), "alone: 2 < 3");
	}

	@Test
	@DisplayName("Section, minimum 2: one piece of each of two items")
	void sectionMinimum() {
		Promotion section = percent("SEC15", PromotionScope.SECTION, 15);
		section.setPromotionType(PromotionType.QUANTITY_PROMOTION);
		section.setMinimumQuantity(2);
		section.setItemSection(sleeves);
		Map<Long, PriceCalculateResponseDTO> lines = lineResults(Arrays.asList(line(32, 1, 60), line(33, 1, 60)));
		assertEquals(section.getId(), lines.get(32L).getPromotionId());
		assertEquals(section.getId(), lines.get(33L).getPromotionId());
	}

	@Test
	@DisplayName("Famille buy 2 get 1 over two items: 1 free on the cheaper; with a row promotion on one, it leaves (B)")
	void familyAndLevels() {
		Promotion family = free("FAM21", PromotionScope.ITEM_FAMILY, 2, 1);
		family.setItemFamily(trousers);
		Map<Long, PriceCalculateResponseDTO> lines = lineResults(Arrays.asList(line(30, 2, 50), line(31, 1, 40)));
		assertFree(family, 0, 0, lines.get(30L));
		assertFree(family, 1, 0, lines.get(31L));

		Promotion row = percent("ROW20", PromotionScope.ITEM, 20);
		row.setItem(items.get(30L));
		lines = lineResults(Arrays.asList(line(30, 2, 50), line(31, 1, 40)));
		assertEquals(row.getId(), lines.get(30L).getPromotionId());
		assertNull(lines.get(31L).getPromotionId(), "the famille counts only item 31: 1 piece");
		assertNull(lines.get(31L).getFreeQuantity(), "below N: no free piece, no hint");
	}

	@Test
	@DisplayName("All items buy 3 get 1 over two items of two families: 4 pieces, 1 free on the cheapest")
	void allItems() {
		Promotion all = free("ALL31", PromotionScope.ALL_ITEMS, 3, 1);
		Map<Long, PriceCalculateResponseDTO> lines = lineResults(Arrays.asList(line(12, 2, 100), line(30, 2, 50)));
		assertFree(all, 0, 0, lines.get(12L));
		assertFree(all, 1, 0, lines.get(30L));
	}

	@Test
	@DisplayName("Decision C: a line counted by a free promotion that gives a free piece takes no other promotion; below N + 1 it does")
	void zeroShareTaken() {
		Promotion nav = free("NAV21", PromotionScope.ITEM, 2, 1);
		nav.setNavItemNo("N1");
		Promotion family = percent("FAM10", PromotionScope.ITEM_FAMILY, 10);
		family.setItemFamily(shirts);
		Map<Long, PriceCalculateResponseDTO> lines = lineResults(Arrays.asList(line(10, 1, 100), line(11, 2, 80)));
		assertFree(nav, 0, 0, lines.get(10L));
		assertNull(lines.get(10L).getDiscountPercentage(), "full price, not the famille's 10 %");

		lines = lineResults(Arrays.asList(line(10, 1, 100), line(11, 1, 80)));
		assertEquals(family.getId(), lines.get(10L).getPromotionId(), "2 pieces: no free piece, the famille's 10 %");
		assertEquals(family.getId(), lines.get(11L).getPromotionId());
		assertEquals(0, lines.get(10L).getFreeQuantityAvailable().intValue(), "the hint on the last line only");
		assertEquals(1, lines.get(11L).getFreeQuantityAvailable().intValue(), "2 pieces across the lines: 1 more is free");
		assertTrue(lines.get(11L).getAcrossLines(), "alone (1 piece, below N) it had no hint");
	}

	// ─── One line, other carts ────────────────────────────────────

	@Test
	@DisplayName("One line (or only the line asked): no line results, priced exactly as before")
	void oneLineAsBefore() {
		Promotion p = free("NAV21", PromotionScope.ITEM, 2, 1);
		p.setNavItemNo("N1");
		PriceCalculateResponseDTO withCart = engine.calculateItemPrice(10L, BigDecimal.valueOf(3), null, codes(),
				Collections.singletonList(line(10, 1, 100)));
		PriceCalculateResponseDTO alone = engine.calculateItemPrice(10L, BigDecimal.valueOf(3), null, codes());
		assertEquals(alone, withCart, "the line's own quantity (3) wins; no acrossLines");
		assertFree(p, 1, 0, alone);
		// The cart pass answers the one line too (was: none), priced alone
		List<PriceCalculateResponseDTO> results = engine.calculateCartPromotion(300.0,
				Collections.singletonList(line(10, 3, 100)), codes(), null).getLineResults();
		assertEquals(1, results.size());
		assertFalse(results.get(0).getAcrossLines());
		results.get(0).setAcrossLines(null);
		assertEquals(alone, results.get(0), "the same answer as the line priced alone");
	}

	@Test
	@DisplayName("The cart pass answers every line, whether or not its price depends on the other lines")
	void everyLineAnswered() {
		Promotion p = free("NAV21", PromotionScope.ITEM, 2, 1);
		p.setNavItemNo("N1");
		Map<Long, PriceCalculateResponseDTO> lines = lineResults(
				Arrays.asList(line(10, 1, 100), line(11, 2, 80), line(30, 1, 50), line(70, 1, 20)));
		assertEquals(Arrays.asList(10L, 11L, 30L, 70L), new ArrayList<>(lines.keySet()));
		assertTrue(lines.get(10L).getAcrossLines());
		assertFalse(lines.get(30L).getAcrossLines());
		assertFalse(lines.get(70L).getAcrossLines());
	}

	@Test
	@DisplayName("Screen test case 6: 6 pieces / 2 free, then the other line removed: the line left has no free piece, its hint back")
	void groupDissolved() {
		Promotion p = free("NAV21", PromotionScope.ITEM, 2, 1);
		p.setNavItemNo("N1");
		item(13, "N1", shirts, null, 100.0); // taille 36, the same price as taille 38 (item 10)
		Map<Long, PriceCalculateResponseDTO> lines = lineResults(Arrays.asList(line(13, 2, 100), line(10, 4, 100)));
		assertFree(p, 2, 0, lines.get(13L));
		assertFree(p, 0, 0, lines.get(10L));

		lines = lineResults(Collections.singletonList(line(13, 2, 100)));
		PriceCalculateResponseDTO left = lines.get(13L);
		assertNull(left.getPromotionId(), "2 pieces alone: not taken");
		assertEquals(0, left.getFreeQuantity().intValue(), "no free piece");
		assertEquals(1, left.getFreeQuantityAvailable().intValue(), "the hint: 1 more piece free");
		assertEquals(p.getId(), left.getFreePromotionId());
		assertFalse(left.getAcrossLines());
	}

	@Test
	@DisplayName("Lines of no common target: each line as alone (acrossLines false)")
	void unrelatedLines() {
		Promotion p = free("NAV21", PromotionScope.ITEM, 2, 1);
		p.setNavItemNo("N1");
		Map<Long, PriceCalculateResponseDTO> lines = lineResults(Arrays.asList(line(10, 3, 100), line(30, 1, 50)));
		assertFree(p, 1, 0, lines.get(10L));
		assertFalse(lines.get(10L).getAcrossLines());
		assertFalse(lines.get(30L).getAcrossLines());
	}

	// ─── Cross-product ────────────────────────────────────────────

	@Test
	@DisplayName("Cross-product buy side, rule 6a: 1 + 1 pieces of two buy items reach N = 2 (each line's floor gave 0)")
	void crossBuySideAcrossLines() {
		Promotion x = free("X21", PromotionScope.ITEM_GROUP, 2, 1);
		x.getGroupItems().addAll(Arrays.asList(items.get(70L), items.get(71L)));
		x.setGetItem(items.get(30L));
		List<CrossProductAdjustmentDTO> adj = cross(Arrays.asList(line(70, 1, 20), line(71, 1, 20), line(30, 1, 50)));
		assertEquals(1, adj.size());
		assertEquals(1, adj.get(0).getEntitledUnits().intValue());
		assertEquals(1, adj.get(0).getFreeQuantity().intValue());
		assertEquals(Collections.singletonMap(30L, 1), adj.get(0).getFreeByItemId());
	}

	@Test
	@DisplayName("Free item on any variant of N1: only scanned pieces, cheapest first; none scanned: the hint names N1")
	void crossFreeOnAnyVariant() {
		Promotion x = free("XNAV", PromotionScope.ITEM, 2, 1);
		x.setItem(items.get(70L));
		x.setGetNavItemNo("N1");
		List<CrossProductAdjustmentDTO> adj = cross(Arrays.asList(line(70, 2, 20), line(10, 1, 100), line(11, 1, 80)));
		assertEquals("N1", adj.get(0).getGetNavItemNo());
		assertEquals(Collections.singletonMap(11L, 1), adj.get(0).getFreeByItemId(), "the cheaper variant");
		assertEquals(0, adj.get(0).getFreeQuantityAvailable().intValue());

		adj = cross(Arrays.asList(line(70, 4, 20), line(10, 1, 100), line(11, 1, 80)));
		Map<Long, Integer> both = new LinkedHashMap<>();
		both.put(11L, 1);
		both.put(10L, 1);
		assertEquals(both, adj.get(0).getFreeByItemId());

		adj = cross(Collections.singletonList(line(70, 2, 20)));
		assertEquals(0, adj.get(0).getFreeQuantity().intValue(), "never a piece added");
		assertEquals(1, adj.get(0).getFreeQuantityAvailable().intValue());
		assertEquals("Chemise N1", adj.get(0).getGetItemName());
	}

	// ─── Helpers ─────────────────────────────────────────────────

	private Map<Long, PriceCalculateResponseDTO> lineResults(List<CartCalculateRequestDTO.CartItemDTO> cart) {
		CartCalculateResponseDTO answer = engine.calculateCartPromotion(500.0, cart, codes(), null);
		return answer.getLineResults().stream().collect(Collectors.toMap(PriceCalculateResponseDTO::getItemId, r -> r,
				(a, b) -> a, LinkedHashMap::new));
	}

	private List<CrossProductAdjustmentDTO> cross(List<CartCalculateRequestDTO.CartItemDTO> cart) {
		return engine.calculateCartPromotion(500.0, cart, codes()).getCrossProductAdjustments();
	}

	private static void assertFree(Promotion p, int free, int available, PriceCalculateResponseDTO r) {
		assertEquals(p.getId(), r.getPromotionId(), "taken by " + p.getCode());
		assertEquals(free, r.getFreeQuantity().intValue(), "free pieces");
		assertEquals(available, r.getFreeQuantityAvailable().intValue(), "pieces still to scan");
	}

	private static List<String> codes() {
		return new ArrayList<>();
	}

	private static ItemFamily family(long id) {
		ItemFamily f = new ItemFamily();
		f.setId(id);
		return f;
	}

	private static ItemSection section(long id, String code) {
		ItemSection s = new ItemSection();
		s.setId(id);
		s.setCode(code);
		return s;
	}

	private void item(long id, String navItemNo, ItemFamily family, String sectionCode, double price) {
		Item i = new Item();
		i.setId(id);
		i.setItemCode("I" + id);
		i.setName(navItemNo != null ? "Chemise " + navItemNo : "Item " + id);
		i.setNavItemNo(navItemNo);
		i.setItemFamily(family);
		i.setSectionCode(sectionCode);
		i.setUnitPrice(price);
		items.put(id, i);
	}

	private Promotion promotion(String code, PromotionScope scope) {
		Promotion p = new Promotion();
		p.setId(nextId++);
		p.setCode(code);
		p.setName(code);
		p.setScope(scope);
		p.setPriority(0);
		p.setRequiresCode(false);
		p.setActive(true);
		promotions.add(p);
		return p;
	}

	private Promotion free(String code, PromotionScope scope, int n, int m) {
		Promotion p = promotion(code, scope);
		p.setPromotionType(PromotionType.QUANTITY_PROMOTION);
		p.setBenefitType(PromotionBenefitType.FREE_QUANTITY);
		p.setMinimumQuantity(n);
		p.setFreeQuantity(m);
		return p;
	}

	private Promotion percent(String code, PromotionScope scope, double percentage) {
		Promotion p = promotion(code, scope);
		p.setPromotionType(PromotionType.SIMPLE_DISCOUNT);
		p.setBenefitType(PromotionBenefitType.PERCENTAGE_DISCOUNT);
		p.setDiscountPercentage(percentage);
		return p;
	}

	private static CartCalculateRequestDTO.CartItemDTO line(long itemId, int quantity, double unitPrice) {
		return new CartCalculateRequestDTO.CartItemDTO(itemId, BigDecimal.valueOf(quantity), unitPrice);
	}

	// ─── In-memory repositories mirroring the JPQL of PromotionRepository ───

	private List<Promotion> query(Predicate<Promotion> filter, LocalDate today) {
		return promotions.stream().filter(p -> Boolean.TRUE.equals(p.getActive()))
				.filter(p -> p.getStartDate() == null || !p.getStartDate().isAfter(today))
				.filter(p -> p.getEndDate() == null || !p.getEndDate().isBefore(today)).filter(filter)
				.sorted(Comparator.comparing(Promotion::getPriority).reversed()).collect(Collectors.toList());
	}

	private static boolean hasId(_BaseEntity entity, Object id) {
		return entity != null && entity.getId() != null && entity.getId().equals(id);
	}

	private PromotionRepository promotionRepository() {
		return stub(PromotionRepository.class, (method, a) -> {
			switch (method) {
				case "findActiveByItemId": return query(p -> hasId(p.getItem(), a[0]), (LocalDate) a[1]);
				case "findActiveByNavItemNo":
					return query(p -> p.getScope() == PromotionScope.ITEM && a[0].equals(p.getNavItemNo()), (LocalDate) a[1]);
				case "findActiveByGroupItemId": return query(p -> p.getScope() == PromotionScope.ITEM_GROUP
						&& p.getGroupItems().stream().anyMatch(gi -> hasId(gi, a[0])), (LocalDate) a[1]);
				case "findActiveByGroupNavItemNo":
					return query(p -> p.getScope() == PromotionScope.ITEM_GROUP && p.getGroupNavItemNos().contains(a[0]),
							(LocalDate) a[1]);
				case "findActiveBySectionCode":
					return query(p -> p.getScope() == PromotionScope.SECTION && p.getItemSection() != null
							&& a[0].equals(p.getItemSection().getCode()), (LocalDate) a[1]);
				case "findActiveByCategoryCode": return query(p -> false, (LocalDate) a[1]);
				case "findActiveByItemSubFamilyId": return query(p -> hasId(p.getItemSubFamily(), a[0]), (LocalDate) a[1]);
				case "findActiveByItemFamilyId": return query(p -> hasId(p.getItemFamily(), a[0]), (LocalDate) a[1]);
				case "findActiveByScope": return query(p -> p.getScope() == a[0], (LocalDate) a[1]);
				case "findActiveCrossProductPromotions":
					return query(p -> p.getGetItem() != null || p.getGetNavItemNo() != null, (LocalDate) a[0]);
				default: return UNHANDLED;
			}
		});
	}

	private ItemRepository itemRepository() {
		return stub(ItemRepository.class, (method, a) -> {
			switch (method) {
				case "findById": return Optional.ofNullable(items.get(a[0]));
				case "findAllById": {
					List<Item> found = new ArrayList<>();
					for (Object id : (Iterable<?>) a[0]) {
						if (items.containsKey(id)) found.add(items.get(id));
					}
					return found;
				}
				case "findNavItemNames": {
					List<Object[]> rows = new ArrayList<>();
					for (Object no : (Collection<?>) a[0]) {
						items.values().stream().filter(i -> no.equals(i.getNavItemNo())).findFirst()
								.ifPresent(i -> rows.add(new Object[] { no, i.getName() }));
					}
					return rows;
				}
				default: return UNHANDLED;
			}
		});
	}

	private interface Handler {
		Object handle(String method, Object[] args);
	}

	private static final Object UNHANDLED = new Object();

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy == args[0];
				case "toString": return type.getSimpleName() + "Stub";
				default: break;
			}
			Object result = handler.handle(method.getName(), args == null ? new Object[0] : args);
			if (result == UNHANDLED) {
				throw new UnsupportedOperationException("Unexpected call: " + type.getSimpleName() + "." + method.getName());
			}
			return result;
		});
	}

	private void inject(Object target, String fieldName, Object value) throws Exception {
		Field field = PromotionCalculationService.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
