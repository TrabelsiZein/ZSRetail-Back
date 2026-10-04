package com.digithink.zsretail.holink.service;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.holink.model.LinkRight;
import com.digithink.zsretail.holink.repository.LinkRightRepository;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.SalesPriceRepository;
import com.digithink.zsretail.support.InMemoryCatalogue;

/**
 * Head office plan, tasks 6.3, 6.5, 6.6: the rules of a store whose catalogue is the head office's. A head office record
 * is consult-only; the purchase right opens purchases, vendors and own items, and never head office items in a purchase;
 * the rights never received are off and the saved ones survive a restart; sales prices and catalogue imports are
 * refused; the status block. In-memory tables, no Spring context.
 */
class StoreCatalogueGuardTest {

	private InMemoryCatalogue store;
	private final Map<String, LinkRight> saved = new HashMap<>();
	private CatalogueRights rights;
	private StoreCatalogueGuard guard;

	private Item hoItem;
	private Item ownItem;
	private ItemFamily hoFamily;
	private ItemSubFamily hoSubFamily;
	private ItemBarcode hoBarcode;
	private ItemBarcode ownBarcode;

	@BeforeEach
	void setUp() {
		store = new InMemoryCatalogue(1);
		rights = new CatalogueRights(rightRepository());
		guard = newGuard(rights);
		hoFamily = store.family("F1");
		hoFamily.setOrigin(RecordOrigin.HEAD_OFFICE);
		hoSubFamily = store.subFamily("SF1", hoFamily);
		hoSubFamily.setOrigin(RecordOrigin.HEAD_OFFICE);
		hoItem = store.item("B001", 10.0, hoSubFamily);
		hoItem.setOrigin(RecordOrigin.HEAD_OFFICE);
		hoItem.setHeadOfficePrice(10.0);
		ownItem = store.item("OWN1", 5.0, null);
		hoBarcode = store.barcode("619", hoItem);
		hoBarcode.setOrigin(RecordOrigin.HEAD_OFFICE);
		ownBarcode = store.barcode("OWNBC", ownItem);
	}

	private StoreCatalogueGuard newGuard(CatalogueRights catalogueRights) {
		SalesPriceRepository salesPrices = proxy(SalesPriceRepository.class,
				(method, args) -> "countOnItemsOfOrigin".equals(method) && args[0] == RecordOrigin.HEAD_OFFICE ? 3L
						: UNHANDLED);
		CatalogueCopyWriter writer = new CatalogueCopyWriter(store.familyRepository(), store.subFamilyRepository(),
				store.itemRepository(), store.barcodeRepository(), store.compositionRepository());
		return new StoreCatalogueGuard(catalogueRights, store.itemRepository(), store.familyRepository(),
				store.subFamilyRepository(), store.barcodeRepository(), salesPrices, writer, new HeadOfficeLinkStatus());
	}

	private LinkRightRepository rightRepository() {
		return proxy(LinkRightRepository.class, (method, args) -> {
			switch (method) {
				case "findByCode":
					return Optional.ofNullable(saved.get(args[0]));
				case "save":
					LinkRight right = (LinkRight) args[0];
					saved.put(right.getCode(), right);
					return right;
				default:
					return UNHANDLED;
			}
		});
	}

	@Test
	@DisplayName("A head office record is consult-only (409); the store's own records are not")
	void headOfficeRecordsConsultOnly() {
		assertEquals(StoreCatalogueGuard.ITEM_MANAGED, guard.itemWrite(hoItem.getId()));
		assertEquals(StoreCatalogueGuard.FAMILY_MANAGED, guard.familyWrite(hoFamily.getId()));
		assertEquals(StoreCatalogueGuard.SUB_FAMILY_MANAGED, guard.subFamilyWrite(hoSubFamily.getId()));
		assertEquals(StoreCatalogueGuard.BARCODE_MANAGED, guard.barcodeWrite(hoBarcode.getId()));
		assertNull(guard.itemWrite(ownItem.getId()));
		assertNull(guard.barcodeWrite(ownBarcode.getId()));
		assertNull(guard.itemWrite(999L), "unknown: the API answers 404 as before");
		ItemBarcode onHoItem = new ItemBarcode();
		onHoItem.setItem(hoItem);
		onHoItem.setBarcode("NEW");
		rights.received(false, true, LocalDateTime.now());
		assertEquals(StoreCatalogueGuard.ITEM_MANAGED, guard.barcodeCreate(onHoItem), "a barcode on a head office item");
	}

	@Test
	@DisplayName("Purchase right never received: off; purchases, vendors and creating refused; own items stay editable")
	void purchaseRightOff() {
		assertEquals(StoreCatalogueGuard.CREATE_REFUSED, guard.create());
		assertEquals(StoreCatalogueGuard.PURCHASES_CLOSED, guard.purchase());
		assertEquals(StoreCatalogueGuard.PURCHASES_CLOSED, guard.purchaseLines(Collections.singletonList(ownItem.getId())));
		ItemBarcode onOwnItem = new ItemBarcode();
		onOwnItem.setItem(ownItem);
		onOwnItem.setBarcode("NEW");
		assertEquals(StoreCatalogueGuard.CREATE_REFUSED, guard.barcodeCreate(onOwnItem));
		assertEquals(StoreCatalogueGuard.PURCHASES_CLOSED, guard.dataImport("vendors"));
		assertNull(guard.itemWrite(ownItem.getId()), "an own item stays editable");
	}

