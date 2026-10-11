package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.headoffice.dto.StockPointItemDTO;
import com.digithink.zsretail.headoffice.model.HoStockPoint;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.headoffice.repository.HoStockPointItemRepository;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.support.InMemoryCatalogue;

/**
 * Stock points, step 4: the rows of the page "Items by point de stock". The row gives name, description, family,
 * sub-family (with their names), price and active; the item gives the VAT, the barcodes and the rest; the filters reach
 * the query as the repository expects them. The query itself is checked by QueryParameterBindingTest.
 */
class HoStockPointItemsServiceTest {

	private InMemoryCatalogue ho;
	private HoStockPointItemRepository rows;
	private HoStockPointItemsService service;
	private HoStockPoint franchise;
	private HoStockPointItem row;
	private Item item;

	@BeforeEach
	void setUp() {
		ho = new InMemoryCatalogue(1);
		rows = mock(HoStockPointItemRepository.class);
		service = new HoStockPointItemsService(rows, ho.stockPointRepository(), ho.itemRepository(),
				ho.barcodeRepository(), ho.familyRepository(), ho.subFamilyRepository());
		ItemFamily f1 = ho.family("F1");
		ItemSubFamily sf1 = ho.subFamily("SF1", f1);
		ho.subFamily("SF2", f1);
		item = ho.item("B001", 10.0, sf1);
		item.setErpExternalId("B001");
		ho.barcode("222", item);
		ho.barcode("111", item).setIsPrimary(true);
		ho.barcode("999", item).setActive(false); // not counted, as on the Item Barcodes page
		franchise = new HoStockPoint();
		franchise.setId(ho.nextId());
		franchise.setCode("FRANCHISE");
		franchise.setName("Franchise");
		franchise.setSortOrder(1);
		ho.stockPoints.put(franchise.getId(), franchise);
		row = ho.stockPointItem(franchise, item, 12.5);
		row.setName("B001 in FRANCHISE");
		row.setDescription("Sold in FRANCHISE");
		row.setFamilyCode("F1");
		row.setSubFamilyCode("SF2");
		row.setActive(false);
	}

	private static Page<Object[]> page(Object[]... lines) {
		List<Object[]> content = Arrays.asList(lines);
		return new PageImpl<>(content, PageRequest.of(0, 20), 41);
	}

	@Test
	@DisplayName("A row: name, description, family, sub-family, price and active from the row; VAT and barcodes from the item")
	void rowView() {
		when(rows.findRows(any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
				.thenReturn(page(new Object[] { row, item, franchise }));
		Page<StockPointItemDTO> answer = service.rows(null, null, null, null, null, null, null, 0, 20);
		assertEquals(41, answer.getTotalElements());
		StockPointItemDTO view = answer.getContent().get(0);
		assertEquals("FRANCHISE", view.getStockPointCode());
		assertEquals("Franchise", view.getStockPointName());
		assertEquals("B001", view.getItemCode());
		assertEquals("B001 in FRANCHISE", view.getName());
		assertEquals("Sold in FRANCHISE", view.getDescription());
		assertEquals("Family F1", view.getFamilyName());
		assertEquals("SF2", view.getSubFamilyCode());
		assertEquals("Sub-family SF2", view.getSubFamilyName(), "the row's sub-family, not the item's");
		assertEquals(12.5, view.getUnitPrice());
		assertFalse(view.getActive(), "the row's flag");
		assertTrue(view.getItemActive());
		assertEquals(Integer.valueOf(19), view.getDefaultVAT());
		assertEquals(Arrays.asList("111", "222"),
				Arrays.asList(view.getBarcodes().get(0).getBarcode(), view.getBarcodes().get(1).getBarcode()));
		assertTrue(view.getBarcodes().get(0).getIsPrimary());
	}

	@Test
	@DisplayName("Filters: search lower case with % around, blanks are none, status mapped, size kept from 1 to 200")
	@SuppressWarnings("unchecked")
	void filters() {
		when(rows.findRows(any(), any(), any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(page());
		service.rows(franchise.getId(), "  AbC ", " ", "SF1", 1.0, 9.0, "inactive", 3, 999);
		ArgumentCaptor<Pageable> paging = ArgumentCaptor.forClass(Pageable.class);
		verify(rows).findRows(eq(franchise.getId()), eq("%abc%"), isNull(), eq("SF1"), eq(1.0), eq(9.0), eq(Boolean.FALSE),
				paging.capture());
		assertEquals(3, paging.getValue().getPageNumber());
		assertEquals(200, paging.getValue().getPageSize());
		assertNull(HoStockPointItemsService.active(" all "));
		assertEquals(Boolean.TRUE, HoStockPointItemsService.active("ACTIVE"));
		assertThrows(IllegalArgumentException.class, () -> HoStockPointItemsService.active("ON"));
	}

	@Test
	@DisplayName("One row with its details; unknown: empty")
	void oneRow() {
		when(rows.findById(row.getId())).thenReturn(Optional.of(row));
		when(rows.findById(999L)).thenReturn(Optional.empty());
		StockPointItemDTO view = service.row(row.getId()).get();
		assertEquals("B001 in FRANCHISE", view.getName());
		assertEquals(Integer.valueOf(19), view.getDefaultVAT());
		assertEquals(2, view.getBarcodes().size());
		assertFalse(service.row(999L).isPresent());
	}
}
