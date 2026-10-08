package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import com.digithink.zsretail.controller.ItemBarcodeAPI;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.service.ItemService.ItemStatusFilter;

/**
 * The status of the item lists (GET item-barcode/items-with-barcodes): absent = ACTIVE (the till, the label and barcode
 * pages, as before); INACTIVE and ALL for the Items page. The till's rule (showInPos: TAX_STAMP hidden) applies to every
 * status. The query is checked on the predicates it builds (no database in these tests).
 */
class ItemStatusFilterTest {

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static final class Criteria {
		final CriteriaBuilder cb = mock(CriteriaBuilder.class);
		final Root<Item> root = mock(Root.class);
		final CriteriaQuery<?> query = mock(CriteriaQuery.class);
		final Path active = mock(Path.class);
		final Path showInPos = mock(Path.class);

		Criteria() {
			when(root.get("active")).thenReturn(active);
			when(root.get("showInPos")).thenReturn(showInPos);
			Predicate any = mock(Predicate.class);
			when(cb.conjunction()).thenReturn(any);
			when(cb.and(any(Predicate.class), any(Predicate.class))).thenReturn(any);
			when(cb.or(any(Predicate.class), any(Predicate.class))).thenReturn(any);
		}

		void build(ItemStatusFilter status) {
			ItemService.itemsSpecification(status, null, null, null, null, null, null).toPredicate(root, query, cb);
		}
	}

	@Test
	@DisplayName("ACTIVE (and no status): active true only, as before; showInPos applied")
	void active() {
		for (ItemStatusFilter status : new ItemStatusFilter[] { ItemStatusFilter.ACTIVE, null }) {
			Criteria c = new Criteria();
			c.build(status);
			verify(c.cb).isTrue(c.active);
			verify(c.cb, never()).isFalse(c.active);
			verify(c.cb, never()).isNull(c.active);
			verify(c.cb).isTrue(c.showInPos);
			verify(c.cb).isNull(c.showInPos);
		}
	}

	@Test
	@DisplayName("INACTIVE: active false or empty; showInPos applied (TAX_STAMP never)")
	void inactive() {
		Criteria c = new Criteria();
		c.build(ItemStatusFilter.INACTIVE);
		verify(c.cb).isFalse(c.active);
		verify(c.cb).isNull(c.active);
		verify(c.cb, never()).isTrue(c.active);
		verify(c.cb).isTrue(c.showInPos);
	}

	@Test
	@DisplayName("ALL: no condition on active; showInPos applied (TAX_STAMP never)")
	void all() {
		Criteria c = new Criteria();
		c.build(ItemStatusFilter.ALL);
		verify(c.cb, never()).isTrue(c.active);
		verify(c.cb, never()).isFalse(c.active);
		verify(c.cb, never()).isNull(c.active);
		verify(c.cb).isTrue(c.showInPos);
		verify(c.cb).isNull(c.showInPos);
	}

	@Test
	@DisplayName("The API: no status = ACTIVE (today's call); ACTIVE, INACTIVE, ALL in any case; another value 400")
	void api() {
		ItemBarcodeAPI api = new ItemBarcodeAPI();
		ItemService items = mock(ItemService.class);
		ItemBarcodeService barcodes = mock(ItemBarcodeService.class);
		ReflectionTestUtils.setField(api, "itemService", items);
		ReflectionTestUtils.setField(api, "itemBarcodeService", barcodes);
		Page<Item> empty = new PageImpl<>(Collections.emptyList());
		when(items.findItems(any(), any(), any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(empty);
		when(barcodes.getActiveBarcodesForItems(any())).thenReturn(Collections.emptyList());

		assertEquals(200, api.getAllItemsWithBarcodes(0, 20, null, null, null, null, null, null, null).getStatusCodeValue());
		verify(items).findItems(eq(ItemStatusFilter.ACTIVE), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
				any(Pageable.class));
		api.getAllItemsWithBarcodes(0, 20, null, null, null, null, null, null, " inactive ");
		verify(items).findItems(eq(ItemStatusFilter.INACTIVE), isNull(), isNull(), isNull(), isNull(), isNull(),
				isNull(), any(Pageable.class));
		api.getAllItemsWithBarcodes(0, 20, null, null, null, null, null, null, "ALL");
		verify(items).findItems(eq(ItemStatusFilter.ALL), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
				any(Pageable.class));
		ResponseEntity<?> wrong = api.getAllItemsWithBarcodes(0, 20, null, null, null, null, null, null, "DISABLED");
		assertEquals(400, wrong.getStatusCodeValue());
		verify(items, never()).findActiveItems(any(), any(), any(), any(), any(), any(), any(Pageable.class));
	}
}