	@Test
	@DisplayName("Purchase right on: open, but a purchase line with a head office item is refused; head office codes clear 409")
	void purchaseRightOn() {
		rights.received(false, true, LocalDateTime.now());
		assertNull(guard.create());
		assertNull(guard.purchase());
		assertNull(guard.purchaseLines(Collections.singletonList(ownItem.getId())));
		String refused = guard.purchaseLines(Arrays.asList(ownItem.getId(), hoItem.getId()));
		assertEquals("Head office items come only from the head office and cannot be purchased here: B001.", refused);
		assertNull(guard.dataImport("VENDORS"));
		ItemBarcode onOwnItem = new ItemBarcode();
		onOwnItem.setItem(ownItem);
		onOwnItem.setBarcode("NEW");
		assertNull(guard.barcodeCreate(onOwnItem));
		onOwnItem.setBarcode("619");
		assertEquals("The barcode 619 is used by a head office item.", guard.barcodeCreate(onOwnItem));
		assertEquals("The code B001 is used by a head office item.", guard.itemCodeTaken(" B001 "));
		assertNull(guard.itemCodeTaken("OWN2"));
		assertEquals("The code F1 is used by a head office family.", guard.familyCodeTaken("F1"));
		assertEquals("The code SF1 is used by a head office sub-family.", guard.subFamilyCodeTaken("SF1"));
	}

	@Test
	@DisplayName("Rights: saved when they change, kept after a restart, an answer without them keeps them")
	void rightsSaved() {
		assertFalse(rights.mayChangePrices());
		assertFalse(rights.canPurchase());
		assertNull(rights.saved(CatalogueRights.CAN_PURCHASE), "never received");
		rights.received(true, true, LocalDateTime.now());
		CatalogueRights afterRestart = new CatalogueRights(rightRepository());
		assertTrue(afterRestart.mayChangePrices());
		assertTrue(afterRestart.canPurchase());
		afterRestart.received(null, null, LocalDateTime.now());
		assertTrue(afterRestart.canPurchase(), "an older head office sends nothing: kept");
		afterRestart.received(true, false, LocalDateTime.now());
		assertFalse(afterRestart.canPurchase());
		assertFalse(new CatalogueRights(rightRepository()).canPurchase());
	}

	@Test
	@DisplayName("Own price: only on a head office item, with the right, a price 0 or more; giving back always allowed")
	void ownPrice() {
		assertThrows(IllegalStateException.class, () -> guard.setOwnPrice(hoItem.getId(), 12.0), "right never received");
		rights.received(true, false, LocalDateTime.now());
		assertThrows(IllegalArgumentException.class, () -> guard.setOwnPrice(ownItem.getId(), 12.0), "own item");
		assertThrows(IllegalArgumentException.class, () -> guard.setOwnPrice(hoItem.getId(), -1.0));
		assertThrows(IllegalArgumentException.class, () -> guard.setOwnPrice(hoItem.getId(), null));
		assertThrows(NoSuchElementException.class, () -> guard.setOwnPrice(999L, 1.0));
		Item priced = guard.setOwnPrice(hoItem.getId(), 12.0);
		assertEquals(12.0, priced.getUnitPrice());
		assertTrue(priced.getOwnPrice());
		assertEquals(10.0, priced.getHeadOfficePrice());
		rights.received(false, false, LocalDateTime.now());
		Item back = guard.giveBackPrice(hoItem.getId());
		assertEquals(10.0, back.getUnitPrice());
		assertNull(back.getOwnPrice());
		assertThrows(IllegalArgumentException.class, () -> guard.giveBackPrice(ownItem.getId()));
	}

	@Test
	@DisplayName("Catalogue imports and sales prices refused; other imports as before")
	void importsAndSalesPrices() {
		for (String type : Arrays.asList("FAMILIES", "subfamilies", "Items", "BARCODES", "SALES_PRICES")) {
			assertEquals(StoreCatalogueGuard.IMPORT_REFUSED, guard.dataImport(type), type);
		}
		assertNull(guard.dataImport("CUSTOMERS"));
		assertNull(guard.dataImport("LOCATIONS"));
		assertNull(guard.dataImport("SALES_DISCOUNTS"));
		assertEquals(StoreCatalogueGuard.SALES_PRICES_FROM_HEAD_OFFICE, guard.salesPriceWrite());
	}

	@Test
	@DisplayName("Status: rights as saved (null when never received), own prices, sales_price rows on head office items")
	void status() {
		Map<String, Object> status = guard.status();
		assertEquals(Arrays.asList("fromHeadOffice", "linkState", "mayChangePrices", "canPurchase", "ownPriceCount",
				"salesPriceRowsOnHeadOfficeItems"), new java.util.ArrayList<>(status.keySet()));
		assertEquals(true, status.get("fromHeadOffice"));
		assertNull(status.get("canPurchase"));
		assertEquals(0L, status.get("ownPriceCount"));
		assertEquals(3L, status.get("salesPriceRowsOnHeadOfficeItems"));
		assertEquals(3L, guard.warnAboutSalesPrices(), "the startup WARN gives the same count");
		rights.received(true, false, LocalDateTime.now());
		guard.setOwnPrice(hoItem.getId(), 11.0);
		status = guard.status();
		assertEquals(true, status.get("mayChangePrices"));
		assertEquals(false, status.get("canPurchase"));
		assertEquals(1L, status.get("ownPriceCount"));
	}
}
