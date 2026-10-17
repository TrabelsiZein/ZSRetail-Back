package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.NavItemDTO;
import com.digithink.zsretail.core.dto.NavItemVariantsDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.repository.ItemCategoryRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSectionRepository;
import com.digithink.zsretail.core.support.TestModes;

/**
 * Item variants (ZEN), step 11a: the "NAV items" service over stubbed repositories. The stock is given only where the
 * installation keeps its own (ApplicationModeService.keepsOwnStock: a store without the ERP's supply, a head office with
 * headoffice.stock.enabled=true), the point de stock part only with the head office's bean, the menu rule is the exists
 * query. The queries themselves: NavItemQueryTest; the three ZEN deploy files: KeepsOwnStockTest (network-tests).
 */
class NavItemServiceTest {

	private final ItemRepository items = mock(ItemRepository.class);
	private final ItemCategoryRepository categories = mock(ItemCategoryRepository.class);
	private final ItemSectionRepository sections = mock(ItemSectionRepository.class);

	private static ApplicationModeService franchiseStore() {
		return TestModes.of(new MockEnvironment()); // every owner LOCAL: the store keeps its stock
	}

	private static ApplicationModeService erpStore() {
		return TestModes.erp(); // the supply is the ERP's (EMTOP): no stock kept here
	}

	private static ApplicationModeService headOfficeWithoutStock() {
		return TestModes.of(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE")
				.withProperty("headoffice.stock.enabled", "false"));
	}

