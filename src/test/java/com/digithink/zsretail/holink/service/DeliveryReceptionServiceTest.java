package com.digithink.zsretail.holink.service;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.headoffice.dto.DeliveryConfirmationDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryCopyDTO;
import com.digithink.zsretail.headoffice.dto.ErpInvoiceCopyDTO;
import com.digithink.zsretail.holink.dto.ReceivedDeliveryDTO;
import com.digithink.zsretail.holink.dto.ReceptionInputDTO;
import com.digithink.zsretail.holink.enumeration.ReceivedDocumentKind;
import com.digithink.zsretail.holink.enumeration.ReceivedLineType;
import com.digithink.zsretail.holink.model.ReceivedDelivery;
import com.digithink.zsretail.holink.model.ReceivedDeliveryLine;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.PurchaseInvoiceHeader;
import com.digithink.zsretail.model.PurchaseInvoiceLine;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.digithink.zsretail.support.InMemoryPurchaseInvoices;
import com.digithink.zsretail.support.InMemoryReceivedDeliveries;
import com.digithink.zsretail.support.InMemoryStock;

/**
 * Invoices from the ERP, step (c), at the store: an ERP invoice saved once; its reception puts the quantities received in
 * the stock, the ERP's net price in the cost of each head office item (weighted over its paid lines, never from a line at
 * amount 0), and writes the purchase invoice as invoiced, in the same action; an item not here waits and gets its stock,
 * its cost and its purchase line later; an OTHER line takes no quantity and is on the purchase invoice. A plain BL is
 * received exactly as before. Real DeliveryReceptionService and SupplyInvoiceWriter over in-memory tables.
 */
class DeliveryReceptionServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 14, 0);
	private static final String NUMBER = "FVV26000000101";

	private InMemoryCatalogue db;
	private InMemoryStock stock;
	private InMemoryReceivedDeliveries received;
	private InMemoryPurchaseInvoices purchases;
	private DeliveryReceptionService reception;

	@BeforeEach
	void setUp() {
		db = new InMemoryCatalogue(100_000);
		stock = new InMemoryStock(db);
		received = new InMemoryReceivedDeliveries(900_000);
		purchases = new InMemoryPurchaseInvoices(950_000);
		for (String code : new String[] { "B001", "B002" }) {
			Item item = db.item(code, 10.0, null);
			item.setOrigin(RecordOrigin.HEAD_OFFICE);
			item.setCostPrice(9.0);
			item.setLastDirectCost(9.0);
			item.setLastDirectNetCost(9.0);
			item.setStockQuantity(BigDecimal.valueOf(0));
		}
		SupplyInvoiceWriter writer = new SupplyInvoiceWriter(purchases.headerRepository(), purchases.lineRepository(),
				purchases.vendorRepository(), stock.itemRepository(), received.repository());
		reception = new DeliveryReceptionService(received.repository(), stock.itemRepository(), stock.stockService(),
				stock.stockMovementService(), TransactionOperations.withoutTransaction(), () -> NOW, writer);
	}

	// ─── Copies ──────────────────────────────────────────────────

	static ErpInvoiceCopyDTO erpCopy(String number, ErpInvoiceCopyDTO.Line... lines) {
		ErpInvoiceCopyDTO copy = new ErpInvoiceCopyDTO();
		copy.setInvoiceNumber(number);
		copy.setDocumentDate("2026-03-02");
		copy.setPostingDate("2026-03-03");
		copy.setSellerName("Happyness");
		copy.setCustomerNo("C-0001");
		copy.setCustomerName("Store One SARL");
		copy.setSentAt("2026-10-09T10:00:00");
		copy.setTotalExclVat(41.0);
		copy.setTotalVat(7.79);
		copy.setTotalInclVat(48.79);
		copy.setLines(Arrays.asList(lines));
		return copy;
	}

	static ErpInvoiceCopyDTO.Line itemLine(int lineNo, String code, int quantity, double amount) {
		ErpInvoiceCopyDTO.Line line = new ErpInvoiceCopyDTO.Line();
		line.setLineNo(lineNo);
		line.setLineType("ITEM");
		line.setItemCode(code);
		line.setDescription("Name of " + code);
		line.setQuantity(quantity);
		line.setUnitPrice(10.0);
		line.setLineDiscountPercent(40.0);
		line.setLineAmount(amount);
		line.setUnitCost(amount == 0 ? null : amount / quantity);
		return line;
	}

	static ErpInvoiceCopyDTO.Line otherLine(int lineNo, double amount) {
		ErpInvoiceCopyDTO.Line line = new ErpInvoiceCopyDTO.Line();
		line.setLineNo(lineNo);
		line.setLineType("OTHER");
		line.setDescription("Transport");
		line.setQuantity(1);
		line.setLineAmount(amount);
		return line;
	}

	private static ReceptionInputDTO quantities(int... lineAndQuantity) {
		ReceptionInputDTO input = new ReceptionInputDTO();
		for (int i = 0; i < lineAndQuantity.length; i += 2) {
			input.getLines().add(new ReceptionInputDTO.Line(lineAndQuantity[i], lineAndQuantity[i + 1]));
		}
		return input;
	}

	private Item item(String code) {
		return db.itemByCode(code).get();
	}

	private ReceivedDelivery invoice() {
		return received.byNumber(NUMBER);
	}

	// ─── Saved ───────────────────────────────────────────────────

	@Test
	@DisplayName("Saved once TO_RECEIVE: kind ERP_INVOICE, seller, totals; ITEM lines resolved or waiting, OTHER without item or quantity")
	void savedOnce() {
		DeliveryReceptionService.Outcome first = reception.saveReceived(erpCopy(NUMBER, itemLine(20000, "B001", 6, 36),
				itemLine(30000, "B009", 2, 8), otherLine(40000, 5)));
		assertTrue(first.isWritten());
		assertEquals(Arrays.asList("B009"), first.getMissingItems());
		ReceivedDelivery saved = invoice();
		assertEquals(ReceivedDocumentKind.ERP_INVOICE, saved.getDocumentKind());
		assertEquals("Happyness", saved.getSellerName());
		assertEquals("Store One SARL", saved.getCustomerName());
		assertEquals(LocalDate.of(2026, 3, 2), saved.getDocumentDate());
		assertEquals(41.0, saved.getTotalExclVat());
		assertEquals(48.79, saved.getTotalInclVat());
		ReceivedDeliveryLine b001 = saved.getLines().get(0);
		assertEquals(item("B001").getId(), b001.getItemId());
		assertEquals(Integer.valueOf(6), b001.getQuantitySent());
		assertEquals(6.0, b001.getUnitCost());
		assertNull(saved.getLines().get(1).getItemId(), "waits for its item, like a BL line");
		ReceivedDeliveryLine transport = saved.getLines().get(2);
		assertEquals(ReceivedLineType.OTHER, transport.getLineType());
		assertEquals("", transport.getItemCode());
		assertEquals("Transport", transport.getItemName());
		assertNull(transport.getItemId());
		assertEquals(Integer.valueOf(0), transport.getQuantitySent());
		assertEquals(5.0, transport.getLineAmount());
		assertNull(transport.getUnitCost());

		DeliveryReceptionService.Outcome again = reception.saveReceived(erpCopy(NUMBER, itemLine(20000, "B001", 99, 1)));
		assertFalse(again.isWritten());
		assertEquals(Integer.valueOf(6), invoice().getLines().get(0).getQuantitySent(), "never changed");
		assertEquals(1, received.deliveries.size());
	}

	// ─── Received ────────────────────────────────────────────────

	@Test
	@DisplayName("5 of 6 received: stock +5, cost = line amount / 6 (the price paid), purchase invoice of 6 with the ERP's totals")
	void fiveOfSix() {
		reception.saveReceived(erpCopy(NUMBER, itemLine(20000, "B001", 6, 36)));
		reception.receive(invoice().getId(), quantities(20000, 5), "responsible");

		assertEquals(5, stock.stockOf("B001"));
		Item b001 = item("B001");
		assertEquals(6.0, b001.getCostPrice());
		assertEquals(10.0, b001.getLastDirectCost(), "the unit price before the 40 % discount");
		assertEquals(10.0, b001.getUnitPrice(), "the selling price is never touched");
		assertEquals(6.0, b001.getLastDirectNetCost());
		assertEquals("HEAD_OFFICE", b001.getUpdatedBy());
		assertTrue(invoice().getLines().get(0).getCostApplied());
		assertEquals(NUMBER, invoice().getInvoiceNumber());

		PurchaseInvoiceHeader header = purchases.byNumber(NUMBER);
		assertEquals(LocalDate.of(2026, 3, 2), header.getInvoiceDate());
		assertEquals(41.0, header.getSubtotal());
		assertEquals(7.79, header.getTaxAmount());
		assertEquals(48.79, header.getTotalAmount());
		assertEquals(RecordOrigin.HEAD_OFFICE, header.getOrigin());
		assertEquals(SupplyInvoiceWriter.VENDOR_CODE, header.getVendor().getVendorCode());
		assertEquals("Happyness", header.getVendor().getName());
		assertEquals("ERP invoice " + NUMBER + " - Happyness", header.getNotes());
		PurchaseInvoiceLine line = purchases.linesOf(header).get(0);
		assertEquals(Integer.valueOf(6), line.getQuantity(), "as invoiced, never the quantity received");
		assertEquals(6.0, line.getUnitPrice());
		assertEquals(36.0, line.getSubtotal());
		assertEquals(Integer.valueOf(19), line.getVatPercent());
		assertEquals(6.84, line.getTaxAmount());
		assertEquals(42.84, line.getLineTotalIncludingVat());
		assertEquals(b001.getId(), line.getItem().getId());

		ReceivedDeliveryDTO view = reception.get(invoice().getId()).get();
		assertEquals("ERP_INVOICE", view.getDocumentKind());
		assertEquals("Happyness", view.getSellerName());
		assertEquals(48.79, view.getTotalInclVat());
		assertTrue(view.isDifference());
		ReceivedDeliveryDTO.Line row = view.getLines().get(0);
		assertEquals("ITEM", row.getLineType());
		assertEquals(11.9, row.getSellingPrice(), "the store's own price 10 with VAT 19 %");
		assertEquals(30.0, row.getLineTotal(), "5 received x 6");
		assertEquals(36.0, row.getLineAmount());
		assertEquals(Integer.valueOf(-1), row.getDifference());
	}

	/** A paid line as BC gives it: unit price before discount, the line discount, amount = quantity x net. */
	static ErpInvoiceCopyDTO.Line paidLine(int lineNo, String code, int quantity, double unitPrice, double discount,
			double amount) {
		ErpInvoiceCopyDTO.Line line = itemLine(lineNo, code, quantity, amount);
		line.setUnitPrice(unitPrice);
		line.setLineDiscountPercent(discount);
		return line;
	}

	@Test
	@DisplayName("A 40 % line of BC (FVV21000000218): last direct cost = the unit price, net cost and cost price = the net unit cost")
	void grossAndNetFromDiscount() {
		reception.saveReceived(erpCopy(NUMBER, paidLine(20000, "B001", 1, 58.82353, 40, 35.294)));
		reception.receive(invoice().getId(), null, "responsible");
		Item b001 = item("B001");
		assertEquals(58.82353, b001.getLastDirectCost(), "before the line discount, before VAT");
		assertEquals(35.294, b001.getLastDirectNetCost(), "58.82353 x (1 - 40 %), as BC rounded the line amount");
		assertEquals(35.294, b001.getCostPrice());
		assertEquals(10.0, b001.getUnitPrice(), "the selling price is never touched");
	}

	@Test
	@DisplayName("The same item on two paid lines: the highest line number wins; a line at amount 0 changes no cost")
	void highestLineWinsAndTester() {
		reception.saveReceived(erpCopy(NUMBER, paidLine(20000, "B001", 4, 12, 25, 36), paidLine(10000, "B001", 6, 10, 40, 36),
				itemLine(30000, "B002", 1, 0)));
		reception.receive(invoice().getId(), null, "responsible");

		assertEquals(10, stock.stockOf("B001"));
		assertEquals(12.0, item("B001").getLastDirectCost(), "line 20000");
		assertEquals(9.0, item("B001").getLastDirectNetCost(), "36 / 4");
		assertEquals(9.0, item("B001").getCostPrice());
		assertEquals(1, stock.stockOf("B002"));
		assertEquals(9.0, item("B002").getCostPrice(), "a tester at 0 never changes the cost");
		assertEquals(9.0, item("B002").getLastDirectCost());
		assertNull(invoice().getLines().get(2).getCostApplied());
		assertEquals(1, db.costUpdates, "one write per item, once");

		reception.applyWaitingStock(); // later cycles change nothing
		assertEquals(1, db.costUpdates);
	}

	@Test
	@DisplayName("Received 0: no stock, the costs are written all the same (the prices are per unit); the selling price untouched")
	void receivedZeroStillCosts() {
		reception.saveReceived(erpCopy(NUMBER, paidLine(10000, "B001", 6, 10, 40, 36)));
		reception.receive(invoice().getId(), quantities(10000, 0), "responsible");
		assertEquals(0, stock.stockOf("B001"));
		assertEquals(10.0, item("B001").getLastDirectCost());
		assertEquals(6.0, item("B001").getCostPrice());
		assertEquals(10.0, item("B001").getUnitPrice(), "never the selling price");
		assertTrue(invoice().getLines().get(0).getCostApplied());
	}

	@Test
	@DisplayName("An item not here received at 0: it waits; when the catalogue brings it, its three costs go in, no stock")
	void missingItemReceivedZero() {
		reception.saveReceived(erpCopy(NUMBER, paidLine(10000, "B009", 3, 20, 10, 54)));
		reception.receive(invoice().getId(), quantities(10000, 0), "responsible");
		assertFalse(invoice().getLines().get(0).getStockApplied(), "waits for its item for its costs");

		Item b009 = db.item("B009", 30.0, null);
		b009.setOrigin(RecordOrigin.HEAD_OFFICE);
		assertTrue(reception.applyWaitingStock().isEmpty());
		assertEquals(0, stock.stockOf("B009"));
		assertEquals(20.0, b009.getLastDirectCost());
		assertEquals(18.0, b009.getLastDirectNetCost(), "54 / 3 = 20 x (1 - 10 %)");
		assertEquals(18.0, b009.getCostPrice());
		assertEquals(30.0, b009.getUnitPrice());
	}

	@Test
	@DisplayName("An item not here: received, its stock and cost wait; when the catalogue brings it, both go in and its purchase line gets it")
	void missingItemLater() {
		reception.saveReceived(erpCopy(NUMBER, itemLine(10000, "B001", 1, 6), itemLine(20000, "B009", 2, 8)));
		reception.receive(invoice().getId(), null, "responsible");

		assertFalse(invoice().getLines().get(1).getStockApplied());
		assertNull(invoice().getLines().get(1).getCostApplied());
		PurchaseInvoiceLine waiting = purchases.linesOf(purchases.byNumber(NUMBER)).get(1);
		assertNull(waiting.getItem());
		assertEquals("B009 Name of B009", waiting.getLineDescription());
		assertEquals(Collections.singletonMap("ERPINV:" + NUMBER, Arrays.asList("B009")), reception.applyWaitingStock());

		Item b009 = db.item("B009", 5.0, null);
		b009.setOrigin(RecordOrigin.HEAD_OFFICE);
		b009.setCostPrice(1.0);
		Map<String, List<String>> still = reception.applyWaitingStock();
		assertTrue(still.isEmpty(), still.toString());
		assertEquals(2, stock.stockOf("B009"));
		assertEquals(4.0, b009.getCostPrice(), "8 / 2");
		assertEquals(10.0, b009.getLastDirectCost(), "the unit price of the line");
		assertTrue(invoice().getLines().get(1).getCostApplied());
		assertEquals(b009.getId(), waiting.getItem().getId(), "the purchase line attached");
	}

	@Test
	@DisplayName("An OTHER line: no quantity, no stock, no cost; on the purchase invoice; a quantity for it refused; never confirmed up")
	void otherLine() {
		reception.saveReceived(erpCopy(NUMBER, itemLine(10000, "B001", 2, 12), otherLine(20000, 5)));
		assertThrows(IllegalArgumentException.class,
				() -> reception.receive(invoice().getId(), quantities(20000, 1), "responsible"));
		reception.receive(invoice().getId(), quantities(10000, 2), "responsible");

		ReceivedDeliveryLine transport = invoice().getLines().get(1);
		assertNull(transport.getQuantityReceived());
		assertTrue(transport.getStockApplied());
		assertNull(transport.getCostApplied());
		PurchaseInvoiceLine line = purchases.linesOf(purchases.byNumber(NUMBER)).get(1);
		assertNull(line.getItem());
		assertEquals("Transport", line.getLineDescription());
		assertEquals(Integer.valueOf(1), line.getQuantity());
		assertEquals(5.0, line.getUnitPrice());
		assertEquals(5.0, line.getSubtotal());
		assertTrue(reception.applyWaitingStock().isEmpty(), "an OTHER line never waits");

		DeliveryConfirmationDTO up = SupplyPushService.confirmationOf(invoice());
		assertEquals(1, up.getLines().size(), "the item lines only");
		assertEquals("B001", up.getLines().get(0).getItemCode());
		ReceivedDeliveryDTO view = reception.get(invoice().getId()).get();
		assertEquals("OTHER", view.getLines().get(1).getLineType());
		assertEquals(0, view.getMissingItems());
	}

	@Test
	@DisplayName("A plain BL: received as before, no cost, no purchase invoice, no invoice number")
	void plainBlUnchanged() {
		DeliveryCopyDTO bl = new DeliveryCopyDTO();
		bl.setNumber("BL-000001");
		bl.setDocumentDate("2026-10-09");
		DeliveryCopyDTO.DeliveryLineCopyDTO line = new DeliveryCopyDTO.DeliveryLineCopyDTO();
		line.setLineNo(1);
		line.setItemCode("B001");
		line.setItemName("Item B001");
		line.setQuantitySent(4);
		bl.getLines().add(line);
		reception.saveReceived(bl);
		ReceivedDelivery saved = received.byNumber("BL-000001");
		assertEquals(ReceivedDocumentKind.BL, saved.kindOrBl());
		assertEquals(ReceivedLineType.ITEM, saved.getLines().get(0).getLineType());

		reception.receive(saved.getId(), null, "responsible");
		assertEquals(4, stock.stockOf("B001"));
		assertEquals(9.0, item("B001").getCostPrice());
		assertEquals(0, db.costUpdates);
		assertTrue(purchases.headers.isEmpty());
		assertNull(saved.getInvoiceNumber());
		ReceivedDeliveryDTO view = reception.get(saved.getId()).get();
		assertEquals("BL", view.getDocumentKind());
		assertNull(view.getLines().get(0).getSellingPrice());
		assertNull(view.getLines().get(0).getLineTotal());

		// A row of before step (c): no kind, no line type (the columns added later)
		saved.setDocumentKind(null);
		saved.getLines().get(0).setLineType(null);
		assertEquals("BL", reception.get(saved.getId()).get().getDocumentKind());
		assertEquals("ITEM", reception.get(saved.getId()).get().getLines().get(0).getLineType());
	}
}
