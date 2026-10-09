package com.digithink.zsretail.holink.service;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
		input.getLines().add(new ReceptionInputDTO.Line(10000, 5));
		input.getLines().add(new ReceptionInputDTO.Line(20000, 2));
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
		assertEquals(Integer.valueOf(5), atHeadOffice.getLines().get(0).getQuantityReceived());

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
}
