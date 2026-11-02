package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.support.InMemoryItems;
import com.digithink.zsretail.core.support.InMemoryStock;
import com.digithink.zsretail.core.support.TestModes;

/**
 * CMP: StockService.applyEntryCost writes the weighted average cost wherever the installation keeps its own stock (a
 * store without the ERP's supply, standalone or supplied by its head office; a head office with stock) and is a no-op
 * elsewhere: where the ERP keeps the stock, on a head office with headoffice.stock.enabled=false. Also a no-op without a
 * cost or with a quantity below 0.
 */
class StockServiceEntryCostTest {

	private InMemoryItems db;
	private InMemoryStock stock;
	private Item item;

	@BeforeEach
	void setUp() {
		db = new InMemoryItems(100_000);
		stock = new InMemoryStock(db);
		item = db.item("B001", 10.0, null);
		item.setCostPrice(9.0);
		item.setStockQuantity(BigDecimal.valueOf(16)); // 6 just entered
	}

	private double entry(ApplicationModeService mode) {
		stock.mode = mode;
		stock.stockService().applyEntryCost(item.getId(), BigDecimal.valueOf(6), 6.0, "TEST");
		return item.getCostPrice();
	}

	private static MockEnvironment headOffice() {
		return new MockEnvironment().withProperty("node.type", "HEAD_OFFICE");
	}

	private static MockEnvironment suppliedStore() {
		return new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde")
				.withProperty("ownership.catalogue", "HEAD_OFFICE").withProperty("ownership.supply", "HEAD_OFFICE");
	}

	@Test
	@DisplayName("A standalone store: (10 x 9 + 6 x 6) / 16 = 7.875")
	void standaloneStore() {
		assertEquals(7.875, entry(TestModes.standalone()));
		assertEquals("TEST", item.getUpdatedBy());
	}

	@Test
	@DisplayName("A store supplied by its head office: the same")
	void suppliedStoreKeepsItsStock() {
		assertEquals(7.875, entry(TestModes.of(suppliedStore())));
	}

	@Test
	@DisplayName("A head office with its own stock: the same")
	void headOfficeWithStock() {
		assertEquals(7.875, entry(TestModes.of(headOffice())));
	}

	@Test
	@DisplayName("The ERP keeps the stock: no-op, the cost price untouched")
	void erpKeepsTheStock() {
		assertEquals(9.0, entry(TestModes.erp()));
		assertEquals(0, db.cmpUpdates);
	}

	@Test
	@DisplayName("A head office with headoffice.stock.enabled=false: no-op")
	void headOfficeWithoutStock() {
		assertEquals(9.0, entry(TestModes.of(headOffice().withProperty("headoffice.stock.enabled", "false"))));
		assertEquals(0, db.cmpUpdates);
	}

	@Test
	@DisplayName("No cost, a cost below 0, a quantity below 0, no item: no-op")
	void nothingToApply() {
		StockService service = stock.stockService();
		service.applyEntryCost(item.getId(), BigDecimal.valueOf(6), null, "TEST");
		service.applyEntryCost(item.getId(), BigDecimal.valueOf(6), -1.0, "TEST");
		service.applyEntryCost(item.getId(), BigDecimal.valueOf(-6), 6.0, "TEST");
		service.applyEntryCost(null, BigDecimal.valueOf(6), 6.0, "TEST");
		assertEquals(9.0, item.getCostPrice());
		assertEquals(0, db.cmpUpdates);
	}
}
