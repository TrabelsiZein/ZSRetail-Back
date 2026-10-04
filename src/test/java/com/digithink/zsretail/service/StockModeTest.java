package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.StockMovement;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.DataOwner;
import com.digithink.zsretail.model.enumeration.StockMovementType;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.digithink.zsretail.support.InMemoryStock;
import com.digithink.zsretail.support.TestModes;

/**
 * Head office plan, task 9.1f: StockService and StockMovementService ask isSupplyFromErp instead of isStandalone (the
 * till calls them at every sale and return). For every real profile file (application.properties under each
 * application-*.properties, found by pattern), the real services over an in-memory item and stock_movement table: a
 * sale, two returns, a purchase, an adjustment and the two BL movements are no-ops when the supply is the ERP's, and
 * are applied (stock and one movement each) when the stock is kept here: a store or a head office without an ERP, a
 * store fed by its head office.
 */
class StockModeTest {

	private static final List<StockMovementType> EVERY_MOVEMENT = Arrays.asList(StockMovementType.SALE,
			StockMovementType.CUSTOMER_RETURN_SIMPLE, StockMovementType.CUSTOMER_RETURN_VOUCHER,
			StockMovementType.PURCHASE_RECEPTION, StockMovementType.ADJUSTMENT_OUT, StockMovementType.DELIVERY_OUT,
			StockMovementType.DELIVERY_IN);

	/** Every real profile, merged over application.properties like Spring does, by file name. */
	private static Map<String, MockEnvironment> profiles() throws Exception {
		PathMatchingResourcePatternResolver files = new PathMatchingResourcePatternResolver();
		Properties base = load(files.getResource("classpath:application.properties"));
		Map<String, MockEnvironment> profiles = new TreeMap<>();
		for (Resource file : files.getResources("classpath*:application-*.properties")) {
			Properties merged = new Properties();
			merged.putAll(base);
			merged.putAll(load(file));
			MockEnvironment env = new MockEnvironment();
			merged.stringPropertyNames().forEach(key -> env.setProperty(key, merged.getProperty(key)));
			profiles.put(file.getFilename(), env);
		}
		return profiles;
	}

	private static Properties load(Resource resource) throws Exception {
		Properties properties = new Properties();
		try (InputStream in = resource.getInputStream()) {
			properties.load(in);
		}
		return properties;
	}

	/** Runs every stock change once on item B001 (stock 100) and gives the stock and the movement types written. */
	private static String run(ApplicationModeService mode, boolean[] deliveryAnswer) {
		InMemoryCatalogue catalogue = new InMemoryCatalogue(1);
		Item item = catalogue.item("B001", 10.0, null);
		item.setStockQuantity(100);
		InMemoryStock memory = new InMemoryStock(catalogue);
		memory.mode = mode;
		StockService stock = memory.stockService();
		StockMovementService movements = memory.stockMovementService();
		Long id = item.getId();

		stock.decrementForSale(id, 2);
		movements.recordSale(id, 2, 10.0, 19, 11.9, 1L, null);
		stock.incrementForReturn(id, 1);
		movements.recordSimpleReturn(id, 1, 10.0, 19, 11.9, 2L, null);
		movements.recordVoucherReturn(id, 1, 10.0, 19, 11.9, 3L, null);
		stock.incrementForPurchase(id, 5);
		movements.recordPurchase(id, 5, 6.0, 19, 7.14, 4L);
		stock.adjustStock(id, -3, "COUNT");
		movements.recordAdjustment(id, -3, "count");
		deliveryAnswer[0] = stock.decrementForDelivery(id, 4);
		movements.recordDeliveryOut(id, 4, 5L, "BL-000001");
		stock.incrementForDelivery(id, 6);
		movements.recordDeliveryIn(id, 6, 6L, "BL-000002");

		return memory.stockOf("B001") + " "
				+ memory.movements.stream().map(StockMovement::getMovementType).collect(Collectors.toList());
	}

	@Test
	@DisplayName("Every profile file: no stock change and no movement when the supply is the ERP's, every change and one movement each otherwise")
	void everyProfileFile() throws Exception {
		Map<String, MockEnvironment> profiles = profiles();
		int erp = 0;
		int local = 0;
		int fedByHeadOffice = 0;
		for (Map.Entry<String, MockEnvironment> profile : profiles.entrySet()) {
			ApplicationModeService mode = TestModes.of(profile.getValue());
			DataOwner supply = mode.ownerOf(DataDomain.SUPPLY);
			boolean[] deliveryAnswer = { false };
			String result = run(mode, deliveryAnswer);
			assertTrue(deliveryAnswer[0], profile.getKey() + ": a BL decrement answers true (no-op or done)");
			if (supply == DataOwner.ERP) {
				erp++;
				assertEquals("100 []", result, profile.getKey());
			} else {
				if (supply == DataOwner.HEAD_OFFICE) {
					fedByHeadOffice++;
				} else {
					local++;
				}
				// 100 - 2 sale + 1 return + 5 purchase - 3 adjustment - 4 BL out + 6 BL in = 103 (the voucher return
				// writes its movement only, its stock goes through incrementForReturn in ReturnHeaderService)
				assertEquals("103 " + EVERY_MOVEMENT, result, profile.getKey());
			}
		}
		assertTrue(erp >= 4, "ERP profiles read (dynamics-*, headoffice-dynamics-dev): " + profiles.keySet());
		assertTrue(local >= 4, "profiles without an ERP read: " + profiles.keySet());
		assertTrue(fedByHeadOffice >= 2, "stores fed by the head office read (store-b-dev, network-store)");
	}

	@Test
	@DisplayName("The answer follows isSupplyFromErp, the same as the old isStandalone check, on the three kinds of installation")
	void threeKinds() {
		boolean[] answer = { false };
		assertEquals("100 []", run(TestModes.erp(), answer));
		assertEquals("103 " + EVERY_MOVEMENT, run(TestModes.standalone(), answer));
		ApplicationModeService fed = TestModes.of(new MockEnvironment().withProperty("application.standalone", "true")
				.withProperty("headoffice.url", "http://localhost:888/zsretail/api").withProperty("headoffice.api-key", "k")
				.withProperty("ownership.catalogue", "HEAD_OFFICE").withProperty("ownership.supply", "HEAD_OFFICE"));
		assertEquals("103 " + EVERY_MOVEMENT, run(fed, answer));
	}
}
