package com.digithink.zsretail.store.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.link.dto.CatalogueBarcodeCopyDTO;
import com.digithink.zsretail.core.link.dto.CatalogueItemCopyDTO;
import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.PromotionRepository;
import com.digithink.zsretail.core.service.CustomerService;
import com.digithink.zsretail.core.service.ItemBarcodeService;
import com.digithink.zsretail.core.service.PricingService;
import com.digithink.zsretail.core.support.InMemoryItems;
import com.digithink.zsretail.store.dto.PriceCalculateResponseDTO;
import com.digithink.zsretail.store.service.PromotionCalculationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Item variants (ZEN), clearance (2026-10-17): the head office sends an item of a point in clearance at its clearance
 * price, with clearance true and the public price. The store applies the copy as any other; the scan (GET
 * /item-barcode/barcode/{barcode}) answers the item at that price with the two fields for the till's display, and the
 * cart's price (POST /price-calculate, the real PricingService) is the clearance price: no store code decides it. The
 * end of the clearance gives the public price back; a store's own price is kept, as for any head office price.
 */
class ClearanceAtTheTillTest {

	private static final String TIE = "14ZDU05~001";
	private static final String BARCODE = "9000002740592";

	private InMemoryItems store;
	private CatalogueCopyWriter writer;

	@BeforeEach
	void setUp() {
		store = new InMemoryItems(1);
		writer = new CatalogueCopyWriter(store.familyRepository(), store.subFamilyRepository(), store.itemRepository(),
				store.barcodeRepository(), store.compositionRepository(), store.categoryRepository(),
				store.sectionRepository());
	}

	/** The head office's copy of the tie: at 7 in clearance (public price 19.5), or at 19.5 without. */
	private static CatalogueItemCopyDTO tie(boolean clearance) {
		CatalogueItemCopyDTO copy = new CatalogueItemCopyDTO();
		copy.setItemCode(TIE);
		copy.setName("CRAVATE HM PETIT CARREAUX SATINE");
		copy.setDefaultVAT(19);
		copy.setActive(true);
		copy.setNavItemNo("14ZDU05");
		copy.setVariantCode("001");
		copy.setUnitPrice(clearance ? 7.0 : 19.5);
		copy.setClearance(clearance ? Boolean.TRUE : null);
		copy.setPublicPrice(clearance ? 19.5 : null);
		return copy;
	}

	private Item saved() {
		return store.itemRepository().findByItemCode(TIE).get();
	}

	private Item scan() throws Exception {
		ItemBarcodeService scan = new ItemBarcodeService();
		inject(scan, ItemBarcodeService.class, "itemBarcodeRepository", store.barcodeRepository());
		return scan.getItemByBarcode(BARCODE).get();
	}

	/** The cart's price of one piece, by the till's engine over the real PricingService, no promotion. */
	private PriceCalculateResponseDTO cart(Item item) throws Exception {
		PromotionCalculationService engine = new PromotionCalculationService();
		inject(engine, PromotionCalculationService.class, "itemRepository", (ItemRepository) Proxy.newProxyInstance(
				ItemRepository.class.getClassLoader(), new Class<?>[] { ItemRepository.class }, (proxy, method, args) -> {
					if ("findById".equals(method.getName())) {
						return Optional.of(item);
					}
					throw new UnsupportedOperationException(method.getName());
				}));
		inject(engine, PromotionCalculationService.class, "promotionRepository", (PromotionRepository) Proxy
				.newProxyInstance(PromotionRepository.class.getClassLoader(), new Class<?>[] { PromotionRepository.class },
						(proxy, method, args) -> {
							if (List.class.isAssignableFrom(method.getReturnType())) {
								return Collections.emptyList(); // no promotion
							}
							throw new UnsupportedOperationException(method.getName());
						}));
		inject(engine, PromotionCalculationService.class, "customerService", new CustomerService() {
			@Override
			public Customer getDefaultCustomer() {
				return new Customer();
			}
		});
		inject(engine, PromotionCalculationService.class, "pricingService", new PricingService());
		return engine.calculateItemPrice(item.getId(), BigDecimal.ONE, null, Collections.emptyList());
	}

	private void received(CatalogueItemCopyDTO copy) {
		writer.saveItem(copy);
		CatalogueBarcodeCopyDTO barcode = new CatalogueBarcodeCopyDTO();
		barcode.setBarcode(BARCODE);
		barcode.setItemCode(TIE);
		barcode.setActive(true);
		writer.saveBarcode(barcode);
	}

	@Test
	@DisplayName("Scan: the item at the clearance price, with clearance true and its public price for the till")
	void scanGivesTheClearancePrice() throws Exception {
		received(tie(true));

		Item scanned = scan();
		assertEquals(7.0, scanned.getUnitPrice(), "before VAT: the till adds the item's 19 % as for any price");
		assertTrue(scanned.getClearance());
		assertEquals(19.5, scanned.getPublicPrice());
		assertEquals(7.0, scanned.getHeadOfficePrice());
		JsonNode answer = new ObjectMapper().valueToTree(scanned);
		assertTrue(answer.get("clearance").asBoolean(), "in the scan's answer (read-only fields are written)");
		assertEquals(19.5, answer.get("publicPrice").asDouble());
	}

	@Test
	@DisplayName("Cart: the price engine sells the clearance price as the item's base price, no store rule involved")
	void cartSellsTheClearancePrice() throws Exception {
		received(tie(true));

		PriceCalculateResponseDTO price = cart(saved());
		assertEquals(7.0, price.getUnitPrice());
		assertEquals("BASE_PRICE", price.getSource(), "the item's own price, no sales price or promotion");
	}

	@Test
	@DisplayName("The clearance ends at the head office: the public price again, the two fields cleared")
	void clearanceEnds() throws Exception {
		received(tie(true));
		received(tie(false));

		Item scanned = scan();
		assertEquals(19.5, scanned.getUnitPrice());
		assertNull(scanned.getClearance());
		assertNull(scanned.getPublicPrice());
		assertEquals(19.5, cart(saved()).getUnitPrice());
	}

	@Test
	@DisplayName("A Happyness item (no clearance in its copy): its price and nothing else, as before")
	void happynessItem() throws Exception {
		received(tie(false));

		Item scanned = scan();
		assertEquals(19.5, scanned.getUnitPrice());
		assertNull(scanned.getClearance());
		assertNull(scanned.getPublicPrice());
	}

	@Test
	@DisplayName("A store's own price is kept when the clearance arrives; the head office price and the fields saved beside")
	void ownPriceKept() throws Exception {
		received(tie(false));
		Item item = saved();
		item.setUnitPrice(15.0);
		item.setOwnPrice(true);
		received(tie(true));

		Item scanned = scan();
		assertEquals(15.0, scanned.getUnitPrice(), "the store's own price");
		assertEquals(7.0, scanned.getHeadOfficePrice());
		assertTrue(scanned.getClearance(), "kept for when the price is given back");
		assertEquals(15.0, cart(saved()).getUnitPrice());
	}

	private static void inject(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
