package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
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
import com.digithink.zsretail.store.dto.CrossProductAdjustmentDTO;
import com.digithink.zsretail.store.dto.PriceCalculateResponseDTO;

/**
 * 3.1.0 (ZEN), promotions for variants, step 1: a promotion on a NAV item number matches every item (variant) carrying
 * it, also one synced after the promotion; ITEM on the row > ITEM on the NAV number > groups (rows and NAV numbers at
 * one level, priority decides). An item without a NAV item number (EMTOP, Happyness) never reads the new lookups and
 * gets what it got before. Plain JUnit with in-memory stubs that mirror the JPQL of PromotionRepository, as
 * PromotionCategorySectionScopeTest.
 */
class PromotionNavTargetTest {

	private final List<Promotion> promotions = new ArrayList<>();
	private final Map<Long, Item> items = new HashMap<>();
	private PromotionCalculationService engine;
	private long nextId;
	/** Calls of findActiveByNavItemNo / findActiveByGroupNavItemNo. */
	private int navReads;

	private final ItemFamily family = family(1);

	@BeforeEach
	void setUp() throws Exception {
		nextId = 100;
		engine = new PromotionCalculationService();
		inject(engine, PromotionCalculationService.class, "itemRepository", itemRepository());
		inject(engine, PromotionCalculationService.class, "promotionRepository", promotionRepository());
		inject(engine, PromotionCalculationService.class, "customerService", new CustomerService() {
			@Override
			public Customer getDefaultCustomer() {
				return new Customer();
			}
		});
		PricingResult erp = new PricingResult();
		erp.setUnitPrice(100.0);
		erp.setPriceIncludesVat(true);
		erp.setSource("BASE_PRICE");
		inject(engine, PromotionCalculationService.class, "pricingService", new PricingService() {
			@Override
			public PricingResult calculateItemPrice(Item item, Customer customer, BigDecimal quantity,
					String responsibilityCenter) {
				return erp;
			}
		});
		item(10, "N1"); // N1 in size S
		item(11, "N1"); // N1 in size M
		item(12, "N2");
		item(20, null); // as EMTOP / Happyness: no NAV item number
		item(21, " "); // blank counts as none
		item(40, null); // a benefit item
	}

	@Test
	@DisplayName("A promotion on NAV item N1 applies to every variant of N1, never to N2 nor to an item without one")
	void everyVariant() {
		Promotion nav = navItem("NAV", "N1", 20);
		assertPromotion(nav, price(10));
		assertPromotion(nav, price(11));
		assertPromotion(null, price(12));
		assertPromotion(null, price(20));
		assertPromotion(null, price(21));
	}

	@Test
	@DisplayName("A variant synced after the promotion was made is in it (nothing expanded at save)")
	void variantSyncedLater() {
		Promotion nav = navItem("NAV", "N1", 20);
		item(13, "N1");
		assertPromotion(nav, price(13));
	}

	@Test
	@DisplayName("Single-row ITEM beats NAV ITEM on its row (whatever the priority); NAV ITEM beats groups and family")
	void order() {
		Promotion nav = navItem("NAV", "N1", 20);
		nav.setPriority(9);
		Promotion row = promotion("ROW", PromotionScope.ITEM, 5);
		row.setItem(items.get(10L));
		Promotion group = promotion("GRP", PromotionScope.ITEM_GROUP, 50);
		group.setPriority(99);
		group.getGroupItems().add(items.get(11L));
		Promotion fam = promotion("FAM", PromotionScope.ITEM_FAMILY, 60);
		fam.setPriority(99);
		fam.setItemFamily(family);

		assertPromotion(row, price(10), "the row's own promotion");
		assertPromotion(nav, price(11), "the NAV number before the group and the family");
		promotions.remove(nav);
		assertPromotion(group, price(11));
		assertPromotion(fam, price(12));
	}

