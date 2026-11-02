package com.digithink.zsretail.core.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.dto.ProcessPurchaseRequestDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemCostHistory;
import com.digithink.zsretail.core.model.PurchaseHeader;
import com.digithink.zsretail.core.model.PurchaseLine;
import com.digithink.zsretail.core.model.StockMovement;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.Vendor;
import com.digithink.zsretail.core.model.enumeration.CostChangeSource;
import com.digithink.zsretail.core.model.enumeration.StockMovementType;
import com.digithink.zsretail.core.repository.PurchaseHeaderRepository;
import com.digithink.zsretail.core.repository.PurchaseLineRepository;
import com.digithink.zsretail.core.repository.VendorRepository;
import com.digithink.zsretail.core.support.InMemoryItems;
import com.digithink.zsretail.core.support.InMemoryStock;

/**
 * CMP, a local purchase (an installation without the ERP's supply): each line puts its quantity in the stock, then the
 * weighted average cost at its net unit price (after the line discount, before VAT); the last costs by a bulk update,
 * never a save of the item (a save put back the stock it was loaded with: the same item on two lines lost the first
 * line's quantity, EntryCostQueryTest shows the mechanism); PURCHASE_RECEPTION carries the net cost. Real
 * PurchaseHeaderService, StockService and StockMovementService over in-memory tables.
 */
class PurchaseHeaderServiceCostTest {

	private InMemoryItems db;
	private InMemoryStock stock;
	private final List<PurchaseLine> lines = new ArrayList<>();
	private PurchaseHeaderService service;
	private Item b001;
	private UserAccount user;

	@BeforeEach
	void setUp() {
		db = new InMemoryItems(100_000);
		stock = new InMemoryStock(db);
		b001 = db.item("B001", 10.0, null);
		b001.setCostPrice(9.0);
		b001.setStockQuantity(BigDecimal.valueOf(10));
		Vendor vendor = new Vendor();
		vendor.setId(1L);
		vendor.setVendorCode("V1");
		PurchaseHeaderRepository headers = proxy(PurchaseHeaderRepository.class, (method, args) -> {
			switch (method) {
				case "count":
					return 0L;
				case "save":
					((PurchaseHeader) args[0]).setId(500L);
					return args[0];
				default:
					return UNHANDLED;
			}
		});
		PurchaseLineRepository lineRepository = proxy(PurchaseLineRepository.class, (method, args) -> {
			if ("save".equals(method)) {
				lines.add((PurchaseLine) args[0]);
				return args[0];
			}
			return UNHANDLED;
		});
		VendorRepository vendors = proxy(VendorRepository.class,
				(method, args) -> "findById".equals(method) ? Optional.of(vendor) : UNHANDLED);
		service = new PurchaseHeaderService();
		InMemoryStock.set(service, PurchaseHeaderService.class, "purchaseHeaderRepository", headers);
		InMemoryStock.set(service, PurchaseHeaderService.class, "purchaseLineRepository", lineRepository);
		InMemoryStock.set(service, PurchaseHeaderService.class, "vendorRepository", vendors);
		InMemoryStock.set(service, PurchaseHeaderService.class, "itemRepository", stock.itemRepository());
		InMemoryStock.set(service, PurchaseHeaderService.class, "stockService", stock.stockService());
		InMemoryStock.set(service, PurchaseHeaderService.class, "stockMovementService", stock.stockMovementService());
		user = new UserAccount();
		user.setUsername("buyer");
	}

	private static ProcessPurchaseRequestDTO.PurchaseLineDTO line(Long itemId, int quantity, double unitPrice,
			Double discount) {
		ProcessPurchaseRequestDTO.PurchaseLineDTO line = new ProcessPurchaseRequestDTO.PurchaseLineDTO();
		line.setItemId(itemId);
		line.setQuantity(BigDecimal.valueOf(quantity));
		line.setUnitPrice(unitPrice);
		line.setDiscountPercent(discount);
		line.setVatPercent(19);
		return line;
	}

	private void purchase(ProcessPurchaseRequestDTO.PurchaseLineDTO... purchaseLines) {
		ProcessPurchaseRequestDTO request = new ProcessPurchaseRequestDTO();
		request.setVendorId(1L);
		request.setLines(Arrays.asList(purchaseLines));
		service.processPurchase(request, user);
	}

	@Test
	@DisplayName("One line, 2 at 8 less 25 %: stock 12, CMP (10 x 9 + 2 x 6) / 12 = 8.5; last costs 8 and 6; movement at the net 6")
	void oneLine() {
		purchase(line(b001.getId(), 2, 8.0, 25.0));
		assertEquals(12, stock.stockOf("B001"));
		assertEquals(8.5, b001.getCostPrice());
		assertEquals(8.0, b001.getLastDirectCost(), "before the discount");
		assertEquals(6.0, b001.getLastDirectNetCost());
		assertEquals("buyer", b001.getUpdatedBy());
		StockMovement movement = stock.movements(StockMovementType.PURCHASE_RECEPTION).get(0);
		assertEquals(6.0, movement.getUnitPriceHt(), "the net unit cost");
		// 3.1.2: the change in the cost history, the purchase as its document
		assertEquals(1, stock.costHistory.size());
		ItemCostHistory row = stock.costHistory.get(0);
		assertEquals(CostChangeSource.LOCAL_PURCHASE, row.getSource());
		assertEquals(9.0, row.getOldCost());
		assertEquals(8.5, row.getNewCost());
		assertEquals(0, java.math.BigDecimal.valueOf(2).compareTo(row.getQtyIn()));
		assertEquals(6.0, row.getUnitCostIn());
		assertEquals("PURCHASE", row.getDocumentType());
		assertNotNull(row.getDocumentNumber());
		assertEquals("buyer", row.getChangedBy());
	}

	@Test
	@DisplayName("The same item on two lines at other prices: both quantities in the stock (15), two entries one after the other: 8.5 then 7.7; the item never saved")
	void sameItemOnTwoLines() {
		purchase(line(b001.getId(), 2, 6.0, null), line(b001.getId(), 3, 5.0, 10.0));
		assertEquals(15, stock.stockOf("B001"), "the first line's quantity kept");
		assertEquals(7.7, b001.getCostPrice(), "(12 x 8.5 + 3 x 4.5) / 15");
		assertEquals(5.0, b001.getLastDirectCost(), "the last line");
		assertEquals(4.5, b001.getLastDirectNetCost());
		assertEquals(0, db.itemSaves, "no save of the item");
		assertEquals(2, db.cmpUpdates);
		assertEquals(Arrays.asList(6.0, 4.5), stock.movements(StockMovementType.PURCHASE_RECEPTION).stream()
				.map(StockMovement::getUnitPriceHt).collect(Collectors.toList()));
		assertEquals(2, lines.size());
	}

	@Test
	@DisplayName("An item without a CMP yet takes the net cost of its first purchase")
	void firstPurchase() {
		b001.setCostPrice(null);
		purchase(line(b001.getId(), 4, 5.0, null));
		assertEquals(5.0, b001.getCostPrice());
		assertTrue(stock.movements(StockMovementType.PURCHASE_RECEPTION).size() == 1);
	}
}
