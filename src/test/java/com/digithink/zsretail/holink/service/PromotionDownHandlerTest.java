package com.digithink.zsretail.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.headoffice.dto.PromotionCopyDTO;
import com.digithink.zsretail.holink.dto.DownApplyResult;
import com.digithink.zsretail.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.holink.model.DownRecord;
import com.digithink.zsretail.holink.repository.DownRecordRepository;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.Promotion;
import com.digithink.zsretail.model.enumeration.PromotionBenefitType;
import com.digithink.zsretail.model.enumeration.PromotionScope;
import com.digithink.zsretail.model.enumeration.PromotionType;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.ItemFamilyRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.repository.PromotionRepository;
import com.digithink.zsretail.service.PromotionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Head office plan, task 3.3: the store side of the promotions. Mapping by codes for every scope (ITEM_GROUP and the
 * benefit item included) to this store's records; saved by code with origin HEAD_OFFICE, normalised, without the usage
 * lock; the same copy twice changes nothing; a code used by a local promotion is not saved; removal (unused deleted,
 * used deactivated, local left alone, targeted again updated); local promotions set inactive (Zein's correction).
 * Task 3.5: missing targets WAITING and retried until they apply, ITEM_GROUP information, the tracking rows. The
 * copies are built from head office promotions with other database ids and go through JSON. In-memory store tables.
 */
class PromotionDownHandlerTest {

	private final ObjectMapper wire = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	/** The store's tables. */
	private final Map<Long, Promotion> promotions = new LinkedHashMap<>();
	private final Map<String, Item> items = new HashMap<>();
	private final Map<String, ItemFamily> families = new HashMap<>();
	private final Map<String, ItemSubFamily> subFamilies = new HashMap<>();
	private final Map<Long, Long> usages = new HashMap<>();
	private int saves;

	/** hol_down_record, by code, and its writes. */
	private final Map<String, DownRecord> tracking = new LinkedHashMap<>();
	private int trackingWrites;
	private long nextId;

	private PromotionDownHandler handler;

	@BeforeEach
	void setUp() throws Exception {
		promotions.clear();
		items.clear();
		families.clear();
		subFamilies.clear();
		usages.clear();
		saves = 0;
		tracking.clear();
		trackingWrites = 0;
		nextId = 1;
		ItemFamily family = new ItemFamily();
		family.setId(7101L);
		family.setCode("F1");
		families.put("F1", family);
		ItemSubFamily subFamily = new ItemSubFamily();
		subFamily.setId(7201L);
		subFamily.setCode("SF1");
		subFamilies.put("SF1", subFamily);
		for (int i = 1; i <= 3; i++) {
			Item item = new Item();
			item.setId(7000L + i);
			item.setItemCode("I" + i);
			item.setName("Item " + i);
			items.put(item.getItemCode(), item);
		}
		PromotionService promotionService = new PromotionService();
		Field repository = PromotionService.class.getDeclaredField("promotionRepository");
		repository.setAccessible(true);
		repository.set(promotionService, promotionRepository());
		handler = new PromotionDownHandler(promotionService, promotionRepository(), itemRepository(),
				familyRepository(), subFamilyRepository(), new DownRecordLog(trackingRepository()),
				TransactionOperations.withoutTransaction());
	}

	// ─── Head office promotions (other ids) and their copies ──────

	private static Item hoItem(String code) {
		Item item = new Item();
		item.setId(90L + code.hashCode() % 7);
		item.setItemCode(code);
		return item;
	}

	private static Promotion hoPromotion(String code, PromotionScope scope) {
		Promotion p = new Promotion();
		p.setId(555L);
		p.setCode(code);
		p.setName("HO " + code);
		p.setDescription("from the head office");
		p.setPromotionType(PromotionType.SIMPLE_DISCOUNT);
		p.setScope(scope);
		p.setBenefitType(PromotionBenefitType.PERCENTAGE_DISCOUNT);
		p.setDiscountPercentage(15.0);
		p.setStartDate(LocalDate.of(2026, 10, 1));
		p.setEndDate(LocalDate.of(2026, 10, 31));
		p.setDayOfWeek("MONDAY,FRIDAY");
		p.setTimeStart(LocalTime.of(10, 0));
		p.setTimeEnd(LocalTime.of(12, 30));
		p.setPriority(4);
		p.setRequiresCode(true);
		return p;
	}

	private JsonNode copy(Promotion headOffice) throws Exception {
		return wire.readTree(wire.writeValueAsString(PromotionCopyDTO.of(headOffice)));
	}

	private DownApplyResult apply(Promotion... headOffice) throws Exception {
		List<JsonNode> records = new ArrayList<>();
		for (Promotion p : headOffice) {
			records.add(copy(p));
		}
		return handler.apply(records, Collections.emptyList());
	}

	private DownApplyResult remove(String... codes) {
		return handler.apply(Collections.emptyList(), Arrays.asList(codes));
	}

	private DownRecord tracked(String code) {
		return tracking.get(code);
	}

	private void addItem(String code, long id) {
		Item item = new Item();
		item.setId(id);
		item.setItemCode(code);
		item.setName(code);
		items.put(code, item);
	}

	private Promotion stored(String code) {
		return promotions.values().stream().filter(p -> p.getCode().equals(code)).findFirst().orElse(null);
	}

	private Promotion local(String code, RecordOrigin origin, boolean active) {
		Promotion p = hoPromotion(code, PromotionScope.CART);
		p.setId(nextId++);
		p.setOrigin(origin);
		p.setActive(active);
		promotions.put(p.getId(), p);
		return p;
	}

	// ─── Tests ────────────────────────────────────────────────────

	@Test
	@DisplayName("Every scope: codes resolved to this store's records, every field copied, origin HEAD_OFFICE")
	void everyScope() throws Exception {
		Promotion item = hoPromotion("ITEM", PromotionScope.ITEM);
		item.setItem(hoItem("I1"));
		Promotion family = hoPromotion("FAM", PromotionScope.ITEM_FAMILY);
		ItemFamily hoFamily = new ItemFamily();
		hoFamily.setId(3L);
		hoFamily.setCode("F1");
		family.setItemFamily(hoFamily);
		Promotion subFamily = hoPromotion("SUB", PromotionScope.ITEM_SUBFAMILY);
		ItemSubFamily hoSub = new ItemSubFamily();
		hoSub.setId(4L);
		hoSub.setCode("SF1");
		subFamily.setItemSubFamily(hoSub);
		Promotion group = hoPromotion("GRP", PromotionScope.ITEM_GROUP);
		group.getGroupItems().add(hoItem("I1"));
		group.getGroupItems().add(hoItem("I2"));
		Promotion all = hoPromotion("ALL", PromotionScope.ALL_ITEMS);
		Promotion cart = hoPromotion("CART", PromotionScope.CART);
		cart.setMinimumAmount(100.0);
		cart.setActive(false);
		Promotion gift = hoPromotion("GIFT", PromotionScope.ITEM);
		gift.setPromotionType(PromotionType.QUANTITY_PROMOTION);
		gift.setBenefitType(PromotionBenefitType.FREE_QUANTITY);
		gift.setMinimumQuantity(2);
		gift.setFreeQuantity(1);
		gift.setItem(hoItem("I1"));
		gift.setGetItem(hoItem("I3"));

		DownApplyResult result = apply(item, family, subFamily, group, all, cart, gift);
		assertEquals(7, result.getApplied(), String.valueOf(result));
		assertEquals(0, result.getProblems());

		for (Promotion p : promotions.values()) {
			assertEquals(RecordOrigin.HEAD_OFFICE, p.getOrigin(), p.getCode());
			assertEquals("from the head office", p.getDescription());
			assertEquals(LocalDate.of(2026, 10, 1), p.getStartDate());
			assertEquals(LocalTime.of(12, 30), p.getTimeEnd());
			assertEquals("MONDAY,FRIDAY", p.getDayOfWeek());
			assertEquals(4, p.getPriority());
			assertTrue(p.getRequiresCode());
			assertEquals(PromotionService.HEAD_OFFICE_USER, p.getCreatedBy());
		}
		assertSame(items.get("I1"), stored("ITEM").getItem(), "the store's item, id 7001");
		assertSame(families.get("F1"), stored("FAM").getItemFamily());
		assertSame(subFamilies.get("SF1"), stored("SUB").getItemSubFamily());
		assertEquals(Arrays.asList(7001L, 7002L), stored("GRP").getGroupItems().stream().map(Item::getId).sorted()
				.collect(Collectors.toList()));
		assertNull(stored("ALL").getItem());
		assertFalse(stored("CART").getActive());
		assertEquals(100.0, stored("CART").getMinimumAmount());
		assertSame(items.get("I3"), stored("GIFT").getGetItem());
		assertEquals(PromotionBenefitType.FREE_QUANTITY, stored("GIFT").getBenefitType());
		assertEquals(PromotionCopyDTO.of(gift), PromotionCopyDTO.of(stored("GIFT")), "same promotion, by codes");
	}

	@Test
	@DisplayName("The same copy twice changes nothing: no save; a changed copy updates the same row")
	void sameCopyTwice() throws Exception {
		Promotion group = hoPromotion("GRP", PromotionScope.ITEM_GROUP);
		group.getGroupItems().add(hoItem("I2"));
		group.getGroupItems().add(hoItem("I1"));
		apply(group);
		Promotion first = stored("GRP");
		int savesBefore = saves;

		DownApplyResult again = apply(group);
		assertEquals(1, again.getUnchanged());
		assertEquals(0, again.getApplied());
		assertEquals(savesBefore, saves, "nothing written");

		group.setName("Renamed at the head office");
		assertEquals(1, apply(group).getApplied());
		assertEquals(1, promotions.size());
		assertEquals(first.getId(), stored("GRP").getId(), "saved by code: same row");
		assertEquals("Renamed at the head office", stored("GRP").getName());
	}

	@Test
	@DisplayName("Normalisation of PromotionService.save kept; the usage lock skipped (the head office decides)")
	void normalisedNotLocked() throws Exception {
		Promotion p = hoPromotion("P1", PromotionScope.ALL_ITEMS);
		p.setItem(hoItem("I1")); // a stray target on an ALL_ITEMS promotion
		p.setGetItem(hoItem("I3")); // a benefit item on a simple discount
		apply(p);
		assertNull(stored("P1").getItem());
		assertNull(stored("P1").getGetItem());

		usages.put(stored("P1").getId(), 4L);
		p.setDiscountPercentage(40.0);
		assertEquals(1, apply(p).getApplied());
		assertEquals(40.0, stored("P1").getDiscountPercentage());
	}

	@Test
	@DisplayName("Code used by a local promotion: not saved, reported as an error; the local promotion untouched")
	void codeClash() throws Exception {
		Promotion mine = local("SALE", null, true);
		mine.setName("Mine");
		DownApplyResult result = apply(hoPromotion("SALE", PromotionScope.CART));
		assertEquals(1, result.getErrors());
		assertEquals("SALE: " + PromotionDownHandler.CODE_CLASH, result.getFirstProblem());
		assertEquals("Mine", stored("SALE").getName());
		assertNull(stored("SALE").getOrigin());
		assertEquals(1, promotions.size());
	}

	@Test
	@DisplayName("Removal: unused deleted, used deactivated and kept, local or unknown left alone; targeted again: updated")
	void removal() throws Exception {
		apply(hoPromotion("UNUSED", PromotionScope.CART), hoPromotion("USED", PromotionScope.CART));
		usages.put(stored("USED").getId(), 2L);
		local("MINE", RecordOrigin.LOCAL, true);
		Long usedId = stored("USED").getId();

		DownApplyResult result = remove("UNUSED", "USED", "MINE", "NEVER_HAD");
		assertEquals(2, result.getRemoved());
		assertNull(stored("UNUSED"));
		assertFalse(stored("USED").getActive());
		assertEquals(RecordOrigin.HEAD_OFFICE, stored("USED").getOrigin());
		assertTrue(stored("MINE").getActive());

		assertEquals(1, remove("USED").getUnchanged(), "already inactive");

		Promotion back = hoPromotion("USED", PromotionScope.CART);
		back.setName("Back");
		assertEquals(1, apply(back).getApplied());
		assertEquals(usedId, stored("USED").getId());
		assertTrue(stored("USED").getActive());
		assertEquals("Back", stored("USED").getName());
	}

	@Test
	@DisplayName("Task 3.5: an item not in the store: WAITING with the reason, retried at every cycle, applies by itself once the item exists")
	void missingItemWaitingThenApplied() throws Exception {
		Promotion missing = hoPromotion("MISS", PromotionScope.ITEM);
		missing.setItem(hoItem("I9"));
		DownApplyResult result = apply(missing);
		assertEquals(1, result.getWaiting());
		assertEquals("MISS: not in this store: item I9", result.getFirstProblem());
		assertNull(stored("MISS"), "not applied");
		DownRecord row = tracked("MISS");
		assertEquals(DownRecordStatus.WAITING, row.getStatus());
		assertEquals("not in this store: item I9", row.getReason());
		assertEquals("HO MISS", row.getRecordName());
		handler.retry(); // end of the pull's cycle: the record it just tried is not retried

		int rowWrites = trackingWrites;
		DownApplyResult retry = handler.retry();
		assertEquals(1, retry.getWaiting(), "still waiting");
		assertEquals(rowWrites, trackingWrites, "a retry that changes nothing writes nothing");
		assertNull(stored("MISS"));

		addItem("I9", 7009L);
		retry = handler.retry();
		assertEquals(1, retry.getApplied());
		assertEquals(7009L, stored("MISS").getItem().getId());
		assertEquals(DownRecordStatus.APPLIED, tracked("MISS").getStatus());
		assertNull(tracked("MISS").getReason());
		assertEquals(0, handler.retry().getApplied(), "an applied record is not retried");
	}

	@Test
	@DisplayName("Task 3.5: family, sub-family and benefit item missing are WAITING too; a promotion the store has becomes inactive")
	void otherTargetsAndExisting() throws Exception {
		Promotion family = hoPromotion("FAM", PromotionScope.ITEM_FAMILY);
		ItemFamily f9 = new ItemFamily();
		f9.setCode("F9");
		family.setItemFamily(f9);
		Promotion sub = hoPromotion("SUB", PromotionScope.ITEM_SUBFAMILY);
		ItemSubFamily s9 = new ItemSubFamily();
		s9.setCode("SF9");
		sub.setItemSubFamily(s9);
		Promotion gift = hoPromotion("GIFT", PromotionScope.ITEM);
		gift.setPromotionType(PromotionType.QUANTITY_PROMOTION);
		gift.setMinimumQuantity(2);
		gift.setItem(hoItem("I1"));
		gift.setGetItem(hoItem("I7"));
		assertEquals(3, apply(family, sub, gift).getWaiting());
		assertEquals("not in this store: family F9", tracked("FAM").getReason());
		assertEquals("not in this store: sub-family SF9", tracked("SUB").getReason());
		assertEquals("not in this store: benefit item I7", tracked("GIFT").getReason());

		Promotion item = hoPromotion("P1", PromotionScope.ITEM);
		item.setItem(hoItem("I1"));
		apply(item);
		assertTrue(stored("P1").getActive());
		item.setItem(hoItem("I9")); // the head office now targets an item this store does not have
		assertEquals(1, apply(item).getWaiting());
		assertFalse(stored("P1").getActive(), "not applied: inactive until its item is here");
		assertEquals(7001L, stored("P1").getItem().getId(), "the rest unchanged");
		int writes = saves;
		apply(item);
		assertEquals(writes, saves, "the same answer again writes nothing");
		handler.retry(); // end of the cycle
		addItem("I9", 7009L);
		handler.retry();
		assertTrue(stored("P1").getActive());
		assertEquals(7009L, stored("P1").getItem().getId());
	}

	@Test
	@DisplayName("Task 3.5: ITEM_GROUP saved with the items the store has, the missing codes as information; none present: WAITING")
	void groupItems() throws Exception {
		Promotion none = hoPromotion("NONE", PromotionScope.ITEM_GROUP);
		none.getGroupItems().add(hoItem("I8"));
		none.getGroupItems().add(hoItem("I9"));
		Promotion some = hoPromotion("SOME", PromotionScope.ITEM_GROUP);
		some.getGroupItems().add(hoItem("I1"));
		some.getGroupItems().add(hoItem("I9"));
		DownApplyResult result = apply(none, some);
		assertEquals(1, result.getWaiting());
		assertEquals(1, result.getApplied());
		assertNull(stored("NONE"));
		assertEquals("not in this store: group items I8, I9", tracked("NONE").getReason());
		assertEquals(Collections.singletonList(7001L), stored("SOME").getGroupItems().stream().map(Item::getId)
				.collect(Collectors.toList()));
		assertEquals(DownRecordStatus.APPLIED, tracked("SOME").getStatus());
		assertEquals("group items not in this store: I9", tracked("SOME").getInfo());
	}

	@Test
	@DisplayName("Task 3.5: a code clash is ERROR, retried; once the local promotion is gone, the head office one applies")
	void clashRetried() throws Exception {
		Promotion mine = local("SALE", null, true);
		apply(hoPromotion("SALE", PromotionScope.CART));
		assertEquals(DownRecordStatus.ERROR, tracked("SALE").getStatus());
		assertEquals(PromotionDownHandler.CODE_CLASH, tracked("SALE").getReason());
		handler.retry(); // end of the cycle
		assertEquals(1, handler.retry().getErrors());
		promotions.remove(mine.getId());
		assertEquals(1, handler.retry().getApplied());
		assertEquals(RecordOrigin.HEAD_OFFICE, stored("SALE").getOrigin());
		assertEquals(DownRecordStatus.APPLIED, tracked("SALE").getStatus());
	}

	@Test
	@DisplayName("Task 3.5: the same answer twice writes no tracking row; a removal deletes the row; this cycle's records are not retried")
	void trackingRows() throws Exception {
		Promotion p = hoPromotion("P1", PromotionScope.CART);
		Promotion w = hoPromotion("W1", PromotionScope.ITEM);
		w.setItem(hoItem("I9"));
		apply(p, w);
		int writes = trackingWrites;
		apply(p, w);
		assertEquals(writes, trackingWrites, "same answer: no write");
		assertEquals(0, handler.retry().getWaiting(), "W1 was just tried by this cycle's pull");
		assertEquals(1, handler.retry().getWaiting(), "next cycle: retried");

		remove("P1", "W1");
		assertNull(tracked("P1"));
		assertNull(tracked("W1"));
		assertTrue(tracking.isEmpty());
	}

	@Test
	@DisplayName("Local promotions set inactive (kept, origin unchanged); head office ones untouched; the next time none")
	void deactivateLocal() {
		local("L-NULL", null, true);
		local("L-LOCAL", RecordOrigin.LOCAL, true);
		local("L-OFF", null, false);
		local("H-ON", RecordOrigin.HEAD_OFFICE, true);
		assertEquals(2, handler.deactivateLocal());
		assertFalse(stored("L-NULL").getActive());
		assertNull(stored("L-NULL").getOrigin());
		assertFalse(stored("L-LOCAL").getActive());
		assertEquals(RecordOrigin.LOCAL, stored("L-LOCAL").getOrigin());
		assertTrue(stored("H-ON").getActive());
		assertEquals(4, promotions.size());
		assertEquals(0, handler.deactivateLocal());
	}

	@Test
	@DisplayName("An unreadable record or one without a code is an error; the next records are applied")
	void unreadable() throws Exception {
		List<JsonNode> records = Arrays.asList(wire.readTree("{\"code\":\"BAD\",\"startDate\":\"not a date\"}"),
				wire.readTree("{\"name\":\"no code\"}"), copy(hoPromotion("OK", PromotionScope.CART)));
		DownApplyResult result = handler.apply(records, Collections.emptyList());
		assertEquals(2, result.getErrors());
		assertTrue(result.getFirstProblem().startsWith("BAD: unreadable record"), result.getFirstProblem());
		assertEquals(1, result.getApplied());
	}

	// ─── Stubs ────────────────────────────────────────────────────

	private PromotionRepository promotionRepository() {
		return proxy(PromotionRepository.class, (method, args) -> {
			switch (method) {
				case "findByCode":
					return Optional.ofNullable(stored((String) args[0]));
				case "save":
					Promotion saved = (Promotion) args[0];
					if (saved.getId() == null) {
						saved.setId(nextId++);
					}
					promotions.put(saved.getId(), saved);
					saves++;
					return saved;
				case "delete":
					promotions.remove(((Promotion) args[0]).getId());
					return null;
				case "countUsages":
					return usages.getOrDefault(args[0], 0L);
				case "findActiveNotFrom":
					return promotions.values().stream()
							.filter(p -> Boolean.TRUE.equals(p.getActive()) && p.getOrigin() != args[0])
							.collect(Collectors.toList());
				default:
					throw new UnsupportedOperationException(method);
			}
		});
	}

	private DownRecordRepository trackingRepository() {
		return proxy(DownRecordRepository.class, (method, args) -> {
			switch (method) {
				case "findByDomainAndRecordCode":
					return Optional.ofNullable(tracking.get(args[1]));
				case "save":
					DownRecord row = (DownRecord) args[0];
					tracking.put(row.getRecordCode(), row);
					trackingWrites++;
					return row;
				case "delete":
					tracking.remove(((DownRecord) args[0]).getRecordCode());
					return null;
				case "findByDomainAndStatusIn":
					java.util.Collection<?> statuses = (java.util.Collection<?>) args[1];
					return tracking.values().stream().filter(r -> statuses.contains(r.getStatus()))
							.collect(Collectors.toList());
				default:
					throw new UnsupportedOperationException(method);
			}
		});
	}

	private ItemRepository itemRepository() {
		return proxy(ItemRepository.class, (method, args) -> {
			if ("findByItemCode".equals(method)) {
				return Optional.ofNullable(items.get(args[0]));
			}
			throw new UnsupportedOperationException(method);
		});
	}

	private ItemFamilyRepository familyRepository() {
		return proxy(ItemFamilyRepository.class, (method, args) -> {
			if ("findByCode".equals(method)) {
				return Optional.ofNullable(families.get(args[0]));
			}
			throw new UnsupportedOperationException(method);
		});
	}

	private ItemSubFamilyRepository subFamilyRepository() {
		return proxy(ItemSubFamilyRepository.class, (method, args) -> {
			if ("findByCode".equals(method)) {
				return Optional.ofNullable(subFamilies.get(args[0]));
			}
			throw new UnsupportedOperationException(method);
		});
	}

	private interface Handler {
		Object handle(String method, Object[] args);
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy == args[0];
				case "toString": return type.getSimpleName() + "Stub";
				default: return handler.handle(method.getName(), args == null ? new Object[0] : args);
			}
		});
	}
}
