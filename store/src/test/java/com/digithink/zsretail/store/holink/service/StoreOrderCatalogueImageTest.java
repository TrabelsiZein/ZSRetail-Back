package com.digithink.zsretail.store.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.dto.NavItemDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.store.holink.dto.StoreOrderCatalogueLineDTO;

/**
 * 3.1.1 (item images): the image of a line of the store order catalogue: the first variant's Item.imageUrl (a file,
 * read at item-image/item/{id}) as before; else the first variant's main URL image (a full address); else none.
 */
class StoreOrderCatalogueImageTest {

	static final String FRONT = "https://images.zen.com.tn/uploads/front.JPG";

	private static Item variant(long id, String imageUrl) {
		Item item = new Item();
		item.setId(id);
		item.setItemCode("43ZFCHD17~00" + id);
		item.setUnitPrice(45.0);
		item.setImageUrl(imageUrl);
		return item;
	}

	private static NavItemDTO line() {
		NavItemDTO line = new NavItemDTO();
		line.setNavItemNo("43ZFCHD17");
		line.setName("CHEMISE");
		return line;
	}

	@Test
	@DisplayName("Item.imageUrl first (as before), even when a variant has a URL image")
	void fileFirst() {
		Map<Long, String> urls = new HashMap<>();
		urls.put(1L, FRONT);
		StoreOrderCatalogueLineDTO dto = StoreOrderService.catalogueLine(line(),
				Arrays.asList(variant(1, null), variant(2, "2.jpg")), urls);
		assertEquals("2.jpg", dto.getMainImage());
		assertEquals(Long.valueOf(2), dto.getMainImageItemId());
	}

	@Test
	@DisplayName("No Item.imageUrl: the first variant with a main URL image, its address as it is")
	void urlFallback() {
		Map<Long, String> urls = new HashMap<>();
		urls.put(3L, FRONT);
		StoreOrderCatalogueLineDTO dto = StoreOrderService.catalogueLine(line(),
				Arrays.asList(variant(1, null), variant(3, " ")), urls);
		assertEquals(FRONT, dto.getMainImage());
		assertEquals(Long.valueOf(3), dto.getMainImageItemId());
	}

	@Test
	@DisplayName("Neither: no image")
	void none() {
		StoreOrderCatalogueLineDTO dto = StoreOrderService.catalogueLine(line(),
				Collections.singletonList(variant(1, null)), Collections.emptyMap());
		assertNull(dto.getMainImage());
		assertNull(dto.getMainImageItemId());
	}
}
