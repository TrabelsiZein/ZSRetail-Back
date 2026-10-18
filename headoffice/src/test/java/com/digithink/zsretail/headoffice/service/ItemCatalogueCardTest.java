package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.persistence.Entity;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.digithink.zsretail.core.dto.NavItemVariantsDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.digithink.zsretail.core.repository.ItemBarcodeRepository;
import com.digithink.zsretail.core.repository.ItemCategoryRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSectionRepository;
import com.digithink.zsretail.core.support.TestModes;
import com.digithink.zsretail.headoffice.controller.HoItemCatalogueAPI;
import com.digithink.zsretail.headoffice.dto.ItemCatalogueCardDTO;
import com.digithink.zsretail.headoffice.model.HoStockPoint;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.headoffice.repository.HoItemCatalogueRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointItemRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointRepository;

/**
 * Fiches items (3.1.0), step 2: the item card (HoItemCatalogueService.card, HoItemCatalogueAPI.card) through the real
 * repositories, Hibernate over an in-memory H2 database (no Spring context; the Spring Data repositories made over each
 * call's session, with the applications' naming strategies).
 * <p>
 * NAV item 70061 (catégorie VET): S (size 1; barcodes 3700000000024, 3700000000017 primary, 3700000000099 inactive), M
 * (size 2; barcode 3700000000031), L (size 3, inactive). Points FRANCHISE and MAG1: S in both (in clearance at 9 in
 * FRANCHISE), M in MAG1 inactive, L in none. PACK-1: a pack without NAV number.
 */
class ItemCatalogueCardTest {

	private static SessionFactory sessions;
	private static long s;
	private static long m;
	private static long l;
	private static long pack;
	private static long franchise;
	private static long mag1;

