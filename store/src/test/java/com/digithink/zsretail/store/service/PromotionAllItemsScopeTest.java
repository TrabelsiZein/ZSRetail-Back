package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model.Promotion;
import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.model.enumeration.PromotionBenefitType;
import com.digithink.zsretail.core.model.enumeration.PromotionScope;
import com.digithink.zsretail.core.model.enumeration.PromotionType;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.PromotionRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.CustomerService;
import com.digithink.zsretail.core.service.PricingService;
import com.digithink.zsretail.core.service.PromotionService;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.dto.CartCalculateRequestDTO;
import com.digithink.zsretail.store.dto.CrossProductAdjustmentDTO;
import com.digithink.zsretail.store.dto.PriceCalculateResponseDTO;

/**
 * ALL_ITEMS promotion scope: least specific scope, used only when no item, group,
 * subfamily or family promotion is eligible. Plain JUnit with in-memory stubs that
 * mirror the JPQL filters of PromotionRepository (no Spring, no database).
 */
class PromotionAllItemsScopeTest {

	private final List<Promotion> promotions = new ArrayList<>();
	private final Map<Long, Item> items = new HashMap<>();
	private PromotionCalculationService engine;
	private PricingResult erpPricing;
	private long nextId;

	private final ItemFamily familyF = family(1);
	private final ItemFamily familyG = family(2);
	private final ItemSubFamily subFamily = subFamily(5);

	@BeforeEach
	void setUp() throws Exception {
		promotions.clear();
		items.clear();
		nextId = 100;
		erpPricing(null);
		engine = new PromotionCalculationService();
		inject(engine, PromotionCalculationService.class, "itemRepository", itemRepository());
		inject(engine, PromotionCalculationService.class, "promotionRepository", promotionRepository());
		inject(engine, PromotionCalculationService.class, "customerService", new CustomerService() {
			@Override
			public Customer getDefaultCustomer() {
				return new Customer();
			}
		});
		inject(engine, PromotionCalculationService.class, "pricingService", new PricingService() {
			@Override
			public PricingResult calculateItemPrice(Item item, Customer customer, BigDecimal quantity, String responsibilityCenter) {
				return erpPricing;
			}
		});
		item(10, familyF, subFamily);
		item(11, familyG, null);
		item(12, null, null);
		item(20, null, null);
	}

	@Test
	@DisplayName("A family promotion beats an all-items promotion, even with lower priority and value")
	void familyBeatsAllItems() {
		Promotion family5 = promotion("FAM5", PromotionScope.ITEM_FAMILY, 5, 0);
		family5.setItemFamily(familyF);
		Promotion all10 = promotion("ALL10", PromotionScope.ALL_ITEMS, 10, 100);
		assertPromotion(family5, price(10, 1));
		assertPromotion(all10, price(11, 1));
		assertPromotion(all10, price(12, 1));
		assertEquals(10.0, price(12, 1).getDiscountPercentage().doubleValue());
	}

	@Test
	@DisplayName("A family promotion that is not eligible falls through to all-items")
	void ineligibleFamilyFallsThrough() {
		Promotion family5 = promotion("FAM5", PromotionScope.ITEM_FAMILY, 5, 0);
		family5.setItemFamily(familyF);
		Promotion all10 = promotion("ALL10", PromotionScope.ALL_ITEMS, 10, 100);

		family5.setDayOfWeek(LocalDate.now().getDayOfWeek().plus(1).name());
		assertPromotion(all10, price(10, 1));
		family5.setDayOfWeek(null);

		family5.setRequiresCode(true);
		assertPromotion(all10, price(10, 1));
		assertPromotion(family5, price(10, 1, "FAM5"));
		family5.setRequiresCode(false);

		family5.setPromotionType(PromotionType.QUANTITY_PROMOTION);
		family5.setMinimumQuantity(3);
		assertPromotion(all10, price(10, 1));
		assertPromotion(family5, price(10, 3));
	}

	@Test
	@DisplayName("Item, group and subfamily promotions all beat all-items")
	void moreSpecificScopesWin() {
		promotion("ALL10", PromotionScope.ALL_ITEMS, 10, 100);
		Promotion item3 = promotion("ITEM3", PromotionScope.ITEM, 3, 0);
		item3.setItem(items.get(10L));
		assertPromotion(item3, price(10, 1));

		Promotion group4 = promotion("GRP4", PromotionScope.ITEM_GROUP, 4, 0);
		group4.getGroupItems().add(items.get(11L));
		assertPromotion(group4, price(11, 1));

		Promotion sub6 = promotion("SUB6", PromotionScope.ITEM_SUBFAMILY, 6, 0);
		sub6.setItemSubFamily(subFamily);
		promotions.remove(item3);
		assertPromotion(sub6, price(10, 1));
	}

