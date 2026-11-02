package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.dto.NavItemVariantsDTO;

/**
 * 3.1.1, Item cards' Stock points tab: the fields of a point's row that differ from its item's
 * ({@link HoNavItemStockPoints#differences}), on rows as ROWS_OF_ITEMS gives them: [6..9] the row's name, description,
 * family code, sub-family code, [10..13] the item's.
 */
class HoNavItemStockPointsTest {

	/** A row of ROWS_OF_ITEMS with these row and item values (the first six columns unused here). */
	private static Object[] row(String name, String description, String family, String subFamily, String itemName,
			String itemDescription, String itemFamily, String itemSubFamily) {
		return new Object[] { 1L, 2L, 10.0, true, null, null, name, description, family, subFamily, itemName,
				itemDescription, itemFamily, itemSubFamily };
	}

	@Test
	@DisplayName("Identical values: no difference; null, blank and spaces around read the same")
	void same() {
		assertEquals(Collections.emptyList(),
				HoNavItemStockPoints.differences(row("CHEMISE", "Lin", "HOM", "CHEM", "CHEMISE", "Lin", "HOM", "CHEM")));
		assertEquals(Collections.emptyList(),
				HoNavItemStockPoints.differences(row(" CHEMISE ", "", null, "  ", "CHEMISE", null, "", null)));
	}

	@Test
	@DisplayName("Each field that differs is named, in order; a value against none differs either way; case counts")
	void differs() {
		assertEquals(Arrays.asList("name", "family"),
				HoNavItemStockPoints.differences(row("CHEMISE LIN", "Lin", "FEM", "CHEM", "CHEMISE", "Lin", "HOM", "CHEM")));
		assertEquals(Collections.singletonList("description"),
				HoNavItemStockPoints.differences(row("A", null, "F", "S", "A", "Coton", "F", "S")),
				"a blank row description against the item's: the stores receive the row's");
		assertEquals(Collections.singletonList("subFamily"),
				HoNavItemStockPoints.differences(row("A", null, "F", "S2", "A", null, "F", null)),
				"a sub-family on the row, none on the item");
		assertEquals(Arrays.asList("name", "description", "family", "subFamily"),
				HoNavItemStockPoints.differences(row("a", "x", "f", "s", "A", "X", "F", "S")));
	}

	@Test
	@DisplayName("The flag: set by any difference; false when identical or not read (the NAV items page)")
	void flag() {
		NavItemVariantsDTO.PointPrice price = new NavItemVariantsDTO.PointPrice(10.0, true, false, null);
		assertFalse(price.isDiffers(), "not read");
		price.setDifferences(Collections.emptyList());
		assertFalse(price.isDiffers());
		price.setDifferences(Collections.singletonList("name"));
		assertTrue(price.isDiffers());
	}
}