	@Test
	@DisplayName("A group of rows and a group of NAV numbers are one level: priority decides; a mixed group matches both ways")
	void groupsOneLevel() {
		Promotion rows = promotion("ROWS", PromotionScope.ITEM_GROUP, 10);
		rows.getGroupItems().add(items.get(10L));
		rows.setPriority(1);
		Promotion navs = promotion("NAVS", PromotionScope.ITEM_GROUP, 5);
		navs.getGroupNavItemNos().add("N1");
		navs.setPriority(3);
		assertPromotion(navs, price(10), "higher priority");
		assertPromotion(navs, price(11));

		rows.setPriority(4);
		assertPromotion(rows, price(10), "higher priority");
		assertPromotion(navs, price(11), "item 11 is not a row of ROWS");

		Promotion mixed = promotion("MIXED", PromotionScope.ITEM_GROUP, 30);
		mixed.setPriority(8);
		mixed.getGroupItems().add(items.get(20L));
		mixed.getGroupItems().add(items.get(10L));
		mixed.getGroupNavItemNos().add("N1");
		assertPromotion(mixed, price(10), "listed twice (row and NAV number): same answer");
		assertPromotion(mixed, price(11));
		assertPromotion(mixed, price(20));
	}

	@Test
	@DisplayName("No NAV item number (EMTOP, Happyness): the new lookups are never read, the cascade as before")
	void noNavNoChange() {
		navItem("NAV", "N1", 50);
		Promotion navs = promotion("NAVS", PromotionScope.ITEM_GROUP, 50);
		navs.getGroupNavItemNos().add("N1");
		Promotion row = promotion("ROW", PromotionScope.ITEM, 5);
		row.setItem(items.get(20L));
		Promotion group = promotion("GRP", PromotionScope.ITEM_GROUP, 6);
		group.getGroupItems().add(items.get(21L));
		Promotion fam = promotion("FAM", PromotionScope.ITEM_FAMILY, 7);
		fam.setItemFamily(family);

		assertPromotion(row, price(20));
		assertPromotion(group, price(21));
		promotions.remove(row);
		assertPromotion(fam, price(20));

		Promotion cross = crossProduct("XFAM", PromotionScope.ITEM_FAMILY);
		cross.setItemFamily(family);
		List<CrossProductAdjustmentDTO> adj = cart(line(20, 2), line(40, 1));
		assertEquals(1, adj.size());
		assertEquals(0, navReads, "no NAV lookup for an item without a NAV item number");
	}

	@Test
	@DisplayName("Lookups filter on the scope: a FAMILY promotion that kept a NAV number never matches as ITEM")
	void scopeFilter() {
		Promotion stray = promotion("STRAY", PromotionScope.ITEM_FAMILY, 30);
		stray.setItemFamily(family(2));
		stray.setNavItemNo("N1");
		Promotion strayGroup = promotion("STRAYG", PromotionScope.ITEM_FAMILY, 30);
		strayGroup.setItemFamily(family(2));
		strayGroup.getGroupNavItemNos().add("N1");
		assertPromotion(null, price(10));
	}

	@Test
	@DisplayName("A benefit on a NAV number never discounts the buy line; the cart pass skips it (step 2)")
	void benefitOnNavNumber() {
		Promotion x = crossProduct("XNAV", PromotionScope.ITEM);
		x.setNavItemNo("N1");
		x.setGetItem(null);
		x.setGetNavItemNo("N2");
		x.setBenefitType(PromotionBenefitType.PERCENTAGE_DISCOUNT);
		x.setFreeQuantity(null);
		x.setDiscountPercentage(50.0);
		x.setMinimumQuantity(1);

		assertPromotion(null, price(10), "the buy line is not discounted");
		assertTrue(cart(line(10, 2), line(12, 1)).isEmpty(), "no adjustment before step 2");
	}

	@Test
	@DisplayName("Cross-product buy side on NAV item N1 or a group holding N1: the lines of its variants count")
	void buySide() {
		Promotion x = crossProduct("XNAV", PromotionScope.ITEM);
		x.setNavItemNo("N1");
		List<CrossProductAdjustmentDTO> adj = cart(line(10, 2), line(11, 2), line(12, 2), line(40, 1));
		assertEquals(1, adj.size());
		assertEquals(2, adj.get(0).getEntitledUnits().intValue(), "items 10 and 11 count (per line, as before), 12 not");

		x.setScope(PromotionScope.ITEM_GROUP);
		x.setNavItemNo(null);
		x.getGroupNavItemNos().add("N2");
		adj = cart(line(10, 2), line(12, 4), line(40, 1));
		assertEquals(2, adj.get(0).getEntitledUnits().intValue(), "only item 12 (N2) counts");
	}