	@Test
	@DisplayName("Between all-items promotions: priority first, then the higher percentage")
	void priorityThenValue() {
		Promotion p1 = promotion("A_P1_10", PromotionScope.ALL_ITEMS, 10, 1);
		promotion("A_P0_20", PromotionScope.ALL_ITEMS, 20, 0);
		assertPromotion(p1, price(12, 1));

		promotions.clear();
		promotion("B_P0_10", PromotionScope.ALL_ITEMS, 10, 0);
		Promotion b20 = promotion("B_P0_20", PromotionScope.ALL_ITEMS, 20, 0);
		assertPromotion(b20, price(12, 1));
	}

	@Test
	@DisplayName("All-items respects promo codes, the active flag and the end date")
	void codesActiveAndDates() {
		Promotion coded = promotion("ALLCODE", PromotionScope.ALL_ITEMS, 10, 0);
		coded.setRequiresCode(true);
		assertPromotion(null, price(12, 1));
		assertPromotion(coded, price(12, 1, "allcode"));

		coded.setRequiresCode(false);
		coded.setActive(false);
		assertPromotion(null, price(12, 1));

		coded.setActive(true);
		coded.setEndDate(LocalDate.now().minusDays(1));
		assertPromotion(null, price(12, 1));
	}

	@Test
	@DisplayName("The SalesDiscount comparison is unchanged: the better discount wins")
	void erpComparisonUnchanged() {
		Promotion all10 = promotion("ALL10", PromotionScope.ALL_ITEMS, 10, 0);
		erpPricing(15.0);
		PriceCalculateResponseDTO r = price(12, 1);
		assertNull(r.getPromotionId());
		assertEquals("SALES_DISCOUNT", r.getSource());
		assertEquals(15.0, r.getDiscountPercentage().doubleValue());
		erpPricing(5.0);
		assertPromotion(all10, price(12, 1));
	}

	@Test
	@DisplayName("An all-items quantity promotion checks the quantity per line; the free piece is one of the line's pieces (2026-10-12)")
	void quantityPromotionPerLine() {
		Promotion q = freePromotion("ALLQTY", 3, 1);
		assertPromotion(null, price(12, 2));
		PriceCalculateResponseDTO r = price(12, 3);
		assertPromotion(null, r); // 0 free piece: the promotion does not take the line (2026-10-12)
		assertEquals(0, r.getFreeQuantity().intValue(), "3 pieces, buy 3 get 1: none free yet (was 1 added on top)");
		assertEquals(1, r.getFreeQuantityAvailable().intValue(), "1 more piece is free");
		assertEquals("ALLQTY", r.getFreePromotionName());
		r = price(12, 4);
		assertPromotion(q, r);
		assertEquals(1, r.getFreeQuantity().intValue());
		assertEquals(0, r.getFreeQuantityAvailable().intValue());
		assertEquals(1, price(12, 7).getFreeQuantity().intValue(), "once per line");
	}

	@Test
	@DisplayName("Free pieces, buy 2 get 1 (2026-10-12): Q = 1, 2, 3, 4, 6 gives 0, 0, 1, 1, 1 free; 2.5 none")
	void freeTable() {
		Promotion q = freePromotion("B2G1", 2, 1);
		int[] pieces = { 1, 2, 3, 4, 6 };
		int[] free = { 0, 0, 1, 1, 1 };
		for (int i = 0; i < pieces.length; i++) {
			assertEquals(free[i], PromotionCalculationService.freePieces(q, BigDecimal.valueOf(pieces[i])), "Q = " + pieces[i]);
		}
		assertEquals(0, PromotionCalculationService.freePieces(q, new BigDecimal("2.5")));
		assertEquals(0, PromotionCalculationService.freePieces(q, new BigDecimal("3.5")));
		assertEquals(1, PromotionCalculationService.freePieces(q, new BigDecimal("3.000")));

		assertPromotion(null, price(12, 1));
		assertEquals(0, price(12, 2).getFreeQuantity().intValue());
		assertEquals(1, price(12, 3).getFreeQuantity().intValue());
		assertEquals(1, price(12, 4).getFreeQuantity().intValue());
		assertEquals(1, price(12, 6).getFreeQuantity().intValue());
		PriceCalculateResponseDTO decimal = engine.calculateItemPrice(12L, new BigDecimal("2.5"), null, new ArrayList<>());
		assertPromotion(null, decimal);
		assertNull(decimal.getFreeQuantity());
	}

