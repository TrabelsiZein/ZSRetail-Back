package com.digithink.zsretail.headoffice.service;

import java.math.BigDecimal;
import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.headoffice.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryConfirmationDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryInputDTO;
import com.digithink.zsretail.headoffice.dto.SupplyInvoiceCopyDTO;
import com.digithink.zsretail.headoffice.dto.SupplyInvoiceDTO;
import com.digithink.zsretail.headoffice.enumeration.DeliveryStatus;
import com.digithink.zsretail.headoffice.enumeration.InvoiceRhythm;
import com.digithink.zsretail.headoffice.enumeration.SupplyPriceMode;
import com.digithink.zsretail.headoffice.model.HoDelivery;
import com.digithink.zsretail.headoffice.model.HoSupplyInvoice;
import com.digithink.zsretail.headoffice.model.HoSupplyInvoiceLine;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.model.CompanyInformation;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.repository.CompanyInformationRepository;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.digithink.zsretail.support.InMemoryStock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Head office plan, step 7B, part 2: the supply invoices. Grouped rhythm (preview writing nothing, the invoice from two
 * received BLs on the confirmed quantities at the supply price with each item's VAT, FHO-yyyy-000001, BLs INVOICED,
 * the buyer and seller copied, sent to its store only); every refusal with nothing written; rhythm PER_BL (created when
 * the confirmation arrives; a missing price keeps the BL received with its reason, the confirmation accepted); the tax
 * stamp setting off and on; percentage mode; paid and what each store owes; one sequence per year. Real
 * HoSupplyInvoiceService, HoDeliveryService, HoSupplyPriceService, CopiesDownFeed over in-memory tables.
 */
class HoSupplyInvoiceServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 11, 0);

	private InMemoryCatalogue ho;
	private InMemoryStock stock;
	private InMemoryDeliveries tables;
	private InMemoryInvoices invoiceTables;
	private CopiesDownFeed feed;
	private HoDeliveryService deliveries;
	private HoSupplyInvoiceService invoices;
	private final Map<String, String> settings = new HashMap<>();
	private LocalDateTime clock = NOW;
	private Store b;
	private Store c;
	private Item b001;
	private Item b002;

	@BeforeEach
	void setUp() {
		ho = new InMemoryCatalogue(1);
		stock = new InMemoryStock(ho);
		tables = new InMemoryDeliveries(ho);
		invoiceTables = new InMemoryInvoices();
		CopiesDownFeed[] feedRef = new CopiesDownFeed[1];
		HoSupplyPriceService prices = new HoSupplyPriceService(ho.supplyPriceRepository(), ho.priceLineRepository(),
				stock.itemRepository());
		CompanyInformation company = new CompanyInformation();
		company.setCompanyName("Happy Head Office SA");
		company.setMatriculeFiscal("0000001/A/M/000");
		company.setAddress("1 rue du Lac");
		company.setCity("Tunis");
		CompanyInformationRepository companies = proxy(CompanyInformationRepository.class,
				(method, args) -> "findAll".equals(method) ? new ArrayList<>(Collections.singletonList(company)) : UNHANDLED);
		GeneralSetupService setup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return settings.get(code);
			}
		};
		invoices = new HoSupplyInvoiceService(invoiceTables.repository(), tables.deliveryRepository(),
				ho.storeRepository(), stock.itemRepository(), tables.sequenceRepository(), prices, companies, setup,
				() -> feedRef[0], TransactionOperations.withoutTransaction(), () -> clock);
		deliveries = new HoDeliveryService(tables.deliveryRepository(), ho.storeRepository(), stock.itemRepository(),
				tables.sequenceRepository(), stock.stockService(), stock.stockMovementService(), () -> feedRef[0],
				TransactionOperations.withoutTransaction(), () -> clock, () -> invoices);
		feed = new InMemoryDownTables().feed(Collections.singletonList(deliveries));
		feedRef[0] = feed;
		b001 = ho.item("B001", 10.0, null);
		b001.setStockQuantity(BigDecimal.valueOf(500));
		b002 = ho.item("B002", 4.0, null);
		b002.setStockQuantity(BigDecimal.valueOf(500));
		b002.setDefaultVAT(7);
		ho.supplyPrice(b001, 6.0);
		ho.supplyPrice(b002, 2.5);
		b = ho.store("B");
		b.setDeliveriesInvoiced(true);
		b.setInvoiceRhythm(InvoiceRhythm.GROUPED);
		b.setBillingLegalName("Happy Hammamet SARL");
		b.setBillingTaxNumber("1234567/B/M/000");
		c = ho.store("C");
		deliveries.initialise();
		feed.initialise();
	}

	/** A BL validated for the store and received with these quantities (by the store's confirmation). */
	private HoDelivery received(Store store, int b001Received, int b002Received) {
		DeliveryInputDTO input = new DeliveryInputDTO();
		input.setStoreId(store.getId());
		input.setLines(Arrays.asList(new DeliveryInputDTO.Line("B001", 50), new DeliveryInputDTO.Line("B002", 10)));
		DeliveryDTO sent = deliveries.validate(deliveries.create(input).getId(), "admin").get();
		DeliveryConfirmationDTO confirmation = new DeliveryConfirmationDTO();
		confirmation.setNumber(sent.getNumber());
		confirmation.setLines(Arrays.asList(new DeliveryConfirmationDTO.Line(1, "B001", b001Received),
				new DeliveryConfirmationDTO.Line(2, "B002", b002Received)));
		assertTrue(deliveries.receiveConfirmations(store, Collections.singletonList(confirmation)).get(0).isAccepted());
		return tables.deliveries.get(sent.getId());
	}

	private static SupplyInvoiceDTO request(Store store, HoDelivery... bls) {
		SupplyInvoiceDTO request = new SupplyInvoiceDTO();
		request.setStoreId(store.getId());
		request.setDeliveryIds(Arrays.stream(bls).map(HoDelivery::getId).collect(Collectors.toList()));
		return request;
	}

	@Test
	@DisplayName("Grouped: preview writes nothing; the invoice of two BLs on the confirmed quantities, VAT per item, numbered, BLs INVOICED, to its store only")
	void groupedInvoice() {
		HoDelivery first = received(b, 48, 10);
		HoDelivery second = received(b, 50, 0);

		SupplyInvoiceDTO preview = invoices.preview(request(b, first, second));
		assertNull(preview.getInvoiceNumber());
		assertTrue(preview.getMissingPrices().isEmpty());
		assertTrue(invoiceTables.invoices.isEmpty(), "a preview writes nothing");
		assertEquals(DeliveryStatus.RECEIVED, first.getStatus());

		SupplyInvoiceDTO invoice = invoices.create(request(b, first, second), "admin");

		assertEquals("FHO-2026-000001", invoice.getInvoiceNumber());
		assertEquals("2026-10-06", invoice.getInvoiceDate());
		assertEquals(3, invoice.getLines().size(), "B002 received 0 on the second BL: no line");
		SupplyInvoiceCopyDTO.Line line = invoice.getLines().get(0);
		assertEquals(first.getNumber(), line.getDeliveryNumber());
		assertEquals(48, line.getQuantity());
		assertEquals(6.0, line.getUnitPrice());
		assertEquals(288.0, line.getLineTotal());
		assertEquals(19, line.getVatPercent());
		assertEquals(54.72, line.getVatAmount());
		assertEquals(342.72, line.getLineTotalIncludingVat());
		assertEquals(7, invoice.getLines().get(1).getVatPercent(), "B002 at its own VAT");
		assertEquals(26.75, invoice.getLines().get(1).getLineTotalIncludingVat(), "10 x 2.5 = 25 + 7%");
		assertEquals(288.0 + 25.0 + 300.0, invoice.getSubtotal());
		assertEquals(54.72 + 1.75 + 57.0, invoice.getTaxAmount(), 0.0005);
		assertEquals(726.47, invoice.getTotalAmount());
		assertEquals("Happy Hammamet SARL", invoice.getBuyerName());
		assertEquals("1234567/B/M/000", invoice.getBuyerTaxNumber());
		assertEquals("Happy Head Office SA", invoice.getSellerName());
		assertEquals("0000001/A/M/000", invoice.getSellerTaxNumber());
		assertEquals("1 rue du Lac, Tunis", invoice.getSellerAddress());
		assertEquals(Arrays.asList(first.getNumber(), second.getNumber()), invoice.getDeliveryNumbers());
		assertFalse(invoice.getPaid());
		for (HoDelivery bl : Arrays.asList(first, second)) {
			assertEquals(DeliveryStatus.INVOICED, bl.getStatus());
			assertEquals(invoice.getId(), bl.getInvoiceId());
		}
		assertEquals("FHO-2026-000001", deliveries.get(first.getId()).get().getInvoiceNumber());

		CopiesDownAnswerDTO atB = feed.pull(b, "SUPPLY", "", 100);
		JsonNode copy = atB.getRecords().stream().filter(r -> "INVOICE".equals(r.get("kind").asText())).findFirst().get();
		assertEquals("FHO-2026-000001", copy.get("invoiceNumber").asText());
		assertEquals(726.47, copy.get("totalAmount").asDouble());
		assertEquals(3, copy.get("lines").size());
		assertFalse(copy.toString().contains("\"id\""), "no database id");
		assertTrue(feed.pull(c, "SUPPLY", "", 100).getRecords().isEmpty(), "never sent to another store");
	}

	@Test
	@DisplayName("Refusals, nothing written: deliveries not invoiced, no BL, a BL sent, of another store, already invoiced, an item without a supply price")
	void refusals() {
		HoDelivery ok = received(b, 48, 10);
		DeliveryInputDTO input = new DeliveryInputDTO();
		input.setStoreId(b.getId());
		input.setLines(Collections.singletonList(new DeliveryInputDTO.Line("B001", 5)));
		HoDelivery sentOnly = tables.deliveries.get(deliveries.validate(deliveries.create(input).getId(), "a").get().getId());
		HoDelivery atC = received(c, 1, 1);

		assertConflict("The deliveries of the store C are not invoiced (setting on the Stores page).", request(c, atC));
		SupplyInvoiceDTO none = request(b);
		assertEquals("Choose at least one received BL.",
				assertThrows(IllegalArgumentException.class, () -> invoices.create(none, "a")).getMessage());
		assertConflict(sentOnly.getNumber() + " is SENT: only received BLs can be invoiced.", request(b, ok, sentOnly));
		assertConflict(atC.getNumber() + " is not a BL of the store B.", request(b, atC));
		ho.supplyPrices.clear();
		ho.supplyPrice(b001, 6.0); // B002 without a supply price now
		assertConflict("No supply price for the store B: B002.", request(b, ok));
		assertEquals(Collections.singletonList("B002"), invoices.preview(request(b, ok)).getMissingPrices());
		assertTrue(invoiceTables.invoices.isEmpty());
		assertEquals(DeliveryStatus.RECEIVED, ok.getStatus());

		ho.supplyPrice(b002, 2.5);
		invoices.create(request(b, ok), "a");
		assertConflict(ok.getNumber() + " is already invoiced.", request(b, ok));
		assertEquals(1, invoiceTables.invoices.size());
	}

	@Test
	@DisplayName("Preview with an item without a supply price: its line in place (code, name, BL, quantity, flagged missing, no price), totals of the priced lines only")
	void previewMissingPriceLines() throws Exception {
		HoDelivery first = received(b, 48, 10);
		HoDelivery second = received(b, 50, 3);
		ho.supplyPrices.clear();
		ho.supplyPrice(b001, 6.0); // B002 without a supply price now

		SupplyInvoiceDTO preview = invoices.preview(request(b, first, second));

		assertEquals(Collections.singletonList("B002"), preview.getMissingPrices(), "missingPrices unchanged");
		assertEquals(4, preview.getLines().size(), "two priced lines and two lines without a price");
		List<String> order = preview.getLines().stream()
				.map(l -> l.getDeliveryNumber() + "/" + l.getItemCode() + "/" + l.getLineNo()).collect(Collectors.toList());
		assertEquals(Arrays.asList(first.getNumber() + "/B001/1", first.getNumber() + "/B002/null",
				second.getNumber() + "/B001/2", second.getNumber() + "/B002/null"), order, "in BL order");
		SupplyInvoiceCopyDTO.Line missing = preview.getLines().get(1);
		assertEquals(Boolean.TRUE, missing.getMissingPrice());
		assertEquals(b002.getName(), missing.getItemName());
		assertEquals(10, missing.getQuantity());
		assertNull(missing.getUnitPrice());
		assertNull(missing.getLineTotal());
		assertNull(missing.getLineTotalIncludingVat());
		assertEquals(3, preview.getLines().get(3).getQuantity());
		assertNull(preview.getLines().get(0).getMissingPrice());
		assertEquals(288.0 + 300.0, preview.getSubtotal(), "priced lines only");
		assertEquals(54.72 + 57.0, preview.getTaxAmount(), 0.0005);
		assertEquals(342.72 + 357.0, preview.getTotalAmount());
		String json = new ObjectMapper().writeValueAsString(preview.getLines().get(0));
		assertFalse(json.contains("missingPrice"), "a priced line has no flag: " + json);
		assertTrue(invoiceTables.invoices.isEmpty());
	}

	private void assertConflict(String message, SupplyInvoiceDTO request) {
		assertEquals(message, assertThrows(IllegalStateException.class, () -> invoices.create(request, "a")).getMessage());
	}

	@Test
	@DisplayName("Rhythm PER_BL: invoiced when the confirmation arrives; a missing price keeps the BL received with its reason, the confirmation accepted")
	void perBl() {
		b.setInvoiceRhythm(InvoiceRhythm.PER_BL);
		HoDelivery bl = received(b, 48, 10);
		assertEquals(DeliveryStatus.INVOICED, bl.getStatus());
		assertEquals("AUTO", invoiceTables.invoices.get(bl.getInvoiceId()).getCreatedBy());

		ho.supplyPrices.clear();
		ho.supplyPrice(b001, 6.0);
		HoDelivery waiting = received(b, 50, 10); // accepted all the same (asserted in received)
		assertEquals(DeliveryStatus.RECEIVED, waiting.getStatus());
		assertEquals("No supply price for the store B: B002.", waiting.getInvoiceNote());
		List<Map<String, Object>> toInvoice = invoices.toInvoice(b.getId());
		assertEquals(1, toInvoice.size());
		assertEquals("No supply price for the store B: B002.", toInvoice.get(0).get("invoiceNote"));

		ho.supplyPrice(b002, 2.5);
		invoices.create(request(b, waiting), "admin");
		assertEquals(DeliveryStatus.INVOICED, waiting.getStatus());
		assertNull(waiting.getInvoiceNote());
		assertEquals(2, invoiceTables.invoices.size());

		HoDelivery notInvoiced = received(c, 1, 1); // C: deliveries not invoiced, nothing happens
		assertEquals(DeliveryStatus.RECEIVED, notInvoiced.getStatus());
		assertNull(notInvoiced.getInvoiceNote());
	}

	@Test
	@DisplayName("Tax stamp: off, no line; on, one TAX_STAMP line at VAT 0 of SUPPLY_INVOICE_TAX_STAMP_MILLIMES (1000 by default, then 600); the till stamp setting is not read")
	void taxStamp() {
		ho.item("TAX_STAMP", 0.1, null);
		HoDelivery first = received(b, 48, 10);
		SupplyInvoiceDTO without = invoices.create(request(b, first), "a");
		assertEquals(2, without.getLines().size());

		settings.put(HoSupplyInvoiceService.TAX_STAMP_SETTING, "true");
		HoDelivery second = received(b, 48, 10);
		SupplyInvoiceDTO with = invoices.create(request(b, second), "a");
		assertEquals(3, with.getLines().size());
		SupplyInvoiceCopyDTO.Line stamp = with.getLines().get(2);
		assertEquals("TAX_STAMP", stamp.getItemCode());
		assertNull(stamp.getDeliveryNumber());
		assertEquals(1, stamp.getQuantity());
		assertEquals(1.0, stamp.getUnitPrice(), "SUPPLY_INVOICE_TAX_STAMP_MILLIMES absent: 1000 millimes");
		assertEquals(0, stamp.getVatPercent());
		assertEquals(0.0, stamp.getVatAmount());
		assertEquals(HoSupplyPriceService.round(without.getTotalAmount() + 1.0), with.getTotalAmount());
		assertEquals(without.getTaxAmount(), with.getTaxAmount(), "no VAT on the stamp");

		settings.put("TAX_STAMP_VALUE_MILLIMES", "100"); // the till ticket's stamp: not read
		settings.put(HoSupplyInvoiceService.TAX_STAMP_MILLIMES_SETTING, "600");
		HoDelivery third = received(b, 1, 0);
		assertEquals(0.6, invoices.create(request(b, third), "a").getLines().get(1).getUnitPrice());
	}

	@Test
	@DisplayName("Percentage mode: the selling price minus the percentage; a store without a percentage: 409")
	void percentMode() {
		b.setSupplyPriceMode(SupplyPriceMode.PERCENT_OFF);
		HoDelivery bl = received(b, 10, 0);
		assertTrue(assertThrows(IllegalStateException.class, () -> invoices.create(request(b, bl), "a")).getMessage()
				.contains("no percentage is set"));
		b.setSupplyDiscountPercent(30.0);
		assertEquals(7.0, invoices.create(request(b, bl), "a").getLines().get(0).getUnitPrice(), "10.000 - 30%");
	}

	@Test
	@DisplayName("Paid and unpaid, the list filters, what each store owes; one sequence per year")
	void paidAndBalances() {
		HoDelivery first = received(b, 48, 10);
		HoDelivery second = received(b, 10, 0);
		SupplyInvoiceDTO one = invoices.create(request(b, first), "a");
		clock = LocalDateTime.of(2027, 1, 2, 9, 0);
		SupplyInvoiceDTO two = invoices.create(request(b, second), "a");
		assertEquals("FHO-2027-000001", two.getInvoiceNumber(), "a new year starts again at 1");

		SupplyInvoiceDTO paid = invoices.setPaid(one.getId(), true, null, "transfer 12").get();
		assertTrue(paid.getPaid());
		assertEquals(LocalDate.of(2027, 1, 2), paid.getPaidDate(), "today when absent");
		assertEquals("transfer 12", paid.getPaidNote());
		assertThrows(IllegalArgumentException.class, () -> invoices.setPaid(one.getId(), null, null, null));
		assertFalse(invoices.setPaid(999L, true, null, null).isPresent());

		assertEquals(1L, invoices.list(b.getId(), false, null, null, 0, 20).get("totalElements"));
		assertEquals(2L, invoices.list(null, null, null, null, 0, 20).get("totalElements"));
		assertEquals(1L, invoices.list(null, null, "2027-01-01", null, 0, 20).get("totalElements"));

		List<Map<String, Object>> balances = invoices.balances();
		assertEquals(1, balances.size(), "C has no invoice");
		Map<String, Object> atB = balances.get(0);
		assertEquals("B", atB.get("storeCode"));
		assertEquals(2L, atB.get("invoiceCount"));
		assertEquals(one.getTotalAmount(), atB.get("paid"));
		assertEquals(two.getTotalAmount(), atB.get("unpaid"));
		assertEquals(1L, atB.get("unpaidCount"));

		invoices.setPaid(one.getId(), false, null, null);
		HoSupplyInvoice reopened = invoiceTables.invoices.get(one.getId());
		assertFalse(reopened.getPaid());
		assertNull(reopened.getPaidDate());
		for (HoSupplyInvoiceLine line : reopened.getLines()) {
			assertTrue(line.getLineNo() > 0);
		}
	}

	@Test
	@DisplayName("Nothing received: 409 (also with the stamp on, also in the preview); left out of /to-invoice; PER_BL: no invoice, no invoice_note")
	void nothingReceived() {
		settings.put(HoSupplyInvoiceService.TAX_STAMP_SETTING, "true");
		HoDelivery zero = received(b, 0, 0);
		HoDelivery some = received(b, 1, 0);

		assertEquals("Nothing was received on these BLs: nothing to invoice.",
				assertThrows(IllegalStateException.class, () -> invoices.create(request(b, zero), "a")).getMessage());
		assertThrows(IllegalStateException.class, () -> invoices.preview(request(b, zero)));
		assertTrue(invoiceTables.invoices.isEmpty(), "never an invoice of the stamp alone");
		assertEquals(Collections.singletonList(some.getId()),
				invoices.toInvoice(b.getId()).stream().map(r -> r.get("id")).collect(Collectors.toList()));
		assertEquals(2, invoices.create(request(b, zero, some), "a").getLines().size(), "with another BL: B001 and the stamp");

		b.setInvoiceRhythm(InvoiceRhythm.PER_BL);
		HoDelivery perBlZero = received(b, 0, 0);
		assertEquals(DeliveryStatus.RECEIVED, perBlZero.getStatus());
		assertNull(perBlZero.getInvoiceNote());
		assertEquals(1, invoiceTables.invoices.size());
	}

	@Test
	@DisplayName("Invoice date: in the future 400; before the last invoice 400 (numbers follow dates); the same day and later accepted; the preview too")
	void invoiceDates() {
		HoDelivery first = received(b, 1, 0);
		HoDelivery second = received(b, 2, 0);
		HoDelivery third = received(b, 3, 0);
		SupplyInvoiceDTO tomorrow = request(b, first);
		tomorrow.setInvoiceDate("2026-10-07");
		assertEquals("The invoice date cannot be in the future.",
				assertThrows(IllegalArgumentException.class, () -> invoices.create(tomorrow, "a")).getMessage());
		assertThrows(IllegalArgumentException.class, () -> invoices.preview(tomorrow));

		SupplyInvoiceDTO lastWeek = request(b, first);
		lastWeek.setInvoiceDate("2026-09-30");
		assertEquals("FHO-2026-000001", invoices.create(lastWeek, "a").getInvoiceNumber(), "a past date, no invoice yet");
		SupplyInvoiceDTO today = request(b, second);
		assertEquals("FHO-2026-000002", invoices.create(today, "a").getInvoiceNumber());

		SupplyInvoiceDTO before = request(b, third);
		before.setInvoiceDate("2026-10-05");
		assertEquals("The invoice date cannot be before the date of the last invoice (FHO-2026-000002 of 2026-10-06).",
				assertThrows(IllegalArgumentException.class, () -> invoices.create(before, "a")).getMessage());
		assertThrows(IllegalArgumentException.class, () -> invoices.preview(before));
		assertEquals(DeliveryStatus.RECEIVED, third.getStatus());
		assertEquals(2, invoiceTables.invoices.size());
	}
}