	// ─── Helpers ─────────────────────────────────────────────────

	private static ItemFamily family(long id) {
		ItemFamily f = new ItemFamily();
		f.setId(id);
		return f;
	}

	private void item(long id, String navItemNo) {
		Item i = new Item();
		i.setId(id);
		i.setItemCode("I" + id);
		i.setName("Item " + id);
		i.setItemFamily(family);
		i.setNavItemNo(navItemNo);
		i.setUnitPrice(100.0);
		items.put(id, i);
	}

	private Promotion navItem(String code, String navItemNo, double percentage) {
		Promotion p = promotion(code, PromotionScope.ITEM, percentage);
		p.setNavItemNo(navItemNo);
		return p;
	}

	/** Buy 2 of the target, get 1 of item 40 free. */
	private Promotion crossProduct(String code, PromotionScope scope) {
		Promotion p = promotion(code, scope, 0);
		p.setPromotionType(PromotionType.QUANTITY_PROMOTION);
		p.setBenefitType(PromotionBenefitType.FREE_QUANTITY);
		p.setDiscountPercentage(null);
		p.setFreeQuantity(1);
		p.setMinimumQuantity(2);
		p.setGetItem(items.get(40L));
		return p;
	}

	private Promotion promotion(String code, PromotionScope scope, double percentage) {
		Promotion p = new Promotion();
		p.setId(nextId++);
		p.setCode(code);
		p.setName(code);
		p.setPromotionType(PromotionType.SIMPLE_DISCOUNT);
		p.setScope(scope);
		p.setBenefitType(PromotionBenefitType.PERCENTAGE_DISCOUNT);
		p.setDiscountPercentage(percentage);
		p.setPriority(0);
		p.setRequiresCode(false);
		p.setActive(true);
		promotions.add(p);
		return p;
	}

	private static CartCalculateRequestDTO.CartItemDTO line(long itemId, int quantity) {
		CartCalculateRequestDTO.CartItemDTO l = new CartCalculateRequestDTO.CartItemDTO();
		l.setItemId(itemId);
		l.setQuantity(BigDecimal.valueOf(quantity));
		return l;
	}

	private List<CrossProductAdjustmentDTO> cart(CartCalculateRequestDTO.CartItemDTO... lines) {
		return engine.calculateCartPromotion(500.0, Arrays.asList(lines), new ArrayList<>()).getCrossProductAdjustments();
	}

	private PriceCalculateResponseDTO price(long itemId) {
		return engine.calculateItemPrice(itemId, BigDecimal.ONE, null, new ArrayList<>());
	}

	private static void assertPromotion(Promotion expected, PriceCalculateResponseDTO r) {
		assertPromotion(expected, r, "");
	}

	private static void assertPromotion(Promotion expected, PriceCalculateResponseDTO r, String why) {
		if (expected == null) {
			assertNull(r.getPromotionId(), "expected no promotion, got " + r.getPromotionCode() + " " + why);
		} else {
			assertEquals(expected.getId(), r.getPromotionId(),
					"expected " + expected.getCode() + ", got " + r.getPromotionCode() + " " + why);
		}
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
				case "findActiveByGroupItemId": return query(p -> p.getScope() == PromotionScope.ITEM_GROUP
						&& p.getGroupItems().stream().anyMatch(gi -> hasId(gi, a[0])), (LocalDate) a[1]);
				case "findActiveByNavItemNo":
					navReads++;
					return query(p -> p.getScope() == PromotionScope.ITEM && a[0].equals(p.getNavItemNo()),
							(LocalDate) a[1]);
				case "findActiveByGroupNavItemNo":
					navReads++;
					return query(p -> p.getScope() == PromotionScope.ITEM_GROUP && p.getGroupNavItemNos().contains(a[0]),
							(LocalDate) a[1]);
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

	private static void inject(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