	@Test
	@DisplayName("Buy 2 get 1 at Q = 2 with a NAV discount of 10 %: the 10 % applies, 0 free, hint 1")
	void freeAtThresholdKeepsSalesDiscount() {
		freePromotion("B2G1", 2, 1);
		erpPricing(10.0);
		PriceCalculateResponseDTO r = price(12, 2);
		assertPromotion(null, r);
		assertEquals("SALES_DISCOUNT", r.getSource());
		assertEquals(10.0, r.getDiscountPercentage().doubleValue(), 0.0);
		assertHint(r, 0, 1);
	}

	@Test
	@DisplayName("Buy 2 get 1 at Q = 2 with another percent promotion in a wider scope: that promotion applies, 0 free, hint 1")
	void freeAtThresholdLetsAnotherPromotionApply() {
		Promotion family = freePromotion("B2G1", 2, 1);
		family.setScope(PromotionScope.ITEM_FAMILY);
		family.setItemFamily(familyF);
		Promotion all15 = promotion("ALL15", PromotionScope.ALL_ITEMS, 15, 0);
		PriceCalculateResponseDTO r = price(10, 2);
		assertPromotion(all15, r);
		assertEquals(15.0, r.getDiscountPercentage().doubleValue(), 0.0);
		assertHint(r, 0, 1);

		r = price(10, 3);
		assertPromotion(family, r);
		assertNull(r.getDiscountPercentage(), "the free promotion takes the line from Q = 3, the 15 % does not stack");
		assertHint(r, 1, 0);
	}

	@Test
	@DisplayName("Buy 2 get 1 at Q = 3 with a NAV discount of 10 %: 1 free, no sales discount, hint 0")
	void freeAboveThresholdReplacesSalesDiscount() {
		Promotion q = freePromotion("B2G1", 2, 1);
		erpPricing(10.0);
		PriceCalculateResponseDTO r = price(12, 3);
		assertPromotion(q, r);
		assertNull(r.getDiscountPercentage());
		assertHint(r, 1, 0);
	}

	@Test
	@DisplayName("Buy 2 get 1 at Q = 1: nothing from the free promotion, no hint; the NAV discount applies")
	void freeBelowThresholdNoHint() {
		freePromotion("B2G1", 2, 1);
		erpPricing(10.0);
		PriceCalculateResponseDTO r = price(12, 1);
		assertPromotion(null, r);
		assertEquals(10.0, r.getDiscountPercentage().doubleValue(), 0.0);
		assertNull(r.getFreeQuantity());
		assertNull(r.getFreeQuantityAvailable());
		assertNull(r.getFreePromotionName());
	}

	/** The free promotion B2G1 named, with these free pieces and these still to scan. */
	private static void assertHint(PriceCalculateResponseDTO r, int free, int available) {
		assertEquals(free, r.getFreeQuantity().intValue(), "free pieces");
		assertEquals(available, r.getFreeQuantityAvailable().intValue(), "free pieces still to scan");
		assertEquals("B2G1", r.getFreePromotionName());
	}

	@Test
	@DisplayName("A free quantity without its N (2026-10-12): no free piece, and the line keeps its other price (SalesDiscount, another promotion)")
	void freeWithoutMinimum() {
		Promotion q = freePromotion("NO_N", 0, 1);
		q.setMinimumQuantity(null);
		q.setPriority(9);
		assertEquals(0, PromotionCalculationService.freePieces(q, BigDecimal.valueOf(5)));
		assertPromotion(null, price(12, 5));
		erpPricing(15.0);
		assertEquals("SALES_DISCOUNT", price(12, 5).getSource());
		erpPricing(null);
		Promotion all10 = promotion("ALL10", PromotionScope.ALL_ITEMS, 10, 0);
		assertPromotion(all10, price(12, 5));
	}

	@Test
	@DisplayName("Without all-items promotions the results are exactly as before")
	void noAllItemsPromotionMeansNoChange() {
		Promotion family5 = promotion("FAM5", PromotionScope.ITEM_FAMILY, 5, 0);
		family5.setItemFamily(familyF);
		assertPromotion(null, price(11, 1));
		assertPromotion(family5, price(10, 1));
		assertEquals("BASE_PRICE", price(12, 1).getSource());
	}

