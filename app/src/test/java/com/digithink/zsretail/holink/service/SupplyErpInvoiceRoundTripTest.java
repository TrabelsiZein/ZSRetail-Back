package com.digithink.zsretail.holink.service;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.erp.dto.ErpSupplyInvoiceDTO;
import com.digithink.zsretail.headoffice.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryCopyDTO;
import com.digithink.zsretail.headoffice.enumeration.ErpInvoiceStatus;
import com.digithink.zsretail.headoffice.model.HoErpInvoice;
import com.digithink.zsretail.headoffice.model.HoErpInvoiceLine;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoErpInvoiceRepository;
import com.digithink.zsretail.headoffice.service.CopiesDownFeed;
import com.digithink.zsretail.headoffice.service.HoErpInvoiceService;
import com.digithink.zsretail.headoffice.service.InMemoryDownTables;
import com.digithink.zsretail.holink.dto.ReceptionInputDTO;
import com.digithink.zsretail.holink.enumeration.ReceivedDeliveryStatus;
import com.digithink.zsretail.holink.model.ReceivedDelivery;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.PurchaseInvoiceHeader;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.digithink.zsretail.support.InMemoryLoyalty;
import com.digithink.zsretail.support.InMemoryPurchaseInvoices;
import com.digithink.zsretail.support.InMemoryReceivedDeliveries;
import com.digithink.zsretail.support.InMemoryStock;
import com.digithink.zsretail.support.InMemoryStoreLink;
import com.digithink.zsretail.utils.Quantities;
import com.digithink.zsretail.service.QuantityPolicy;
import com.digithink.zsretail.model.enumeration.StockMovementType;
import com.digithink.zsretail.headoffice.dto.ErpInvoiceDTO;
import com.digithink.zsretail.headoffice.enumeration.ErpInvoiceLineType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Invoices from the ERP, step (c), end to end over the copies down: the real head office (HoErpInvoiceService over a
 * fake ERP read, CopiesDownFeed) and the real store B (SupplyDownHandler, DeliveryReceptionService, SupplyInvoiceWriter),
 * each over its own in-memory tables. An ERP invoice of B's customer reaches B only, is received once with stock, cost
 * and purchase invoice, its confirmation comes back and the head office shows the difference; an item not at B waits.
 */
class SupplyErpInvoiceRoundTripTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 16, 0);
	private static final String NUMBER = "FVV26000000101";

	// Head office
	private final InMemoryCatalogue ho = new InMemoryCatalogue(1);
	private final Map<Long, HoErpInvoice> hoInvoices = new LinkedHashMap<>();
	private final List<ErpSupplyInvoiceDTO> erp = new ArrayList<>();
	private HoErpInvoiceService hoService;
	private CopiesDownFeed feed;
	private Store b;
	private Store c;

	// Store B
	private final InMemoryCatalogue db = new InMemoryCatalogue(100_000);
	private final InMemoryStock stock = new InMemoryStock(db);
	private final InMemoryStoreLink link = new InMemoryStoreLink(new InMemoryLoyalty(500_000));
	private final InMemoryReceivedDeliveries received = new InMemoryReceivedDeliveries(900_000);
	private final InMemoryPurchaseInvoices purchases = new InMemoryPurchaseInvoices(950_000);
	private DeliveryReceptionService reception;
	private SupplyDownHandler handler;
	private String cursor = "";

	@BeforeEach
	void setUp() {
		CopiesDownFeed[] holder = new CopiesDownFeed[1];
		hoService = new HoErpInvoiceService(hoRepository(), ho.storeRepository(), ho.itemRepository(),
				highest -> erp.stream().filter(i -> !hoInvoices.values().stream()
						.anyMatch(saved -> saved.getBcNumber().equals(i.getNumber()))).collect(Collectors.toList()),
				TransactionOperations.withoutTransaction(), () -> NOW, () -> holder[0], "Happyness");
		feed = new InMemoryDownTables().feed(Collections.singletonList(hoService));
		holder[0] = feed;
		feed.initialise();
		for (String code : new String[] { "B001", "B009" }) {
			ho.item(code, 10.0, null);
		}
		b = ho.store("B");
		b.setErpCustomerNo("C-0001");
		c = ho.store("C");
		c.setErpCustomerNo("C-0002");

		Item b001 = db.item("B001", 12.0, null);
		b001.setOrigin(RecordOrigin.HEAD_OFFICE);
		b001.setCostPrice(9.0);
		b001.setStockQuantity(BigDecimal.valueOf(0));
		SupplyInvoiceWriter writer = new SupplyInvoiceWriter(purchases.headerRepository(), purchases.lineRepository(),
				purchases.vendorRepository(), stock.itemRepository(), received.repository());
		reception = new DeliveryReceptionService(received.repository(), stock.itemRepository(), stock.stockService(),
				stock.stockMovementService(), TransactionOperations.withoutTransaction(), () -> NOW, writer);
		handler = new SupplyDownHandler(reception, link.downRecordLog(), TransactionOperations.withoutTransaction(),
				writer);
	}

	private static ErpSupplyInvoiceDTO invoice(String number, String customer) {
		ErpSupplyInvoiceDTO invoice = new ErpSupplyInvoiceDTO();
		invoice.setNumber(number);
		invoice.setYearPrefix("FVV26");
		invoice.setCustomerNo(customer);
		invoice.setCustomerName("Name of " + customer);
		invoice.setDocumentDate(LocalDate.of(2026, 10, 8));
		invoice.setTotalExclVat(new BigDecimal("44.000"));
		invoice.setTotalVat(new BigDecimal("8.360"));
		invoice.setTotalInclVat(new BigDecimal("52.360"));
		invoice.getLines().add(line(10000, ErpSupplyInvoiceDTO.LineType.ITEM, "B001", "6", "36.000"));
		invoice.getLines().add(line(20000, ErpSupplyInvoiceDTO.LineType.ITEM, "B009", "2", "8.000"));
		invoice.getLines().add(line(30000, ErpSupplyInvoiceDTO.LineType.OTHER, null, "1", "0.000"));
		return invoice;
	}

	private static ErpSupplyInvoiceDTO.Line line(int lineNo, ErpSupplyInvoiceDTO.LineType type, String code,
			String quantity, String amount) {
		ErpSupplyInvoiceDTO.Line line = new ErpSupplyInvoiceDTO.Line();
		line.setLineNo(lineNo);
		line.setType(type);
		line.setItemCode(code);
		line.setDescription(code == null ? "Transport" : "Item " + code);
		line.setQuantity(new BigDecimal(quantity));
		line.setUnitPrice(new BigDecimal("10"));
		line.setLineAmount(new BigDecimal(amount));
		return line;
	}

	private void pull() {
		CopiesDownAnswerDTO page = feed.pull(b, "SUPPLY", cursor, 500);
		handler.apply(page.getRecords(), page.getRemoved());
		cursor = page.getCursor();
	}

	@Test
	@DisplayName("An ERP invoice reaches B only, received once: stock, cost and purchase invoice; the confirmation shows the difference at the head office")
	void roundTrip() {
		erp.add(invoice(NUMBER, "C-0001"));
		hoService.run();
		pull();

		ReceivedDelivery atB = received.byNumber(NUMBER);
		assertEquals(ReceivedDeliveryStatus.TO_RECEIVE, atB.getStatus());
		assertTrue(atB.isErpInvoice());
		assertEquals("Happyness", atB.getSellerName());
		assertEquals("items not in this store yet: B009", link.downRecords.get("ERPINV:" + NUMBER).getInfo());
		assertTrue(feed.pull(c, "SUPPLY", "", 500).getRecords().isEmpty(), "C sees nothing");

		ReceptionInputDTO input = new ReceptionInputDTO();
		input.getLines().add(new ReceptionInputDTO.Line(10000, Quantities.of(5)));
		input.getLines().add(new ReceptionInputDTO.Line(20000, Quantities.of(2)));
		reception.receive(atB.getId(), input, "responsible");
		assertEquals(5, stock.stockOf("B001"));
		assertEquals(6.0, db.itemByCode("B001").get().getCostPrice());
		assertEquals(10.0, db.itemByCode("B001").get().getLastDirectCost(), "the invoice's unit price");
		assertEquals(12.0, db.itemByCode("B001").get().getUnitPrice(), "the selling price is never touched");
		PurchaseInvoiceHeader purchase = purchases.byNumber(NUMBER);
		assertEquals(52.36, purchase.getTotalAmount());
		assertEquals(3, purchases.linesOf(purchase).size());

		assertTrue(hoService.receiveConfirmations(b, Collections.singletonList(SupplyPushService.confirmationOf(atB)))
				.get(0).isAccepted());
		HoErpInvoice atHeadOffice = hoInvoices.values().iterator().next();
		assertEquals(ErpInvoiceStatus.RECEIVED, atHeadOffice.getStatus());
		assertTrue(atHeadOffice.getDifference());
		assertEquals(BigDecimal.valueOf(5), atHeadOffice.getLines().get(0).getQuantityReceived());

		pull(); // nothing new: the store's copy never changes
		assertEquals(1, received.deliveries.size());
		assertEquals(1, purchases.headers.size());

		// The item arrives with the catalogue: its stock and cost go in, its purchase line gets it
		Item b009 = db.item("B009", 5.0, null);
		b009.setOrigin(RecordOrigin.HEAD_OFFICE);
		handler.retry();
		assertEquals(2, stock.stockOf("B009"));
		assertEquals(4.0, b009.getCostPrice());
		assertEquals(b009.getId(), purchases.linesOf(purchase).get(1).getItem().getId());
	}

	@Test
	@DisplayName("A store that does not know the kind ERP_INVOICE reads no BL number in the copy: the old reading refuses it")
	void oldStoreRefuses() throws Exception {
		erp.add(invoice(NUMBER, "C-0001"));
		hoService.run();
		JsonNode copy = feed.pull(b, "SUPPLY", "", 500).getRecords().get(0);
		DeliveryCopyDTO asBl = new ObjectMapper().configure(
				com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
				.treeToValue(copy, DeliveryCopyDTO.class);
		assertNull(asBl.getNumber(), "the old handler answers 'record without a BL number'");
		assertFalse(copy.has("number"));
	}

	// ─── Head office ho_erp_invoice ──────────────────────────────

	private HoErpInvoiceRepository hoRepository() {
		long[] nextId = { 70_000 };
		return proxy(HoErpInvoiceRepository.class, (method, args) -> {
			switch (method) {
				case "existsByBcNumber":
					return hoInvoices.values().stream().anyMatch(i -> i.getBcNumber().equals(args[0]));
				case "findHighestByYear":
					return new ArrayList<>();
				case "findHeldNumbersWithReason": { // 2.2.1
					String part = ((String) args[0]).replace("%", "");
					return hoInvoices.values().stream().filter(i -> Boolean.TRUE.equals(i.getHeld()) && i.getStoreId() == null
							&& i.getHoldReason() != null && i.getHoldReason().contains(part)).map(HoErpInvoice::getBcNumber)
							.sorted().collect(Collectors.toList());
				}
				case "findByBcNumber":
					return hoInvoices.values().stream().filter(i -> i.getBcNumber().equals(args[0])).findFirst();
				case "delete":
					hoInvoices.remove(((HoErpInvoice) args[0]).getId());
					return null;
				case "flush":
					return null;
				case "findIdsToAssign":
					return hoInvoices.values().stream().filter(i -> i.getStoreId() == null && !i.getHeld())
							.map(HoErpInvoice::getId).collect(Collectors.toList());
				case "findById":
				case "findForUpdate":
					return Optional.ofNullable(hoInvoices.get(args[0]));
				case "findForUpdateByStoreAndNumber":
					return hoInvoices.values().stream()
							.filter(i -> Objects.equals(i.getStoreId(), args[0]) && i.getBcNumber().equals(args[1]))
							.findFirst();
				case "findForStore": {
					java.util.Collection<?> numbers = (java.util.Collection<?>) args[1];
					java.util.Collection<?> statuses = (java.util.Collection<?>) args[2];
					return hoInvoices.values().stream().filter(i -> Objects.equals(i.getStoreId(), args[0])
							&& numbers.contains(i.getBcNumber()) && statuses.contains(i.getStatus()) && !i.getHeld())
							.collect(Collectors.toList());
				}
				case "findAssignedTargets":
					return hoInvoices.values().stream().filter(i -> i.getStoreId() != null && !i.getHeld())
							.sorted(Comparator.comparing(HoErpInvoice::getBcNumber))
							.map(i -> new Object[] { i.getBcNumber(), i.getStoreId() }).collect(Collectors.toList());
				case "save":
				case "saveAndFlush": {
					HoErpInvoice invoice = (HoErpInvoice) args[0];
					if (invoice.getId() == null) {
						invoice.setId(nextId[0]++);
					}
					for (HoErpInvoiceLine line : invoice.getLines()) {
						if (line.getId() == null) {
							line.setId(nextId[0]++);
						}
					}
					hoInvoices.put(invoice.getId(), invoice);
					return invoice;
				}
				case "findPage":
					return new PageImpl<>(new ArrayList<>(hoInvoices.values()));
				default:
					return UNHANDLED;
			}
		});
	}

	// ─── 2.2.1: decimal quantities ───────────────────────────────

	private static final String BULK = "FVV26000000150";

	/** Store B's ALLOW_DECIMAL_QUANTITY. */
	private boolean decimalsAllowed;

	/** A NAV invoice of bulk perfume: B001 1.5 L at 20.000 the litre (30.000), B002 0.25 L at 20.000 (5.000). */
	private static ErpSupplyInvoiceDTO bulkInvoice(String number) {
		ErpSupplyInvoiceDTO invoice = new ErpSupplyInvoiceDTO();
		invoice.setNumber(number);
		invoice.setYearPrefix("FVV26");
		invoice.setCustomerNo("C-0001");
		invoice.setCustomerName("Name of C-0001");
		invoice.setDocumentDate(LocalDate.of(2026, 10, 10));
		invoice.setTotalExclVat(new BigDecimal("35.000"));
		invoice.setTotalVat(new BigDecimal("6.650"));
		invoice.setTotalInclVat(new BigDecimal("41.650"));
		invoice.getLines().add(bulkLine(10000, "B001", "1.5", "30.000"));
		invoice.getLines().add(bulkLine(20000, "B002", "0.25", "5.000"));
		return invoice;
	}

	private static ErpSupplyInvoiceDTO.Line bulkLine(int lineNo, String code, String quantity, String amount) {
		ErpSupplyInvoiceDTO.Line line = new ErpSupplyInvoiceDTO.Line();
		line.setLineNo(lineNo);
		line.setType(ErpSupplyInvoiceDTO.LineType.ITEM);
		line.setItemCode(code);
		line.setDescription("Bulk " + code);
		line.setQuantity(new BigDecimal(quantity));
		line.setUnitPrice(new BigDecimal("20"));
		line.setLineAmount(new BigDecimal(amount));
		return line;
	}

	/** B002 at the head office and at B; the store's setting through a policy that answers decimalsAllowed. */
	private void bulkItems() {
		ho.item("B002", 10.0, null);
		Item b002 = db.item("B002", 25.0, null);
		b002.setOrigin(RecordOrigin.HEAD_OFFICE);
		b002.setStockQuantity(BigDecimal.ZERO);
		reception.setQuantityPolicy(new QuantityPolicy() {
			@Override
			public boolean decimalAllowed() {
				return decimalsAllowed;
			}
		});
	}

	@Test
	@DisplayName("2.2.1: a NAV invoice with 1.5 and 0.25 becomes head office lines, reaches B, received as sent: stock +1.5 and +0.25, cost per unit as invoiced")
	void decimalInvoiceReceivedAsSent() {
		bulkItems();
		decimalsAllowed = true;
		erp.add(bulkInvoice(BULK));
		Map<String, Object> run = hoService.run();

		assertEquals(0, run.get("held"), "1.5 is no longer held");
		HoErpInvoice atHeadOffice = hoInvoices.values().iterator().next();
		assertFalse(atHeadOffice.getHeld());
		assertEquals("1.5", atHeadOffice.getLines().get(0).getQuantity().toPlainString());
		assertEquals("0.25", atHeadOffice.getLines().get(1).getQuantity().toPlainString());
		assertEquals(20.0, atHeadOffice.getLines().get(0).getUnitCost(), "30.000 / 1.5, the 2.2.0 rule");
		assertEquals(30.0, atHeadOffice.getLines().get(0).getLineAmount(), "as NAV sent it");

		pull();
		ReceivedDelivery atB = received.byNumber(BULK);
		assertEquals("1.5", atB.getLines().get(0).getQuantitySent().toPlainString());
		reception.receive(atB.getId(), null, "responsible");

		assertEquals("1.5", stock.quantityOf("B001").toPlainString());
		assertEquals("0.25", stock.quantityOf("B002").toPlainString());
		assertEquals("1.5", stock.movements(StockMovementType.DELIVERY_IN).get(0).getQuantity().toPlainString());
		assertEquals(20.0, db.itemByCode("B001").get().getCostPrice(), "per unit, from the invoice line");
		assertEquals(20.0, db.itemByCode("B001").get().getLastDirectCost());
		assertEquals(20.0, db.itemByCode("B002").get().getCostPrice());
		PurchaseInvoiceHeader purchase = purchases.byNumber(BULK);
		assertEquals("1.5", purchases.linesOf(purchase).get(0).getQuantity().toPlainString(), "as invoiced");

		assertTrue(hoService.receiveConfirmations(b, Collections.singletonList(SupplyPushService.confirmationOf(atB)))
				.get(0).isAccepted());
		assertEquals(ErpInvoiceStatus.RECEIVED, atHeadOffice.getStatus());
		assertFalse(atHeadOffice.getDifference());
	}

	@Test
	@DisplayName("2.2.1: 1.2 received of 1.5: stock +1.2, the head office shows 0.3 of difference on the line")
	void decimalInvoicePartlyReceived() {
		bulkItems();
		decimalsAllowed = true;
		erp.add(bulkInvoice(BULK));
		hoService.run();
		pull();
		ReceivedDelivery atB = received.byNumber(BULK);
		ReceptionInputDTO input = new ReceptionInputDTO();
		input.getLines().add(new ReceptionInputDTO.Line(10000, new BigDecimal("1.2")));
		reception.receive(atB.getId(), input, "responsible");

		assertEquals("1.2", stock.quantityOf("B001").toPlainString());
		assertEquals("0.25", stock.quantityOf("B002").toPlainString(), "a line absent from the body: as sent");
		assertEquals(20.0, db.itemByCode("B001").get().getCostPrice(), "the quantity received never enters the cost");

		hoService.receiveConfirmations(b, Collections.singletonList(SupplyPushService.confirmationOf(atB)));
		HoErpInvoice atHeadOffice = hoInvoices.values().iterator().next();
		assertTrue(atHeadOffice.getDifference());
		assertEquals("1.2", atHeadOffice.getLines().get(0).getQuantityReceived().toPlainString());
		ErpInvoiceDTO view = hoService.get(atHeadOffice.getId()).get();
		assertEquals("-0.3", view.getLines().get(0).getDifference().toPlainString());
		assertEquals("0", view.getLines().get(1).getDifference().toPlainString());
	}

	@Test
	@DisplayName("2.2.1: setting off at the store, Receive of an invoice with 1.5 is refused naming the setting; nothing changes")
	void decimalReceptionRefusedWhenSettingOff() {
		bulkItems();
		erp.add(bulkInvoice(BULK));
		hoService.run();
		pull();
		ReceivedDelivery atB = received.byNumber(BULK);

		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> reception.receive(atB.getId(), null, "responsible"));
		assertEquals(BULK + " cannot be received: line 10000 was sent with the quantity 1.5, and decimal quantities are"
				+ " not allowed in this store (General Setup, Allow decimal quantities).", e.getMessage());
		assertEquals(ReceivedDeliveryStatus.TO_RECEIVE, atB.getStatus());
		assertEquals("0", stock.quantityOf("B001").toPlainString());
		assertTrue(purchases.headers.isEmpty());
	}

	@Test
	@DisplayName("2.2.1: an invoice a 2.2.0 head office held for 1.5 is read again by number at the next run, released, sent to B")
	void heldIn220ReadAgain() {
		bulkItems();
		HoErpInvoice old = new HoErpInvoice(); // as 2.2.0 saved it: held, the quantity not kept
		old.setBcNumber(BULK);
		old.setYearPrefix("FVV26");
		old.setCustomerNo("C-0001");
		old.setStatus(ErpInvoiceStatus.READ);
		old.setHeld(true);
		old.setHoldReason("line 10000: quantity 1.5 of item B001 is not a whole number");
		HoErpInvoiceLine oldLine = new HoErpInvoiceLine();
		oldLine.setInvoice(old);
		oldLine.setLineNo(10000);
		oldLine.setLineType(ErpInvoiceLineType.ITEM);
		oldLine.setItemCode("B001");
		old.getLines().add(oldLine);
		old.setId(69_999L);
		hoInvoices.put(old.getId(), old);
		erp.add(bulkInvoice(BULK)); // the ERP still has it; the read after the highest number never returns it again
		List<List<String>> askedAgain = new ArrayList<>();
		hoService.setRereader(numbers -> {
			askedAgain.add(numbers);
			return erp.stream().filter(i -> numbers.contains(i.getNumber())).collect(Collectors.toList());
		});

		Map<String, Object> run = hoService.run();

		assertEquals(Collections.singletonList(Collections.singletonList(BULK)), askedAgain);
		assertEquals(1, run.get("heldReleased"));
		assertEquals(1, run.get("assigned"));
		HoErpInvoice released = hoInvoices.values().iterator().next();
		assertFalse(released.getHeld());
		assertNull(released.getHoldReason());
		assertEquals("1.5", released.getLines().get(0).getQuantity().toPlainString());
		assertEquals(b.getId(), released.getStoreId());
		pull();
		assertEquals("1.5", received.byNumber(BULK).getLines().get(0).getQuantitySent().toPlainString());

		hoService.run();
		assertEquals(1, askedAgain.size(), "read again once: nothing held for a whole number any more");
	}
}
