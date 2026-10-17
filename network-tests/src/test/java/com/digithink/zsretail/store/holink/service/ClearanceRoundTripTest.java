package com.digithink.zsretail.store.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.core.link.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.support.InMemoryLoyalty;
import com.digithink.zsretail.headoffice.model.HoStockPoint;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.service.CopiesDownFeed;
import com.digithink.zsretail.headoffice.service.HoCatalogueService;
import com.digithink.zsretail.headoffice.service.InMemoryDownTables;
import com.digithink.zsretail.headoffice.support.InMemoryCatalogue;
import com.digithink.zsretail.store.holink.model.LinkRight;
import com.digithink.zsretail.store.support.InMemoryStoreLink;

/**
 * Item variants (ZEN), clearance (2026-10-17): a head office whose catalogue comes from the ERP and a store of its point
 * de stock, over the real copies down (head office feed, store handler and writer). A row in clearance at 7 reaches the
 * store as the item's price, with clearance true and the public price; the end of the clearance, a later row change,
 * brings the public price back by the same channel. A row without clearance (EMTOP, Happyness) is received as before.
 */
class ClearanceRoundTripTest {

	private InMemoryCatalogue ho;
	private InMemoryCatalogue store;
	private CopiesDownFeed feed;
	private HoCatalogueService hoCatalogue;
	private CatalogueDownHandler handler;
	private Store franchise;
	private HoStockPointItem tieRow;
	private HoStockPointItem bikeRow;
	private String cursor = "";

	@BeforeEach
	void setUp() {
		ho = new InMemoryCatalogue(1);
		store = new InMemoryCatalogue(1000);
		InMemoryStoreLink link = new InMemoryStoreLink(new InMemoryLoyalty(5000));
		InMemoryDownTables down = new InMemoryDownTables();
		CopiesDownFeed[] feedRef = new CopiesDownFeed[1];
		hoCatalogue = new HoCatalogueService(ho.familyRepository(), ho.subFamilyRepository(), ho.itemRepository(),
				ho.barcodeRepository(), ho.compositionRepository(), ho.priceLineRepository(), () -> feedRef[0],
				TransactionOperations.withoutTransaction(), ho.stockPointItemRepository(), ho.storeRepository());
		feed = down.feed(Collections.singletonList(hoCatalogue));
		feedRef[0] = feed;
		CatalogueRights rights = new CatalogueRights(InMemoryLoyalty.proxy(
				com.digithink.zsretail.store.holink.repository.LinkRightRepository.class, (method, args) -> {
					if ("findByCode".equals(method)) {
						return java.util.Optional.<LinkRight>empty();
					}
					return InMemoryLoyalty.UNHANDLED;
				}));
		CatalogueCopyWriter writer = new CatalogueCopyWriter(store.familyRepository(), store.subFamilyRepository(),
				store.itemRepository(), store.barcodeRepository(), store.compositionRepository(),
				store.categoryRepository(), store.sectionRepository());
		handler = new CatalogueDownHandler(writer, link.downRecordLog(), rights, link.exchangeLog(),
				TransactionOperations.withoutTransaction());

		ItemSubFamily sub = ho.subFamily("SF1", ho.family("F1"));
		Item tie = ho.item("14ZDU05~001", 19.5, sub);
		tie.setErpExternalId(tie.getItemCode());
		Item bike = ho.item("1000", 100.0, sub);
		bike.setErpExternalId(bike.getItemCode());
		HoStockPoint point = new HoStockPoint();
		point.setId(77L);
		point.setCode("FRANCHISE");
		point.setName("Franchise");
		point.setActive(true);
		ho.stockPoints.put(point.getId(), point);
		tieRow = ho.stockPointItem(point, tie, 19.5);
		tieRow.setClearance(true);
		tieRow.setClearancePrice(new BigDecimal("7.000"));
		bikeRow = ho.stockPointItem(point, bike, 100.0); // a Happyness-shaped row: clearance null
		franchise = ho.store("FR1");
		franchise.setStockPointId(point.getId());
	}

	/** One cycle as CopiesDownPuller runs it. */
	private void sync() {
		feed.initialise();
		CopiesDownAnswerDTO page = feed.pull(franchise, "CATALOGUE", cursor, 500);
		handler.apply(page.getRecords(), page.getRemoved());
		cursor = page.getCursor();
		handler.retry();
	}

	private Item atStore(String code) {
		return store.itemRepository().findByItemCode(code).get();
	}

	@Test
	@DisplayName("The store receives the tie at its clearance price 7 with clearance true and the public price 19.5;"
			+ " the bike (no clearance) at its price, as before")
	void clearanceReachesTheStore() {
		sync();

		Item tie = atStore("14ZDU05~001");
		assertEquals(7.0, tie.getUnitPrice());
		assertTrue(tie.getClearance());
		assertEquals(19.5, tie.getPublicPrice());
		Item bike = atStore("1000");
		assertEquals(100.0, bike.getUnitPrice());
		assertNull(bike.getClearance());
		assertNull(bike.getPublicPrice());
	}

	@Test
	@DisplayName("The clearance ends at the ERP: the row change sends the item again, the store sells 19.5")
	void clearanceEnds() {
		sync();
		tieRow.setClearance(false);
		hoCatalogue.stockPointRowsChanged(77L, Collections.singletonList(tieRow.getItemId()), Collections.emptyList());
		sync();

		Item tie = atStore("14ZDU05~001");
		assertEquals(19.5, tie.getUnitPrice());
		assertNull(tie.getClearance());
		assertNull(tie.getPublicPrice());
		assertEquals(100.0, atStore("1000").getUnitPrice(), "the bike was not sent again and is unchanged");
	}
}