	@Test
	@DisplayName("Cross-product with an all-items buy side: every line counts except the benefit item")
	void crossProductAllItems() {
		Promotion x = promotion("XALL", PromotionScope.ALL_ITEMS, 0, 0);
		x.setPromotionType(PromotionType.QUANTITY_PROMOTION);
		x.setBenefitType(PromotionBenefitType.FREE_QUANTITY);
		x.setDiscountPercentage(null);
		x.setFreeQuantity(1);
		x.setMinimumQuantity(2);
		x.setGetItem(items.get(20L));

		List<CrossProductAdjustmentDTO> adj = engine.calculateCartPromotion(300.0,
				Arrays.asList(line(10, 2), line(20, 1)), new ArrayList<>()).getCrossProductAdjustments();
		assertEquals(1, adj.size());
		assertEquals(20L, adj.get(0).getGetItemId().longValue());
		assertEquals(1, adj.get(0).getEntitledUnits().intValue());
		assertEquals(1, adj.get(0).getFreeUnits().intValue());
		assertEquals(1, adj.get(0).getFreeQuantity().intValue(), "the Y piece in the cart is free");
		assertEquals(0, adj.get(0).getFreeQuantityAvailable().intValue());

		adj = engine.calculateCartPromotion(400.0, Collections.singletonList(line(20, 4)), new ArrayList<>())
				.getCrossProductAdjustments();
		assertTrue(adj.isEmpty(), "the benefit item's own line must not count as buy quantity");
		assertPromotion(null, price(10, 2)); // never discounts the buy line itself
	}

	@Test
	@DisplayName("Cross-product free pieces (2026-10-12): free Y = min(freeUnits, Y's pieces in the cart); Y absent: none free, all available")
	void crossProductFreeOnlyWhenScanned() {
		Promotion x = freePromotion("XFREE", 2, 1);
		x.setGetItem(items.get(20L));

		CrossProductAdjustmentDTO absent = cross(line(10, 4));
		assertEquals(2, absent.getEntitledUnits().intValue());
		assertEquals(2, absent.getFreeUnits().intValue(), "kept: what a till of before 3.1.0 adds on top");
		assertEquals(0, absent.getFreeQuantity().intValue());
		assertEquals(2, absent.getFreeQuantityAvailable().intValue(), "2 pieces of Y are free when scanned");

		CrossProductAdjustmentDTO one = cross(line(10, 4), line(20, 1));
		assertEquals(1, one.getFreeQuantity().intValue());
		assertEquals(1, one.getFreeQuantityAvailable().intValue());

		CrossProductAdjustmentDTO more = cross(line(10, 4), line(20, 5));
		assertEquals(2, more.getFreeQuantity().intValue(), "the other 3 pieces of Y are paid");
		assertEquals(0, more.getFreeQuantityAvailable().intValue());

		CartCalculateRequestDTO.CartItemDTO decimalY = line(20, 1);
		decimalY.setQuantity(new BigDecimal("1.5"));
		CrossProductAdjustmentDTO fraction = cross(line(10, 4), decimalY);
		assertEquals(0, fraction.getFreeQuantity().intValue(), "never a free piece on a fraction");
		assertEquals(2, fraction.getFreeQuantityAvailable().intValue());
	}

	private CrossProductAdjustmentDTO cross(CartCalculateRequestDTO.CartItemDTO... lines) {
		List<CrossProductAdjustmentDTO> adj = engine.calculateCartPromotion(500.0, Arrays.asList(lines), new ArrayList<>())
				.getCrossProductAdjustments();
		assertEquals(1, adj.size());
		return adj.get(0);
	}

	/** An all-items "buy n, get m free". */
	private Promotion freePromotion(String code, int n, int m) {
		Promotion q = promotion(code, PromotionScope.ALL_ITEMS, 0, 0);
		q.setPromotionType(PromotionType.QUANTITY_PROMOTION);
		q.setBenefitType(PromotionBenefitType.FREE_QUANTITY);
		q.setDiscountPercentage(null);
		q.setFreeQuantity(m);
		q.setMinimumQuantity(n);
		return q;
	}