	@BeforeAll
	static void database() {
		Configuration configuration = new Configuration();
		configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
		scan.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
		for (String base : new String[] { "com.digithink.zsretail.core.model", "com.digithink.zsretail.headoffice.model" }) {
			for (BeanDefinition entity : scan.findCandidateComponents(base)) {
				try {
					configuration.addAnnotatedClass(Class.forName(entity.getBeanClassName()));
				} catch (ClassNotFoundException e) {
					throw new IllegalStateException(e);
				}
			}
		}
		sessions = configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:itemcataloguecard;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			ItemFamily hom = new ItemFamily();
			hom.setCode("HOM");
			hom.setName("Homme");
			session.persist(hom);
			ItemCategory vet = new ItemCategory();
			vet.setCode("VET");
			vet.setName("Vêtements");
			session.persist(vet);
			Item itemS = variant(session, "70061~S", 1, true, hom);
			Item itemM = variant(session, "70061~M", 2, true, hom);
			Item itemL = variant(session, "70061~L", 3, false, hom);
			itemS.setItemDiscGroup("DG-S");
			itemM.setItemDiscGroup("DG-M");
			itemM.setShowInPos(false);
			s = itemS.getId();
			m = itemM.getId();
			l = itemL.getId();
			barcode(session, itemS, "3700000000024", false, true);
			barcode(session, itemS, "3700000000017", true, true);
			barcode(session, itemS, "3700000000099", false, false);
			barcode(session, itemM, "3700000000031", true, true);
			Item packItem = new Item();
			packItem.setItemCode("PACK-1");
			packItem.setName("Pack cadeau");
			packItem.setType(ItemType.PACKAGE);
			packItem.setUnitPrice(0.0);
			session.persist(packItem);
			pack = packItem.getId();
			franchise = point(session, "FRANCHISE", 1);
			mag1 = point(session, "MAG1", 2);
			row(session, franchise, s, 19.5, true, true, "9");
			row(session, mag1, s, 19.5, true, null, null);
			row(session, mag1, m, 21.0, false, false, null);
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static Item variant(Session session, String code, int sizeOrder, boolean active, ItemFamily family) {
		Item item = new Item();
		item.setItemCode(code);
		item.setNavItemNo("70061");
		item.setName("CHEMISE");
		item.setVariantCode(code.substring(6));
		item.setSizeCode(code.substring(6));
		item.setSizeSortOrder(sizeOrder);
		item.setColorCode("BLK");
		item.setSeasonCode("E26");
		item.setDivisionCode("PAP");
		item.setCategoryCode("VET");
		item.setItemFamily(family);
		item.setActive(active);
		item.setDefaultVAT(19);
		item.setUnitPrice(16.39);
		item.setStockQuantity(new BigDecimal(sizeOrder));
		session.persist(item);
		return item;
	}

	private static void barcode(Session session, Item item, String value, boolean primary, boolean active) {
		ItemBarcode barcode = new ItemBarcode();
		barcode.setItem(item);
		barcode.setBarcode(value);
		barcode.setIsPrimary(primary);
		barcode.setActive(active);
		session.persist(barcode);
	}

	private static long point(Session session, String code, int order) {
		HoStockPoint point = new HoStockPoint();
		point.setCode(code);
		point.setName("Point " + code);
		point.setSortOrder(order);
		point.setActive(true);
		session.persist(point);
		return point.getId();
	}

	private static void row(Session session, long pointId, long itemId, double price, boolean active,
			Boolean clearance, String clearancePrice) {
		HoStockPointItem row = new HoStockPointItem();
		row.setStockPointId(pointId);
		row.setItemId(itemId);
		row.setName("row");
		row.setUnitPrice(price);
		row.setActive(active);
		row.setClearance(clearance);
		row.setClearancePrice(clearancePrice == null ? null : new BigDecimal(clearancePrice));
		session.persist(row);
	}

	/** The work with a service (and its API) over one session's repositories; noPoints: no point de stock exists. */
	private static <T> T withService(boolean noPoints, Function<HoItemCatalogueService, T> work) {
		try (Session session = sessions.openSession()) {
			JpaRepositoryFactory repositories = new JpaRepositoryFactory(session);
			HoStockPointItemRepository rows = repositories.getRepository(HoStockPointItemRepository.class);
			HoStockPointRepository points = repositories.getRepository(HoStockPointRepository.class);
			HoStockPointRepository pointsSeen = points;
			if (noPoints) {
				pointsSeen = mock(HoStockPointRepository.class);
				when(pointsSeen.count()).thenReturn(0L);
			}
			HoItemCatalogueService service = new HoItemCatalogueService(
					repositories.getRepository(HoItemCatalogueRepository.class), rows, pointsSeen,
					repositories.getRepository(ItemCategoryRepository.class),
					repositories.getRepository(ItemSectionRepository.class), TestModes.standalone(),
					repositories.getRepository(ItemRepository.class),
					repositories.getRepository(ItemBarcodeRepository.class), new HoNavItemStockPoints(rows, points));
			return work.apply(service);
		}
	}

	private static ItemCatalogueCardDTO card(String navItemNo, Long itemId, String search) {
		return withService(false, service -> service.card(navItemNo, itemId, search).orElse(null));
	}

	private static ResponseEntity<?> api(String navItemNo, Long itemId) {
		return withService(false, service -> new HoItemCatalogueAPI(service).card(navItemNo, itemId, null));
	}

	private static List<String> highlighted(ItemCatalogueCardDTO card) {
		return card.getVariants().stream().filter(ItemCatalogueCardDTO.Variant::isHighlighted)
				.map(ItemCatalogueCardDTO.Variant::getItemCode).collect(Collectors.toList());
	}

	@Test
	@DisplayName("NAV item: header, every variant active or not in size order, barcodes per variant, the points and their rows")
	void navItemCard() {
		ItemCatalogueCardDTO card = card(" 70061 ", null, null);
		ItemCatalogueCardDTO.Header header = card.getHeader();
		assertEquals("70061", header.getNavItemNo());
		assertNull(header.getItemId());
		assertEquals("70061", header.getCode());
		assertEquals("CHEMISE", header.getName());
		assertTrue(header.isActive());
		assertFalse(header.isSingle());
		assertEquals("Homme", header.getFamilyName());
		assertEquals("VET", header.getCategoryCode());
		assertEquals("Vêtements", header.getCategoryName());
		assertEquals(3, header.getVariantsCount());
		assertTrue(header.isWithStock());
		assertTrue(header.isWithStockPoints());

		assertEquals(Arrays.asList("70061~S", "70061~M", "70061~L"),
				card.getVariants().stream().map(ItemCatalogueCardDTO.Variant::getItemCode).collect(Collectors.toList()));
		ItemCatalogueCardDTO.Variant vs = card.getVariants().get(0);
		assertEquals(Long.valueOf(s), vs.getItemId());
		assertEquals("E26", vs.getSeasonCode());
		assertEquals("PAP", vs.getDivisionCode());
		assertEquals("PRODUCT", vs.getType());
		assertEquals(Boolean.FALSE, vs.getIsPackage());
		assertEquals(Integer.valueOf(19), vs.getDefaultVAT());
		assertEquals(0, BigDecimal.ONE.compareTo(vs.getStockQuantity()));
		assertEquals(Arrays.asList("3700000000017", "3700000000024"), vs.getBarcodes().stream()
				.map(ItemCatalogueCardDTO.Barcode::getBarcode).collect(Collectors.toList()), "active only, by value");
		assertEquals(Boolean.TRUE, vs.getBarcodes().get(0).getIsPrimary());
		assertEquals(Arrays.asList("3700000000031"), card.getVariants().get(1).getBarcodes().stream()
				.map(ItemCatalogueCardDTO.Barcode::getBarcode).collect(Collectors.toList()));
		assertFalse(card.getVariants().get(2).isActive(), "L inactive, still shown");
		assertTrue(card.getVariants().get(2).getBarcodes().isEmpty());

		assertEquals(Arrays.asList("FRANCHISE", "MAG1"),
				card.getStockPoints().stream().map(NavItemVariantsDTO.Point::getCode).collect(Collectors.toList()));
		NavItemVariantsDTO.PointPrice clearance = vs.getPrices().get(franchise);
		assertEquals(19.5, clearance.getUnitPrice());
		assertTrue(clearance.isClearanceApplies());
		assertEquals(0, new BigDecimal("9").compareTo(clearance.getClearancePrice()));
		assertTrue(clearance.isActive());
		assertFalse(vs.getPrices().get(mag1).isClearanceApplies());
		NavItemVariantsDTO.PointPrice inactive = card.getVariants().get(1).getPrices().get(mag1);
		assertFalse(inactive.isActive());
		assertEquals(21.0, inactive.getUnitPrice());
		assertEquals(1, card.getVariants().get(1).getPrices().size());
		assertTrue(card.getVariants().get(2).getPrices().isEmpty(), "L has no row");

		assertTrue(highlighted(card).isEmpty(), "no search: none");
		assertEquals(Long.valueOf(s), card.getDetails().getItemId(), "details: the first variant");
		assertEquals("DG-S", card.getDetails().getItemDiscGroup());
	}

	@Test
	@DisplayName("Highlight: an exact barcode or an exact item code (trimmed, any case); a contains match highlights nothing")
	void highlight() {
		ItemCatalogueCardDTO byBarcode = card("70061", null, " 3700000000024 ");
		assertEquals(Arrays.asList("70061~S"), highlighted(byBarcode));
		assertEquals(Long.valueOf(s), byBarcode.getDetails().getItemId());
		ItemCatalogueCardDTO byCode = card("70061", null, "70061~m");
		assertEquals(Arrays.asList("70061~M"), highlighted(byCode));
		assertEquals(Long.valueOf(m), byCode.getDetails().getItemId(), "details: the highlighted variant");
		assertEquals("DG-M", byCode.getDetails().getItemDiscGroup());
		assertFalse(byCode.getDetails().isShowInPos());
		assertTrue(highlighted(card("70061", null, "37000000000")).isEmpty(), "contains: none");
		assertTrue(highlighted(card("70061", null, "70061")).isEmpty(), "the NAV number: none");
		assertTrue(highlighted(card("70061", null, "3700000000099")).isEmpty(), "an inactive barcode: none");
	}

	@Test
	@DisplayName("Single item by its id; an item with a NAV number gives its NAV item's card")
	void byItemId() {
		ItemCatalogueCardDTO single = card(null, pack, null);
		assertTrue(single.getHeader().isSingle());
		assertEquals(Long.valueOf(pack), single.getHeader().getItemId());
		assertNull(single.getHeader().getNavItemNo());
		assertEquals("PACK-1", single.getHeader().getCode());
		assertEquals(1, single.getHeader().getVariantsCount());
		assertEquals(Boolean.TRUE, single.getVariants().get(0).getIsPackage());
		assertEquals("PACKAGE", single.getVariants().get(0).getType());
		assertTrue(single.getStockPoints().isEmpty(), "no row of PACK-1");
		assertNotNull(single.getDetails());
		ItemCatalogueCardDTO variant = card(null, l, null);
		assertEquals("70061", variant.getHeader().getNavItemNo());
		assertEquals(3, variant.getVariants().size());
	}

	@Test
	@DisplayName("API: 400 unless exactly one key, 404 for an unknown key, 200 with the card")
	void statuses() {
		assertEquals(HttpStatus.BAD_REQUEST, api("70061", pack).getStatusCode());
		assertEquals(HttpStatus.BAD_REQUEST, api(null, null).getStatusCode());
		assertEquals(HttpStatus.BAD_REQUEST, api("  ", null).getStatusCode(), "a blank NAV number counts as absent");
		assertEquals(HttpStatus.NOT_FOUND, api("UNKNOWN", null).getStatusCode());
		assertEquals(HttpStatus.NOT_FOUND, api(null, 999999L).getStatusCode());
		ResponseEntity<?> ok = api("70061", null);
		assertEquals(HttpStatus.OK, ok.getStatusCode());
		assertTrue(ok.getBody() instanceof ItemCatalogueCardDTO);
	}

	@Test
	@DisplayName("No point de stock: withStockPoints false, no point and no price")
	void withoutStockPoints() {
		ItemCatalogueCardDTO card = withService(true, service -> service.card("70061", null, null).orElse(null));
		assertFalse(card.getHeader().isWithStockPoints());
		assertTrue(card.getStockPoints().isEmpty());
		card.getVariants().forEach(variant -> assertTrue(variant.getPrices().isEmpty()));
	}
}
