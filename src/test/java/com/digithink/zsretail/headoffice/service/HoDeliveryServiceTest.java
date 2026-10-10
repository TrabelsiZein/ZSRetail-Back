package com.digithink.zsretail.headoffice.service;

import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.headoffice.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryCopyDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryInputDTO;
import com.digithink.zsretail.headoffice.enumeration.DeliveryStatus;
import com.digithink.zsretail.headoffice.model.HoDelivery;
import com.digithink.zsretail.headoffice.model.HoDownChange;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.StockMovement;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.model.enumeration.StockMovementDirection;
import com.digithink.zsretail.model.enumeration.StockMovementType;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.digithink.zsretail.support.InMemoryStock;
import com.digithink.zsretail.utils.Quantities;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Head office plan, task 7A.2: the BLs of the head office. Drafts (rules, edit, delete), validation all or nothing
 * (number, status, stock out once with one DELIVERY_OUT per line, a store's BL recorded for that store only), the stock
 * check (409 listing each short item, nothing changed; allowed below zero with ALLOW_NEGATIVE_STOCK), the copy by codes,
 * the list filters, the startup backfill, and no CATALOGUE change. Real HoDeliveryService, HoCatalogueService,
 * CopiesDownFeed, StockService and StockMovementService over in-memory tables.
 */
class HoDeliveryServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 9, 30);

	private InMemoryCatalogue ho;
	private InMemoryStock stock;
	private InMemoryDownTables down;
	private InMemoryDeliveries tables;
	private CopiesDownFeed feed;
	private HoDeliveryService service;
	private Store b;
	private Store c;
	private Item b001;
	private Item b002;

	@BeforeEach
	void setUp() {
		ho = new InMemoryCatalogue(1);
		stock = new InMemoryStock(ho);
		down = new InMemoryDownTables();
		tables = new InMemoryDeliveries(ho);
		CopiesDownFeed[] feedRef = new CopiesDownFeed[1];
		HoCatalogueService catalogue = new HoCatalogueService(ho.familyRepository(), ho.subFamilyRepository(),
				stock.itemRepository(), ho.barcodeRepository(), ho.compositionRepository(), ho.priceLineRepository(),
				() -> feedRef[0], TransactionOperations.withoutTransaction());
		service = tables.service(stock, () -> feedRef[0], () -> NOW);
		feed = down.feed(Arrays.asList(catalogue, service));
		feedRef[0] = feed;
		b001 = ho.item("B001", 10.0, null);
		b001.setStockQuantity(BigDecimal.valueOf(100));
		b002 = ho.item("B002", 4.0, null);
		b002.setStockQuantity(BigDecimal.valueOf(10));
		b = ho.store("B");
		c = ho.store("C");
		service.initialise();
		feed.initialise();
	}

	private static DeliveryInputDTO input(Long storeId, DeliveryInputDTO.Line... lines) {
		DeliveryInputDTO input = new DeliveryInputDTO();
		input.setStoreId(storeId);
		input.setNote("Weekly delivery");
		input.setLines(Arrays.asList(lines));
		return input;
	}

	private static DeliveryInputDTO.Line line(String itemCode, Integer quantity) {
		return new DeliveryInputDTO.Line(itemCode, Quantities.of(quantity));
	}

	private DeliveryDTO draftForB() {
		return service.create(input(b.getId(), line("B001", 50), line("B002", 5)));
	}

	private long supplyChanges() {
		return down.changes.stream().filter(ch -> ch.getDomain() == DataDomain.SUPPLY).count();
	}

	private long catalogueVersion() {
		return down.changes.stream().filter(ch -> ch.getDomain() == DataDomain.CATALOGUE)
				.mapToLong(HoDownChange::getChangeVersion).max().orElse(0);
	}

	@Test
	@DisplayName("A draft: lines numbered with the item's code and name, no number, no stock moved, nothing for the stores")
	void draft() {
		DeliveryDTO draft = draftForB();

		assertEquals("DRAFT", draft.getStatus());
		assertNull(draft.getNumber());
		assertEquals(LocalDate.of(2026, 10, 5), draft.getDocumentDate(), "today by default");
		assertEquals(2, draft.getLineCount());
		assertEquals(BigDecimal.valueOf(55), draft.getQuantitySent());
		assertEquals(1, draft.getLines().get(0).getLineNo());
		assertEquals("B001", draft.getLines().get(0).getItemCode());
		assertEquals("Item B001", draft.getLines().get(0).getItemName());
		assertEquals(BigDecimal.valueOf(100), draft.getLines().get(0).getHeadOfficeStock());
		assertEquals(2, draft.getLines().get(1).getLineNo());
		assertEquals("B", draft.getStoreCode());
		assertEquals(100, stock.stockOf("B001"));
		assertTrue(stock.movements.isEmpty());
		assertEquals(0, supplyChanges());
	}

	@Test
	@DisplayName("Draft rules: store, lines, items (unknown, inactive, service, tax stamp, twice), quantity: 400 with the line; nothing saved")
	void draftRules() {
		Item service1 = ho.item("S1", 5.0, null);
		service1.setType(ItemType.SERVICE);
		Item off = ho.item("OFF", 5.0, null);
		off.setActive(false);
		ho.item("TAX_STAMP", 1.0, null);
		c.setActive(false);

		assertBadRequest("Choose the store of the BL.", input(null, line("B001", 1)));
		assertBadRequest("Unknown store id 999.", input(999L, line("B001", 1)));
		assertBadRequest("The store C is inactive.", input(c.getId(), line("B001", 1)));
		assertBadRequest("A BL needs at least one line.", input(b.getId()));
		assertBadRequest("Line 1: choose an item.", input(b.getId(), line(" ", 1)));
		assertBadRequest("Line 2: unknown item B999.", input(b.getId(), line("B001", 1), line("B999", 1)));
		assertBadRequest("Line 1: the item OFF is inactive.", input(b.getId(), line("OFF", 1)));
		assertBadRequest("Line 1: the item S1 is a service: it has no stock.", input(b.getId(), line("S1", 1)));
		assertBadRequest("Line 1: the tax stamp cannot be delivered.", input(b.getId(), line("TAX_STAMP", 1)));
		assertBadRequest("Line 1: the quantity must be a whole number above 0.", input(b.getId(), line("B001", 0)));
		assertBadRequest("Line 1: the quantity must be a whole number above 0.", input(b.getId(), line("B001", null)));
		// 2.2.1: a BL made here stays whole; a decimal is refused naming the item, never read as 1
		assertBadRequest("Line 1: Item B001 (Item B001): the quantity 1.5 has decimals, and decimal quantities are not"
				+ " supported in head office BLs yet.", input(b.getId(), new DeliveryInputDTO.Line("B001", new java.math.BigDecimal("1.5"))));
		assertBadRequest("Line 3: the item B001 is already on line 1.",
				input(b.getId(), line("B001", 1), line("B002", 1), line("B001", 2)));
		DeliveryInputDTO badDate = input(b.getId(), line("B001", 1));
		badDate.setDocumentDate("05/10/2026");
		assertBadRequest("documentDate must be a date as yyyy-MM-dd.", badDate);
		assertTrue(tables.deliveries.isEmpty());
	}

	private void assertBadRequest(String message, DeliveryInputDTO input) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.create(input));
		assertEquals(message, e.getMessage());
	}

	@Test
	@DisplayName("A store that reported another supply owner: 409; a store that has not reported yet is accepted")
	void storeNotSuppliedByTheHeadOffice() {
		c.setOwnerSupply("LOCAL");
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> service.create(input(c.getId(), line("B001", 1))));
		assertEquals("The store C does not receive goods from the head office (ownership.supply=LOCAL in its settings).",
				e.getMessage());

		b.setOwnerSupply("HEAD_OFFICE");
		assertEquals("DRAFT", draftForB().getStatus());
		b.setOwnerSupply(null);
		assertEquals("DRAFT", draftForB().getStatus());
	}

	@Test
	@DisplayName("A draft is edited (store, lines, note) and deleted; once sent, edit, delete and validate answer 409 and change nothing")
	void draftEditAndDelete() {
		DeliveryDTO draft = draftForB();
		DeliveryInputDTO edit = input(c.getId(), line("B002", 3));
		edit.setNote("Changed");
		DeliveryDTO edited = service.update(draft.getId(), edit).get();
		assertEquals(c.getId(), edited.getStoreId());
		assertEquals(1, edited.getLineCount());
		assertEquals("B002", edited.getLines().get(0).getItemCode());
		assertEquals("Changed", edited.getNote());

		assertTrue(service.delete(draft.getId()));
		assertTrue(tables.deliveries.isEmpty());
		assertFalse(service.delete(draft.getId()), "unknown");
		assertFalse(service.update(draft.getId(), edit).isPresent());
		assertFalse(service.validate(draft.getId(), "admin").isPresent());

		DeliveryDTO sent = service.validate(draftForB().getId(), "admin").get();
		assertEquals("Only a draft BL can be changed: BL-000001 is SENT.",
				assertThrows(IllegalStateException.class, () -> service.update(sent.getId(), edit)).getMessage());
		assertEquals("Only a draft BL can be deleted: BL-000001 is SENT.",
				assertThrows(IllegalStateException.class, () -> service.delete(sent.getId())).getMessage());
		assertEquals("B", service.get(sent.getId()).get().getStoreCode());
		assertEquals(2, service.get(sent.getId()).get().getLineCount());
	}

	@Test
	@DisplayName("Validate: BL-000001, SENT, stock out once with one DELIVERY_OUT per line, recorded for its store only; again 409, nothing moves")
	void validateOnce() {
		DeliveryDTO draft = draftForB();

		DeliveryDTO sent = service.validate(draft.getId(), "admin").get();

		assertEquals("BL-000001", sent.getNumber());
		assertEquals("SENT", sent.getStatus());
		assertEquals(NOW, sent.getSentAt());
		assertEquals("admin", sent.getSentBy());
		assertEquals(50, stock.stockOf("B001"));
		assertEquals(5, stock.stockOf("B002"));
		List<StockMovement> out = stock.movements(StockMovementType.DELIVERY_OUT);
		assertEquals(2, out.size());
		assertEquals(StockMovementDirection.OUT, out.get(0).getDirection());
		assertEquals(BigDecimal.valueOf(50), out.get(0).getQuantity());
		assertEquals(draft.getId(), out.get(0).getReferenceId());
		assertEquals("BL", out.get(0).getReferenceType());
		assertEquals("BL-000001", out.get(0).getNotes());
		assertEquals(1, supplyChanges(), "one change row: store B only");
		assertEquals(b.getId(), down.changes.stream().filter(ch -> ch.getDomain() == DataDomain.SUPPLY).findFirst().get()
				.getStoreId());

		IllegalStateException again = assertThrows(IllegalStateException.class,
				() -> service.validate(draft.getId(), "admin"));
		assertEquals("Only a draft BL can be validated: BL-000001 is SENT.", again.getMessage());
		assertEquals(50, stock.stockOf("B001"));
		assertEquals(2, stock.movements.size());
		assertEquals(1, supplyChanges());

		assertEquals("BL-000002", service.validate(draftForB().getId(), "admin").get().getNumber(), "next number");
	}

	@Test
	@DisplayName("Stock not sufficient: 409 listing each short item, all or nothing (still a draft, no number, no stock, no movement)")
	void shortage() {
		ho.item("B003", 1.0, null); // never in stock (null counts as 0)
		DeliveryDTO draft = service.create(input(b.getId(), line("B001", 150), line("B002", 5), line("B003", 1)));
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.validate(draft.getId(), "admin"));

		assertEquals("Stock not sufficient at the head office: B001: 100 in stock, 150 on the BL; B003: 0 in stock, 1 on the BL.",
				e.getMessage());
		HoDelivery row = tables.deliveries.get(draft.getId());
		assertEquals(DeliveryStatus.DRAFT, row.getStatus());
		assertNull(row.getNumber());
		assertEquals(100, stock.stockOf("B001"));
		assertEquals(10, stock.stockOf("B002"));
		assertTrue(stock.movements.isEmpty());
		assertEquals(0, supplyChanges());
		assertEquals("BL-000001", service.validate(draftForB().getId(), "admin").get().getNumber(), "no number used");
	}

	@Test
	@DisplayName("Without stock (headoffice.stock.enabled=false): no shortage check, no decrease, no movement, headOfficeStock"
			+ " null; the number, SENT and the record for its store as usual")
	void withoutStock() {
		HoDeliveryService noStock = tables.serviceWithoutStock(stock, () -> feed, () -> NOW);
		ho.item("B003", 1.0, null); // never in stock
		assertTrue(!stock.allowNegative, "ALLOW_NEGATIVE_STOCK false: the head office that keeps its stock would refuse");
		DeliveryDTO draft = noStock.create(input(b.getId(), line("B001", 150), line("B002", 5), line("B003", 1)));
		draft.getLines().forEach(l -> assertNull(l.getHeadOfficeStock(), l.getItemCode()));

		DeliveryDTO sent = noStock.validate(draft.getId(), "admin").get();

		assertEquals("BL-000001", sent.getNumber());
		assertEquals("SENT", sent.getStatus());
		assertEquals(100, stock.stockOf("B001"));
		assertEquals(10, stock.stockOf("B002"));
		assertTrue(stock.movements.isEmpty(), "no stock movement");
		assertEquals(1, supplyChanges(), "recorded for store B");
		noStock.get(draft.getId()).get().getLines().forEach(l -> assertNull(l.getHeadOfficeStock(), l.getItemCode()));
		assertEquals(BigDecimal.valueOf(100), service.get(draft.getId()).get().getLines().get(0).getHeadOfficeStock(),
				"the head office that keeps its stock still reads it");
	}

	@Test
	@DisplayName("Without stock: the item rules stay (a service item cannot be delivered)")
	void withoutStockItemRules() {
		HoDeliveryService noStock = tables.serviceWithoutStock(stock, () -> feed, () -> NOW);
		ho.item("S1", 5.0, null).setType(ItemType.SERVICE);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> noStock.create(input(b.getId(), line("S1", 1))));
		assertEquals("Line 1: the item S1 is a service: it has no stock.", e.getMessage());
	}

	@Test
	@DisplayName("ALLOW_NEGATIVE_STOCK=true: the BL is validated and the head office stock goes below zero")
	void negativeAllowed() {
		stock.allowNegative = true;
		DeliveryDTO draft = service.create(input(b.getId(), line("B002", 15)));

		assertEquals("SENT", service.validate(draft.getId(), "admin").get().getStatus());
		assertEquals(-5, stock.stockOf("B002"));
	}

	@Test
	@DisplayName("The copy by codes reaches its store only: B pulls BL:BL-000001 with its lines, C gets nothing, nor when it asks for the code")
	void copyForItsStoreOnly() {
		service.validate(draftForB().getId(), "admin");

		CopiesDownAnswerDTO atB = feed.pull(b, "supply", "", 100);
		assertEquals(1, atB.getRecords().size());
		JsonNode copy = atB.getRecords().get(0);
		assertEquals("BL-000001", copy.get("number").asText());
		assertEquals("2026-10-05", copy.get("documentDate").asText());
		assertEquals("2026-10-05T09:30:00", copy.get("sentAt").asText());
		assertEquals("SENT", copy.get("status").asText());
		assertEquals("Weekly delivery", copy.get("note").asText());
		assertEquals(2, copy.get("lines").size());
		assertEquals("B001", copy.get("lines").get(0).get("itemCode").asText());
		assertEquals(50, copy.get("lines").get(0).get("quantitySent").asInt());
		assertFalse(copy.toString().contains("\"id\""), "no database id");
		assertFalse(copy.toString().contains("Id\""), "no database id");

		assertTrue(feed.pull(c, "supply", "", 100).getRecords().isEmpty());
		Map<String, JsonNode> asked = service.load(c, Collections.singletonList(DeliveryCopyDTO.recordCode("BL-000001")));
		assertTrue(asked.isEmpty(), "answered as removed to C");
	}

	@Test
	@DisplayName("Startup backfill: a sent BL without a change row gets one for its store only; drafts never")
	void backfill() {
		service.validate(draftForB().getId(), "admin");
		draftForB();
		down.changes.clear();

		feed.initialise();

		List<HoDownChange> rows = down.changes.stream().filter(ch -> ch.getDomain() == DataDomain.SUPPLY)
				.collect(Collectors.toList());
		assertEquals(1, rows.size());
		assertEquals("BL:BL-000001", rows.get(0).getRecordCode());
		assertEquals(b.getId(), rows.get(0).getStoreId());
	}

	@Test
	@DisplayName("Validating a BL records no CATALOGUE change: no store pulls its items again")
	void noCatalogueChange() {
		long before = catalogueVersion();

		service.validate(draftForB().getId(), "admin");

		assertEquals(before, catalogueVersion());
	}

	@Test
	@DisplayName("List: newest first, filters store, status, search, dates, difference; paging; a bad status or date is 400")
	void list() {
		DeliveryDTO first = draftForB();
		service.validate(first.getId(), "admin");
		DeliveryDTO second = service.create(input(c.getId(), line("B002", 1)));
		HoDelivery received = tables.deliveries.get(first.getId());
		received.setStatus(DeliveryStatus.RECEIVED);
		received.getLines().get(0).setQuantityReceived(Quantities.of(48));
		received.getLines().get(1).setQuantityReceived(Quantities.of(5));

		assertEquals(Arrays.asList(second.getId(), first.getId()), ids(service.list(null, null, null, null, null, null, null, null)));
		assertEquals(Collections.singletonList(first.getId()), ids(service.list(b.getId(), "all", null, null, null, null, 0, 20)));
		assertEquals(Collections.singletonList(second.getId()), ids(service.list(null, "draft", null, null, null, null, 0, 20)));
		assertEquals(Collections.singletonList(first.getId()), ids(service.list(null, null, "bl-0000", null, null, null, 0, 20)));
		assertEquals(Collections.singletonList(first.getId()), ids(service.list(null, null, null, null, null, true, 0, 20)));
		assertTrue(ids(service.list(null, null, null, "2026-10-06", null, null, 0, 20)).isEmpty());
		assertEquals(2, ids(service.list(null, null, null, "2026-10-05", "2026-10-05", null, 0, 20)).size());
		Map<String, Object> page = service.list(null, null, null, null, null, null, 1, 1);
		assertEquals(Collections.singletonList(first.getId()), ids(page));
		assertEquals(2L, page.get("totalElements"));

		@SuppressWarnings("unchecked")
		DeliveryDTO row = ((List<DeliveryDTO>) service.list(b.getId(), null, null, null, null, null, 0, 20).get("content")).get(0);
		assertEquals("B", row.getStoreCode());
		assertEquals(BigDecimal.valueOf(55), row.getQuantitySent());
		assertEquals(BigDecimal.valueOf(53), row.getQuantityReceived());
		assertTrue(row.isDifference());
		assertNull(row.getLines());
		assertEquals(BigDecimal.valueOf(-2), service.get(first.getId()).get().getLines().get(0).getDifference());

		assertThrows(IllegalArgumentException.class, () -> service.list(null, "LOST", null, null, null, null, 0, 20));
		assertThrows(IllegalArgumentException.class, () -> service.list(null, null, null, "yesterday", null, null, 0, 20));
		assertThrows(IllegalArgumentException.class, () -> service.list(null, null, null, null, null, null, -1, 20));
	}

	@SuppressWarnings("unchecked")
	private static List<Long> ids(Map<String, Object> page) {
		return ((List<DeliveryDTO>) page.get("content")).stream().map(DeliveryDTO::getId).collect(Collectors.toList());
	}
}