	@Test
	@DisplayName("Saving an all-items promotion clears every target; other scopes keep theirs")
	void saveNormalisesTargets() throws Exception {
		PromotionService service = new PromotionService();
		inject(service, PromotionService.class, "promotionRepository", promotionRepository());
		inject(service, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "test";
			}
		});

		Promotion all = new Promotion();
		all.setScope(PromotionScope.ALL_ITEMS);
		all.setPromotionType(PromotionType.SIMPLE_DISCOUNT);
		all.setItem(items.get(10L));
		all.setItemFamily(familyF);
		all.setItemSubFamily(subFamily);
		all.getGroupItems().add(items.get(11L));
		service.save(all);
		assertNull(all.getItem());
		assertNull(all.getItemFamily());
		assertNull(all.getItemSubFamily());
		assertTrue(all.getGroupItems().isEmpty());

		Promotion family = new Promotion();
		family.setScope(PromotionScope.ITEM_FAMILY);
		family.setPromotionType(PromotionType.SIMPLE_DISCOUNT);
		family.setItemFamily(familyF);
		service.save(family);
		assertSame(familyF, family.getItemFamily());
	}

	@Test
	@DisplayName("Admin search: 'All Items' finds the new scope, existing lookups are unchanged")
	void scopeLookup() {
		assertSame(PromotionScope.ALL_ITEMS, PromotionScope.fromString("All Items"));
		assertSame(PromotionScope.ALL_ITEMS, PromotionScope.fromString("all_items"));
		assertSame(PromotionScope.ITEM, PromotionScope.fromString("item"));
		assertSame(PromotionScope.CART, PromotionScope.fromString("cart"));
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private static ItemFamily family(long id) {
		ItemFamily f = new ItemFamily();
		f.setId(id);
		return f;
	}

	private static ItemSubFamily subFamily(long id) {
		ItemSubFamily s = new ItemSubFamily();
		s.setId(id);
		return s;
	}

	private void item(long id, ItemFamily family, ItemSubFamily subFamily) {
		Item i = new Item();
		i.setId(id);
		i.setItemCode("I" + id);
		i.setName("Item " + id);
		i.setItemFamily(family);
		i.setItemSubFamily(subFamily);
		i.setUnitPrice(100.0);
		items.put(id, i);
	}

	private Promotion promotion(String code, PromotionScope scope, double percentage, int priority) {
		Promotion p = new Promotion();
		p.setId(nextId++);
		p.setCode(code);
		p.setName(code);
		p.setPromotionType(PromotionType.SIMPLE_DISCOUNT);
		p.setScope(scope);
		p.setBenefitType(PromotionBenefitType.PERCENTAGE_DISCOUNT);
		p.setDiscountPercentage(percentage);
		p.setPriority(priority);
		p.setRequiresCode(false);
		p.setActive(true);
		promotions.add(p);
		return p;
	}

	private void erpPricing(Double salesDiscountPercentage) {
		PricingResult r = new PricingResult();
		r.setUnitPrice(100.0);
		r.setPriceIncludesVat(true);
		r.setDiscountPercentage(salesDiscountPercentage);
		r.setSource(salesDiscountPercentage != null ? "SALES_DISCOUNT" : "BASE_PRICE");
		erpPricing = r;
	}

	private static CartCalculateRequestDTO.CartItemDTO line(long itemId, int quantity) {
		CartCalculateRequestDTO.CartItemDTO l = new CartCalculateRequestDTO.CartItemDTO();
		l.setItemId(itemId);
		l.setQuantity(BigDecimal.valueOf(quantity));
		return l;
	}

	private PriceCalculateResponseDTO price(long itemId, int quantity, String... codes) {
		return engine.calculateItemPrice(itemId, BigDecimal.valueOf(quantity), null, Arrays.asList(codes));
	}

	private static void assertPromotion(Promotion expected, PriceCalculateResponseDTO r) {
		if (expected == null) {
			assertNull(r.getPromotionId(), "expected no promotion, got " + r.getPromotionCode());
		} else {
			assertEquals(expected.getId(), r.getPromotionId(), "expected " + expected.getCode() + ", got " + r.getPromotionCode());
			assertEquals("PROMOTION", r.getSource());
		}
	}

	// ─── In-memory repositories mirroring the JPQL in PromotionRepository ───

	private List<Promotion> query(Predicate<Promotion> filter, LocalDate today) {
		return promotions.stream()
				.filter(p -> Boolean.TRUE.equals(p.getActive()))
				.filter(p -> p.getStartDate() == null || !p.getStartDate().isAfter(today))
				.filter(p -> p.getEndDate() == null || !p.getEndDate().isBefore(today))
				.filter(filter)
				.sorted(Comparator.comparing(Promotion::getPriority).reversed())
				.collect(Collectors.toList());
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
				case "findActiveByItemSubFamilyId": return query(p -> hasId(p.getItemSubFamily(), a[0]), (LocalDate) a[1]);
				case "findActiveByItemFamilyId": return query(p -> hasId(p.getItemFamily(), a[0]), (LocalDate) a[1]);
				case "findActiveByScope": return query(p -> p.getScope() == a[0], (LocalDate) a[1]);
				case "findActiveCrossProductPromotions": return query(p -> p.getGetItem() != null, (LocalDate) a[0]);
				case "save": return a[0];
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

	// ─── Stub plumbing ───────────────────────────────────────────────

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
