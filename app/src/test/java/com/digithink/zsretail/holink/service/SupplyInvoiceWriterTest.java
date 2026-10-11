package com.digithink.zsretail.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.holink.enumeration.ReceivedDocumentKind;
import com.digithink.zsretail.holink.enumeration.ReceivedLineType;
import com.digithink.zsretail.holink.model.ReceivedDelivery;
import com.digithink.zsretail.holink.model.ReceivedDeliveryLine;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.PurchaseInvoiceHeader;
import com.digithink.zsretail.model.PurchaseInvoiceLine;
import com.digithink.zsretail.model.Vendor;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.digithink.zsretail.support.InMemoryPurchaseInvoices;
import com.digithink.zsretail.support.InMemoryReceivedDeliveries;
import com.digithink.zsretail.utils.Quantities;

/**
 * Invoices from the ERP, step (c): SupplyInvoiceWriter.saveFromErpInvoice writes the purchase invoice of a received ERP
 * invoice and no cost, once by number, on the vendor HEAD_OFFICE (kept as it is when it exists); attachItems gives a line
 * without item the item that arrived.
 */
class SupplyInvoiceWriterTest {

	private InMemoryCatalogue db;
	private InMemoryPurchaseInvoices purchases;
	private SupplyInvoiceWriter writer;
	private Item b001;

	@BeforeEach
	void setUp() {
		db = new InMemoryCatalogue(100_000);
		purchases = new InMemoryPurchaseInvoices(950_000);
		writer = new SupplyInvoiceWriter(purchases.headerRepository(), purchases.lineRepository(),
				purchases.vendorRepository(), db.itemRepository(), new InMemoryReceivedDeliveries(900_000).repository());
		b001 = db.item("B001", 10.0, null);
		b001.setOrigin(RecordOrigin.HEAD_OFFICE);
		b001.setCostPrice(9.0);
	}

	private static ReceivedDeliveryLine line(int lineNo, ReceivedLineType type, String code, Long itemId, int quantity,
			double amount, Double unitCost) {
		ReceivedDeliveryLine line = new ReceivedDeliveryLine();
		line.setLineNo(lineNo);
		line.setLineType(type);
		line.setItemCode(code);
		line.setItemName(type == ReceivedLineType.OTHER ? "Transport" : "Name of " + code);
		line.setItemId(itemId);
		line.setQuantitySent(Quantities.of(quantity));
		line.setQuantityReceived(Quantities.of(quantity - 1));
		line.setLineAmount(amount);
		line.setUnitCost(unitCost);
		return line;
	}

	private ReceivedDelivery invoice(String number) {
		ReceivedDelivery invoice = new ReceivedDelivery();
		invoice.setId(1L);
		invoice.setNumber(number);
		invoice.setDocumentKind(ReceivedDocumentKind.ERP_INVOICE);
		invoice.setDocumentDate(LocalDate.of(2026, 3, 2));
		invoice.setSellerName("Happyness");
		invoice.setTotalExclVat(41.0);
		invoice.setTotalVat(7.79);
		invoice.setTotalInclVat(48.79);
		invoice.getLines().addAll(Arrays.asList(line(10000, ReceivedLineType.ITEM, "B001", b001.getId(), 6, 36, 6.0),
				line(20000, ReceivedLineType.ITEM, "B009", null, 2, 8, 4.0),
				line(30000, ReceivedLineType.OTHER, "", null, 0, 5, null)));
		return invoice;
	}

	@Test
	@DisplayName("ERP path: once by number, totals copied, lines as invoiced, no cost written; a new vendor takes the seller's name")
	void savedWithoutCost() {
		SupplyInvoiceWriter.Outcome first = writer.saveFromErpInvoice(invoice("FVV26000000101"));
		assertTrue(first.isWritten());
		assertEquals(Arrays.asList("B009"), first.getMissingItems());
		assertEquals(9.0, b001.getCostPrice(), "no cost from the writer");
		assertEquals(0, db.costUpdates);
		assertEquals(0, db.itemSaves);

		PurchaseInvoiceHeader header = purchases.byNumber("FVV26000000101");
		assertEquals(LocalDate.of(2026, 3, 2), header.getInvoiceDate());
		assertEquals(48.79, header.getTotalAmount());
		assertEquals("Happyness", header.getVendor().getName());
		assertEquals("Happyness", header.getSnapshotVendorName());
		assertEquals(3, purchases.linesOf(header).size());
		PurchaseInvoiceLine b009 = purchases.linesOf(header).get(1);
		assertNull(b009.getItem());
		assertEquals("B009 Name of B009", b009.getLineDescription());
		assertEquals(BigDecimal.valueOf(2), b009.getQuantity());
		assertNull(b009.getVatPercent(), "the VAT of an item not here is not known");
		assertEquals(8.0, b009.getTotalAmount());

		assertFalse(writer.saveFromErpInvoice(invoice("FVV26000000101")).isWritten(), "never twice");
		assertEquals(1, purchases.headers.size());
	}

	@Test
	@DisplayName("An existing vendor HEAD_OFFICE keeps its name; the invoice names the seller")
	void vendorKept() {
		Vendor existing = new Vendor();
		existing.setVendorCode(SupplyInvoiceWriter.VENDOR_CODE);
		existing.setName("Head office of before");
		existing.setPhone("71 000 000");
		purchases.vendorRepository().save(existing);
		writer.saveFromErpInvoice(invoice("FVV26000000101"));
		PurchaseInvoiceHeader header = purchases.byNumber("FVV26000000101");
		assertEquals("Head office of before", header.getVendor().getName());
		assertEquals("Happyness", header.getSnapshotVendorName());
		assertEquals("ERP invoice FVV26000000101 - Happyness", header.getNotes());
		assertEquals(1, purchases.vendors.size());
	}

	@Test
	@DisplayName("attachItems: a line without item gets the item its received line has now; nothing without a purchase invoice")
	void attachItems() {
		ReceivedDelivery invoice = invoice("FVV26000000101");
		assertEquals(0, writer.attachItems(invoice), "no purchase invoice yet");
		writer.saveFromErpInvoice(invoice);
		Item b009 = db.item("B009", 5.0, null);
		invoice.getLines().get(1).setItemId(b009.getId());
		assertEquals(1, writer.attachItems(invoice));
		assertEquals(b009.getId(), purchases.linesOf(purchases.byNumber("FVV26000000101")).get(1).getItem().getId());
		assertEquals(0, writer.attachItems(invoice), "once");
	}
}
