package com.digithink.zsretail.store.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.core.link.dto.DeliveryCopyDTO;
import com.digithink.zsretail.core.link.dto.SupplyInvoiceCopyDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;
import com.digithink.zsretail.core.model.enumeration.StockMovementType;
import com.digithink.zsretail.core.support.InMemoryItems;
import com.digithink.zsretail.core.support.InMemoryLoyalty;
import com.digithink.zsretail.core.support.InMemoryPurchaseInvoices;
import com.digithink.zsretail.core.support.InMemoryStock;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.store.holink.dto.ReceptionInputDTO;
import com.digithink.zsretail.store.holink.model.ReceivedDelivery;
import com.digithink.zsretail.store.holink.model.ReceivedDeliveryLine;
import com.digithink.zsretail.store.support.InMemoryReceivedDeliveries;
import com.digithink.zsretail.store.support.InMemoryStoreLink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * CMP, a store supplied by its head office's BLs and invoices: the BL brings the stock without a cost, its invoice the
 * cost. Through the copies down (the real SupplyDownHandler, DeliveryReceptionService and SupplyInvoiceWriter over
 * in-memory tables): an invoice of a BL already received applies the weighted average cost at once; an invoice of a BL
 * still to receive stamps its lines, the reception applies it; a BL whose record comes after its invoice takes the costs
 * from the purchase invoice; part of the BL sold before its invoice: the CMP becomes the invoice cost (the known
 * compromise). The last costs are the invoice's, written by the invoice only; the store's own items are never touched.
 */
class SupplyInvoiceCostTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 10, 0);
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String BL = "BL-2026-000101";
	private static final String INVOICE = "FHO-2026-000101";

	private InMemoryItems db;
	private InMemoryStock stock;
	private InMemoryReceivedDeliveries received;
	private InMemoryPurchaseInvoices purchases;
	private DeliveryReceptionService reception;
	private SupplyDownHandler handler;

	@BeforeEach
	void setUp() {
		db = new InMemoryItems(100_000);
		stock = new InMemoryStock(db);
		received = new InMemoryReceivedDeliveries(900_000);
		purchases = new InMemoryPurchaseInvoices(950_000);
		Item b001 = db.item("B001", 10.0, null);
		b001.setOrigin(RecordOrigin.HEAD_OFFICE);
		b001.setCostPrice(9.0);
		b001.setStockQuantity(BigDecimal.valueOf(10));
		SupplyInvoiceWriter writer = new SupplyInvoiceWriter(purchases.headerRepository(), purchases.lineRepository(),
				purchases.vendorRepository(), stock.itemRepository(), received.repository());
		reception = new DeliveryReceptionService(received.repository(), stock.itemRepository(), stock.stockService(),
				stock.stockMovementService(), TransactionOperations.withoutTransaction(), () -> NOW, writer);
		handler = new SupplyDownHandler(reception, new InMemoryStoreLink(new InMemoryLoyalty(500_000)).downRecordLog(),
				TransactionOperations.withoutTransaction(), writer);
	}

	private void down(Object copy) {
		handler.apply(Collections.singletonList((JsonNode) JSON.valueToTree(copy)), Collections.emptyList());
	}

	private static DeliveryCopyDTO bl(String code, int quantity) {
		DeliveryCopyDTO copy = new DeliveryCopyDTO();
		copy.setNumber(BL);
		copy.setDocumentDate("2026-11-01");
		DeliveryCopyDTO.DeliveryLineCopyDTO line = new DeliveryCopyDTO.DeliveryLineCopyDTO();
		line.setLineNo(1);
		line.setItemCode(code);
		line.setItemName("Item " + code);
		line.setQuantitySent(Quantities.of(quantity));
		copy.getLines().add(line);
		return copy;
	}

	/** An invoice of the BL: one line of the item, quantity at unit price (no discount: line total = quantity x price). */
	private static SupplyInvoiceCopyDTO invoice(String code, int quantity, double unitPrice) {
		SupplyInvoiceCopyDTO copy = new SupplyInvoiceCopyDTO();
		copy.setInvoiceNumber(INVOICE);
		copy.setInvoiceDate("2026-11-02");
		copy.setSellerName("Head office");
		copy.getDeliveryNumbers().add(BL);
		SupplyInvoiceCopyDTO.Line line = new SupplyInvoiceCopyDTO.Line();
		line.setLineNo(1);
		line.setDeliveryNumber(BL);
		line.setItemCode(code);
		line.setItemName("Item " + code);
		line.setQuantity(quantity);
		line.setUnitPrice(unitPrice);
		line.setVatPercent(19);
		line.setLineTotal(quantity * unitPrice);
		line.setVatAmount(quantity * unitPrice * 0.19);
		line.setLineTotalIncludingVat(quantity * unitPrice * 1.19);
		copy.getLines().add(line);
		double total = quantity * unitPrice;
		copy.setSubtotal(total);
		copy.setTaxAmount(total * 0.19);
		copy.setTotalAmount(total * 1.19);
		return copy;
	}

	private Item b001() {
		return db.itemByCode("B001").get();
	}

	private ReceivedDelivery delivery() {
		return received.byNumber(BL);
	}

	private void receive() {
		reception.receive(delivery().getId(), null, "responsible");
	}

	@Test
	@DisplayName("Invoice after the BL is received: stock 10 at 9, 4 received, invoiced at 5: (10 x 9 + 4 x 5) / 14 = 7.857; last costs the invoice's; once")
	void invoiceAfterReception() {
		down(bl("B001", 4));
		receive();
		assertEquals(14, stock.stockOf("B001"));
		assertEquals(9.0, b001().getCostPrice(), "a BL without its invoice: no cost");
		assertNull(stock.movements(StockMovementType.DELIVERY_IN).get(0).getUnitPriceHt());

		down(invoice("B001", 4, 5.0));
		assertEquals(7.857, b001().getCostPrice());
		assertEquals(5.0, b001().getLastDirectCost());
		assertEquals(5.0, b001().getLastDirectNetCost());
		assertEquals(INVOICE, delivery().getInvoiceNumber());
		ReceivedDeliveryLine line = delivery().getLines().get(0);
		assertTrue(line.getCostApplied());
		assertEquals(5.0, line.getUnitCost());
		assertEquals(20.0, line.getLineAmount());
		assertNull(stock.movements(StockMovementType.DELIVERY_IN).get(0).getUnitPriceHt(), "never filled later");
		assertEquals(0, db.itemSaves, "no save of the item");

		down(invoice("B001", 4, 5.0)); // the same record again
		reception.applyWaitingStock();
		assertEquals(1, db.cmpUpdates, "written once");
		assertEquals(7.857, b001().getCostPrice());
	}

	@Test
	@DisplayName("Invoice before the BL is received: its lines stamped, nothing yet; the reception applies the CMP with the stock and the movement's cost")
	void invoiceBeforeReception() {
		down(bl("B001", 4));
		down(invoice("B001", 4, 5.0));
		assertEquals(10, stock.stockOf("B001"));
		assertEquals(9.0, b001().getCostPrice(), "the stock is not in yet");
		assertEquals(5.0, b001().getLastDirectCost(), "the last costs are the invoice's, written with it");
		assertEquals(5.0, delivery().getLines().get(0).getUnitCost());
		assertEquals(0, db.cmpUpdates);

		receive();
		assertEquals(14, stock.stockOf("B001"));
		assertEquals(7.857, b001().getCostPrice());
		assertEquals(5.0, stock.movements(StockMovementType.DELIVERY_IN).get(0).getUnitPriceHt());
		assertEquals(1, db.costUpdates, "the last costs once, by the invoice; never by the reception of a BL");
	}

	@Test
	@DisplayName("Received 3 of 4 before the invoice of 4: the 3 received enter at the invoice cost (10 x 9 + 3 x 5) / 13")
	void partlyReceived() {
		down(bl("B001", 4));
		down(invoice("B001", 4, 5.0));
		ReceptionInputDTO input = new ReceptionInputDTO();
		input.getLines().add(new ReceptionInputDTO.Line(1, Quantities.of(3)));
		reception.receive(delivery().getId(), input, "responsible");
		assertEquals(13, stock.stockOf("B001"));
		assertEquals(8.077, b001().getCostPrice(), "105 / 13 = 8.0769");
	}

	@Test
	@DisplayName("Part of the BL sold before its invoice: the stock now minus the BL is below 0, the CMP becomes the invoice cost (the known compromise)")
	void soldBeforeInvoice() {
		b001().setStockQuantity(BigDecimal.ZERO);
		down(bl("B001", 4));
		receive();
		stock.stockService().decrementForSale(b001().getId(), BigDecimal.valueOf(3));
		assertEquals(1, stock.stockOf("B001"));

		down(invoice("B001", 4, 5.0));
		assertEquals(5.0, b001().getCostPrice());
	}

	@Test
	@DisplayName("A BL whose record comes after its invoice: it takes the costs and the invoice number from the purchase invoice; its reception applies the CMP")
	void blAfterItsInvoice() {
		down(invoice("B001", 4, 5.0));
		assertEquals(1, purchases.headers.size(), "the invoice is written without its BL");

		down(bl("B001", 4));
		ReceivedDelivery delivery = delivery();
		assertEquals(INVOICE, delivery.getInvoiceNumber());
		assertEquals(5.0, delivery.getLines().get(0).getUnitCost());
		assertEquals(20.0, delivery.getLines().get(0).getLineAmount());
		assertEquals(9.0, b001().getCostPrice(), "not received yet");

		receive();
		assertEquals(7.857, b001().getCostPrice());
	}

	@Test
	@DisplayName("An item invoiced at 0 (a free unit): no cost, the CMP stays")
	void invoicedAtZero() {
		down(bl("B001", 4));
		receive();
		down(invoice("B001", 4, 0.0));
		assertEquals(9.0, b001().getCostPrice());
		assertEquals(0, db.cmpUpdates);
	}

	@Test
	@DisplayName("The store's own item of the same code: the BL never resolves it, the invoice never touches its costs")
	void ownItemUntouched() {
		Item own = db.item("B002", 3.0, null); // made here, no origin
		own.setCostPrice(2.0);
		own.setLastDirectCost(2.0);
		down(bl("B002", 4));
		receive();
		down(invoice("B002", 4, 5.0));
		assertEquals(2.0, own.getCostPrice());
		assertEquals(2.0, own.getLastDirectCost());
		assertEquals(0, db.cmpUpdates);
		assertEquals(0, db.costUpdates);
	}
}