	private static ApplicationModeService headOfficeWithStock() {
		return TestModes.of(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE"));
	}

	private NavItemService service(ApplicationModeService mode, NavItemStockPoints points) {
		return new NavItemService(items, categories, sections, mode, Optional.ofNullable(points));
	}

	private void oneLine() {
		Object[] row = { "70061", "CHEMISE", "Homme", "Chemises", "VET", "MANCHE", 4L, new BigDecimal("13") };
		Page<Object[]> page = new PageImpl<>(Collections.singletonList(row), PageRequest.of(0, 20), 1);
		when(items.findNavItems(any(), any(), any(Pageable.class))).thenReturn(page);
		ItemCategory vet = new ItemCategory();
		vet.setCode("VET");
		vet.setName("Vêtements");
		when(categories.findByCodeIn(anyCollection())).thenReturn(Collections.singletonList(vet));
		when(sections.findByCodeIn(anyCollection())).thenReturn(Collections.<ItemSection>emptyList());
	}

	private void twoVariants() {
		Item s = variant(1L, "70061~S", "2");
		Item m = variant(2L, "70061~M", null);
		m.setActive(false);
		when(items.findNavItemVariants(eq("70061"), any())).thenReturn(Arrays.asList(s, m));
	}

	private static Item variant(Long id, String code, String stock) {
		Item item = new Item();
		item.setId(id);
		item.setItemCode(code);
		item.setNavItemNo("70061");
		item.setSizeCode(code.substring(code.length() - 1));
		item.setUnitPrice(19.5);
		item.setStockQuantity(stock == null ? null : new BigDecimal(stock));
		return item;
	}

	/** Two points; variant 1 in both (clearance in P2), variant 2 in P1 only. */
	private static NavItemStockPoints twoPoints() {
		return new NavItemStockPoints() {
			@Override
			public Map<String, Long> activePointCounts(Collection<String> navItemNos) {
				return Collections.singletonMap("70061", 2L);
			}

			@Override
			public Map<Long, Map<Long, NavItemVariantsDTO.PointPrice>> prices(Collection<Long> itemIds) {
				Map<Long, Map<Long, NavItemVariantsDTO.PointPrice>> prices = new HashMap<>();
				Map<Long, NavItemVariantsDTO.PointPrice> one = new LinkedHashMap<>();
				one.put(11L, new NavItemVariantsDTO.PointPrice(19.5, true, false, null));
				one.put(12L, new NavItemVariantsDTO.PointPrice(19.5, true, true, new BigDecimal("7")));
				prices.put(1L, one);
				prices.put(2L, Collections.singletonMap(11L, new NavItemVariantsDTO.PointPrice(19.5, false, false, null)));
				return prices;
			}

			@Override
			public List<NavItemVariantsDTO.Point> points(Collection<Long> pointIds) {
				assertEquals(Arrays.asList(11L, 12L), new java.util.ArrayList<>(pointIds));
				return Arrays.asList(new NavItemVariantsDTO.Point(11L, "FRANCHISE", "Franchise"),
						new NavItemVariantsDTO.Point(12L, "MAG1", "Magasin 1"));
			}
		};
	}

	@Test
	@DisplayName("keepsOwnStock: a store without the ERP's supply and a head office with stock keep theirs; an ERP store and a head office without stock do not")
	void keepsOwnStock() {
		assertTrue(franchiseStore().keepsOwnStock());
		assertTrue(headOfficeWithStock().keepsOwnStock());
		assertFalse(erpStore().keepsOwnStock());
		assertFalse(headOfficeWithoutStock().keepsOwnStock());
	}

	@Test
	@DisplayName("Store that keeps its stock: lines and variants carry the stock, no point de stock part")
	void storeWithStock() {
		oneLine();
		twoVariants();
		NavItemService service = service(franchiseStore(), null);
		assertTrue(service.withStock());
		assertFalse(service.withStockPoints());

		NavItemDTO line = service.page(" Chem ", true, 0, 20).getContent().get(0);
		verify(items).findNavItems(eq("%chem%"), eq(Boolean.TRUE), eq(PageRequest.of(0, 20)));
		assertEquals("70061", line.getNavItemNo());
		assertEquals(4L, line.getVariants());
		assertEquals(new BigDecimal("13"), line.getStockQuantity());
		assertNull(line.getStockPoints());
		assertEquals("Vêtements", line.getCategoryName());
		assertNull(line.getSectionName(), "no section row of that code (a store receives the codes only)");

		NavItemVariantsDTO variants = service.variants("70061", false);
		verify(items).findNavItemVariants("70061", null);
		assertNull(variants.getStockPoints());
		assertEquals(2, variants.getVariants().size());
		assertEquals(new BigDecimal("2"), variants.getVariants().get(0).getStockQuantity());
		assertEquals(BigDecimal.ZERO, variants.getVariants().get(1).getStockQuantity(), "a null stock reads 0");
		assertFalse(variants.getVariants().get(1).isActive());
		assertNull(variants.getVariants().get(0).getPrices());
	}

	@Test
	@DisplayName("Store, clearance (ZEN): a variant carries the item's clearance, public price and own price as the till has them; the others null")
	void storeClearance() {
		Item zen = variant(1L, "14ZDU05~001", "3");
		zen.setUnitPrice(7.0);
		zen.setClearance(true);
		zen.setPublicPrice(19.5);
		Item own = variant(2L, "14ZDU05~002", "1");
		own.setUnitPrice(7.0);
		own.setClearance(true);
		own.setPublicPrice(19.5);
		own.setOwnPrice(true);
		Item happyness = variant(3L, "14ZDU05~003", "2");
		when(items.findNavItemVariants(eq("14ZDU05"), any())).thenReturn(Arrays.asList(zen, own, happyness));

		List<NavItemVariantsDTO.Variant> variants = service(franchiseStore(), null).variants("14ZDU05", false)
				.getVariants();
		assertEquals(Boolean.TRUE, variants.get(0).getClearance());
		assertEquals(19.5, variants.get(0).getPublicPrice());
		assertEquals(7.0, variants.get(0).getUnitPrice(), "the price sold, as before");
		assertNull(variants.get(0).getOwnPrice());
		assertEquals(Boolean.TRUE, variants.get(1).getOwnPrice(), "the page strikes nothing under a store's own price");
		assertNull(variants.get(2).getClearance(), "Happyness, EMTOP: no clearance");
		assertNull(variants.get(2).getPublicPrice());
		assertNull(variants.get(2).getOwnPrice());
	}

	@Test
	@DisplayName("ERP store (EMTOP's supply): no stock in the lines nor the variants")
	void erpStoreWithoutStock() {
		oneLine();
		twoVariants();
		NavItemService service = service(erpStore(), null);
		assertFalse(service.withStock());
		assertNull(service.page(null, true, 0, 20).getContent().get(0).getStockQuantity());
		assertNull(service.variants("70061", true).getVariants().get(0).getStockQuantity());
	}

	@Test
	@DisplayName("Head office without stock (ZEN): no stock, the active points per line and the variant × point de stock grid")
	void headOfficeGrid() {
		oneLine();
		twoVariants();
		NavItemService service = service(headOfficeWithoutStock(), twoPoints());
		assertFalse(service.withStock());
		assertTrue(service.withStockPoints());

		NavItemDTO line = service.page(null, false, 0, 20).getContent().get(0);
		verify(items).findNavItems(null, null, PageRequest.of(0, 20));
		assertNull(line.getStockQuantity());
		assertEquals(Long.valueOf(2), line.getStockPoints());

		NavItemVariantsDTO variants = service.variants("70061", true);
		assertEquals(Arrays.asList("FRANCHISE", "MAG1"),
				Arrays.asList(variants.getStockPoints().get(0).getCode(), variants.getStockPoints().get(1).getCode()));
		NavItemVariantsDTO.Variant first = variants.getVariants().get(0);
		assertNull(first.getStockQuantity());
		assertTrue(first.getPrices().get(12L).isClearance());
		assertEquals(new BigDecimal("7"), first.getPrices().get(12L).getClearancePrice());
		assertEquals(1, variants.getVariants().get(1).getPrices().size(), "no row in MAG1: no entry");
	}

	@Test
	@DisplayName("Head office: a NAV item number in no point answers 0 points; blank number answers no variant without a read")
	void edges() {
		oneLine();
		NavItemStockPoints none = mock(NavItemStockPoints.class);
		when(none.activePointCounts(anyCollection())).thenReturn(Collections.emptyMap());
		NavItemService service = service(headOfficeWithStock(), none);
		NavItemDTO line = service.page(null, true, -3, 9999).getContent().get(0);
		verify(items).findNavItems(null, Boolean.TRUE, PageRequest.of(0, NavItemService.MAX_SIZE));
		assertEquals(Long.valueOf(0), line.getStockPoints());
		assertEquals(new BigDecimal("13"), line.getStockQuantity(), "a head office with stock gives it");

		NavItemVariantsDTO blank = service.variants("  ", true);
		assertTrue(blank.getVariants().isEmpty());
		verify(items, never()).findNavItemVariants(any(), any());
	}

	@Test
	@DisplayName("Menu rule: navItems is the exists query on the NAV item number")
	void menuRule() {
		NavItemService service = service(franchiseStore(), null);
		when(items.existsByNavItemNoIsNotNull()).thenReturn(false);
		assertFalse(service.hasNavItems());
		when(items.existsByNavItemNoIsNotNull()).thenReturn(true);
		assertTrue(service.hasNavItems());
	}
}
