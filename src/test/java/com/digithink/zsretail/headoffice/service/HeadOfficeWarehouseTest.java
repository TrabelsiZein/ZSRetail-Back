package com.digithink.zsretail.headoffice.service;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;
import static com.digithink.zsretail.support.InMemoryStock.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.controller.PurchaseHeaderAPI;
import com.digithink.zsretail.controller.PurchaseInvoiceAPI;
import com.digithink.zsretail.controller.VendorAPI;
import com.digithink.zsretail.dto.ProcessPurchaseRequestDTO;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.PurchaseHeader;
import com.digithink.zsretail.model.PurchaseLine;
import com.digithink.zsretail.model.Vendor;
import com.digithink.zsretail.model.enumeration.StockMovementType;
import com.digithink.zsretail.repository.PurchaseHeaderRepository;
import com.digithink.zsretail.repository.PurchaseLineRepository;
import com.digithink.zsretail.repository.VendorRepository;
import com.digithink.zsretail.service.CatalogueHeadOfficeHooks;
import com.digithink.zsretail.service.ItemService;
import com.digithink.zsretail.service.PurchaseHeaderService;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.digithink.zsretail.support.InMemoryStock;
import com.digithink.zsretail.support.TestModes;

/**
 * Head office plan, task 7A.1: the head office as a warehouse. On a head office without an ERP (standalone) a purchase
 * raises its stock with a PURCHASE_RECEPTION movement and the last costs, exactly as on a store, and a stock
 * adjustment writes its movement; neither records a CATALOGUE change (the item copy carries no stock and no cost), so
 * no store pulls the item again. A head office with an ERP keeps today's refusals (403). Real PurchaseHeaderService,
 * StockService, StockMovementService, ItemService, HoCatalogueService and CopiesDownFeed over in-memory tables.
 */
class HeadOfficeWarehouseTest {

	private InMemoryCatalogue ho;
	private InMemoryStock stock;
	private InMemoryDownTables down;
	private HoCatalogueService hoCatalogue;
	private final List<PurchaseHeader> purchases = new ArrayList<>();
	private final List<PurchaseLine> purchaseLines = new ArrayList<>();
	private Vendor vendor;
	private Item b001;

	@BeforeEach
	void setUp() {
		ho = new InMemoryCatalogue(1);
		stock = new InMemoryStock(ho);
		down = new InMemoryDownTables();
		CopiesDownFeed[] feed = new CopiesDownFeed[1];
		hoCatalogue = new HoCatalogueService(ho.familyRepository(), ho.subFamilyRepository(), stock.itemRepository(),
				ho.barcodeRepository(), ho.compositionRepository(), ho.priceLineRepository(), () -> feed[0],
				TransactionOperations.withoutTransaction());
		feed[0] = down.feed(Collections.singletonList(hoCatalogue));
		b001 = ho.item("B001", 10.0, null);
		ho.store("B");
		vendor = new Vendor();
		vendor.setId(500L);
		vendor.setVendorCode("V1");
		feed[0].initialise(); // the startup backfill: B001 has its change row
	}

	private PurchaseHeaderService purchaseService() {
		PurchaseHeaderService service = new PurchaseHeaderService();
		set(service, PurchaseHeaderService.class, "purchaseHeaderRepository",
				proxy(PurchaseHeaderRepository.class, (method, args) -> {
					switch (method) {
						case "count":
							return (long) purchases.size();
						case "save":
							PurchaseHeader header = (PurchaseHeader) args[0];
							if (header.getId() == null) {
								header.setId(ho.nextId());
								purchases.add(header);
							}
							return header;
						default:
							return UNHANDLED;
					}
				}));
		set(service, PurchaseHeaderService.class, "purchaseLineRepository",
				proxy(PurchaseLineRepository.class, (method, args) -> {
					if ("save".equals(method)) {
						purchaseLines.add((PurchaseLine) args[0]);
						return args[0];
					}
					return UNHANDLED;
				}));
		set(service, PurchaseHeaderService.class, "vendorRepository", proxy(VendorRepository.class,
				(method, args) -> "findById".equals(method) ? Optional.of(vendor) : UNHANDLED));
		set(service, PurchaseHeaderService.class, "itemRepository", stock.itemRepository());
		set(service, PurchaseHeaderService.class, "stockService", stock.stockService());
		set(service, PurchaseHeaderService.class, "stockMovementService", stock.stockMovementService());
		return service;
	}

	private static ProcessPurchaseRequestDTO purchase(Long itemId, int quantity, double unitPrice) {
		ProcessPurchaseRequestDTO.PurchaseLineDTO line = new ProcessPurchaseRequestDTO.PurchaseLineDTO();
		line.setItemId(itemId);
		line.setQuantity(java.math.BigDecimal.valueOf(quantity));
		line.setUnitPrice(unitPrice);
		line.setVatPercent(19);
		ProcessPurchaseRequestDTO request = new ProcessPurchaseRequestDTO();
		request.setVendorId(500L);
		request.setLines(Collections.singletonList(line));
		return request;
	}

	private int catalogueChanges() {
		return (int) down.changes.stream().filter(c -> c.getDomain() == com.digithink.zsretail.model.enumeration.DataDomain.CATALOGUE)
				.mapToLong(c -> c.getChangeVersion()).max().orElse(0);
	}

	@Test
	@DisplayName("2.2.1: a purchase with a decimal quantity is refused naming the item, nothing written (1.5 was read as 1 until 2.2.0); 100.0 is 100")
	void decimalPurchaseRefused() {
		int before = stock.stockOf("B001");
		ProcessPurchaseRequestDTO decimal = purchase(b001.getId(), 1, 6.0);
		decimal.getLines().get(0).setQuantity(new BigDecimal("1.5"));
		IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> purchaseService().processPurchase(decimal, null));
		assertEquals("Item B001 (" + b001.getName() + "): the quantity 1.5 has decimals, and decimal quantities are not"
				+ " supported in purchases yet.", refused.getMessage());
		assertEquals(0, purchases.size());
		assertEquals(0, purchaseLines.size());
		assertEquals(before, stock.stockOf("B001"));

		ProcessPurchaseRequestDTO whole = purchase(b001.getId(), 1, 6.0);
		whole.getLines().get(0).setQuantity(new BigDecimal("100.0"));
		purchaseService().processPurchase(whole, null);
		assertEquals(before + 100, stock.stockOf("B001"));
		assertEquals(Integer.valueOf(100), purchaseLines.get(0).getQuantity());
	}

	@Test
	@DisplayName("A purchase at the head office: stock +100, one PURCHASE_RECEPTION movement, last costs; no CATALOGUE change")
	void purchaseRaisesStockWithoutCatalogueChange() {
		int before = catalogueChanges();

		purchaseService().processPurchase(purchase(b001.getId(), 100, 6.0), null);

		assertEquals(100, stock.stockOf("B001"));
		assertEquals(1, stock.movements(StockMovementType.PURCHASE_RECEPTION).size());
		assertEquals(BigDecimal.valueOf(100), stock.movements.get(0).getQuantity());
		assertEquals(6.0, b001.getLastDirectCost());
		assertEquals(1, purchases.size());
		assertEquals(1, purchaseLines.size());
		assertEquals(before, catalogueChanges(), "no store is told to pull the item again");
	}

	@Test
	@DisplayName("A stock adjustment at the head office: quantity and its movement; no CATALOGUE change")
	void adjustmentWithoutCatalogueChange() {
		ItemService items = new ItemService();
		set(items, ItemService.class, "itemRepository", stock.itemRepository());
		set(items, ItemService.class, "stockService", stock.stockService());
		set(items, ItemService.class, "stockMovementService", stock.stockMovementService());
		set(items, ItemService.class, "catalogueHooks", new StaticListableBeanFactory(
				Collections.singletonMap("hooks", hoCatalogue)).getBeanProvider(CatalogueHeadOfficeHooks.class));
		int before = catalogueChanges();

		items.adjustStock(b001.getId(), 12, "COUNT");
		items.adjustStock(b001.getId(), -2, "DAMAGE");

		assertEquals(10, stock.stockOf("B001"));
		assertEquals(1, stock.movements(StockMovementType.ADJUSTMENT_IN).size());
		assertEquals(1, stock.movements(StockMovementType.ADJUSTMENT_OUT).size());
		assertEquals(before, catalogueChanges());
	}

	@Test
	@DisplayName("A head office with an ERP (standalone false): purchases, purchase history, vendors and purchase invoices refused as today")
	void headOfficeWithErpRefused() throws Exception {
		ApplicationModeService erp = TestModes.of(TestModes.erpOwners(new org.springframework.mock.env.MockEnvironment()
				.withProperty("node.type", "HEAD_OFFICE")));

		PurchaseHeaderAPI purchasesApi = new PurchaseHeaderAPI();
		set(purchasesApi, PurchaseHeaderAPI.class, "applicationModeService", erp);
		assertEquals(403, purchasesApi.processPurchase(purchase(b001.getId(), 1, 1.0)).getStatusCodeValue());
		assertEquals(403, purchasesApi.getHistory(0, 10, null, null, null, null, null).getStatusCodeValue());

		VendorAPI vendors = new VendorAPI();
		set(vendors, VendorAPI.class, "applicationModeService", erp);
		assertEquals(403, vendors.create(new Vendor()).getStatusCodeValue());

		PurchaseInvoiceAPI invoices = new PurchaseInvoiceAPI();
		set(invoices, PurchaseInvoiceAPI.class, "applicationModeService", erp);
		ResponseStatusException refused = assertThrows(ResponseStatusException.class,
				() -> invoices.createPurchaseInvoice(new PurchaseInvoiceAPI.CreatePurchaseInvoiceRequest()));
		assertEquals(403, refused.getStatus().value());

		assertTrue(stock.movements.isEmpty());
		assertEquals(0, stock.stockOf("B001"));
	}
}
