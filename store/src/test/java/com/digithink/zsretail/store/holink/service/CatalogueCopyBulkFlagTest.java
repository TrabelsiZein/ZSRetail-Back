package com.digithink.zsretail.store.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.link.dto.CatalogueItemCopyDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.support.InMemoryItems;

/**
 * 3.1.2, bulk sale: "vendu en vrac" (Item.soldInBulk) travels in the item copy as the 3.1.1 images do. The head office
 * sends true or false (its null is false); the store writes it (false as null) and counts it in "unchanged"; a copy
 * without the field (a head office of an older build) keeps the store's value and never makes the copy look changed, so
 * no pull rewrites every item.
 */
class CatalogueCopyBulkFlagTest {

	static final String CODE = "VH52-1L";

	private InMemoryItems store;
	private CatalogueCopyWriter writer;

	@BeforeEach
	void setUp() {
		store = new InMemoryItems(1);
		writer = new CatalogueCopyWriter(store.familyRepository(), store.subFamilyRepository(), store.itemRepository(),
				store.barcodeRepository(), store.compositionRepository(), store.categoryRepository(),
				store.sectionRepository(), null);
	}

	/** The copy as a head office sends it; soldInBulk null: a head office of an older build (no field). */
	private static CatalogueItemCopyDTO copy(Boolean soldInBulk) {
		CatalogueItemCopyDTO copy = new CatalogueItemCopyDTO();
		copy.setItemCode(CODE);
		copy.setName("V H 52 1L");
		copy.setDefaultVAT(19);
		copy.setActive(true);
		copy.setUnitPrice(50.0);
		copy.setShowInPos(true); // as the head office sends it (CatalogueItemCopyDTO.of)
		copy.setSoldInBulk(soldInBulk);
		return copy;
	}

	private Item item() {
		return store.itemRepository().findByItemCode(CODE).get();
	}

	@Test
	@DisplayName("The head office's flag: true written, the same copy unchanged; false clears it (stored as null)")
	void writtenUnchangedCleared() {
		assertTrue(writer.saveItem(copy(true)).isWritten());
		assertEquals(Boolean.TRUE, item().getSoldInBulk());
		CatalogueCopyWriter.Outcome again = writer.saveItem(copy(true));
		assertTrue(again.isApplied());
		assertFalse(again.isWritten(), "the same flag: unchanged");

		assertTrue(writer.saveItem(copy(false)).isWritten(), "the head office cleared it");
		assertNull(item().getSoldInBulk(), "false is stored as null");
		assertFalse(writer.saveItem(copy(false)).isWritten(), "false and null are the same");
	}

	@Test
	@DisplayName("An older head office (no field): the store's value kept, the copy unchanged when nothing else moved")
	void notSentKept() {
		writer.saveItem(copy(true));
		CatalogueCopyWriter.Outcome outcome = writer.saveItem(copy(null));
		assertTrue(outcome.isApplied());
		assertFalse(outcome.isWritten());
		assertEquals(Boolean.TRUE, item().getSoldInBulk());

		CatalogueItemCopyDTO renamed = copy(null);
		renamed.setName("V H 52 1 litre");
		assertTrue(writer.saveItem(renamed).isWritten(), "another change: written");
		assertEquals(Boolean.TRUE, item().getSoldInBulk(), "still kept");
	}

	@Test
	@DisplayName("The upgrade: items written by an older head office, then false from a 3.1.2 one: unchanged, no rewrite")
	void upgradeRewritesNothing() {
		writer.saveItem(copy(null));
		assertNull(item().getSoldInBulk());
		assertFalse(writer.saveItem(copy(false)).isWritten(), "false on an item never flagged: unchanged");
	}

	@Test
	@DisplayName("The head office's copy (of): true for a flagged item, false for null or false")
	void copyOfTheItem() {
		Item item = new Item();
		item.setItemCode(CODE);
		assertEquals(Boolean.FALSE, CatalogueItemCopyDTO.of(item, 1.0, null).getSoldInBulk());
		item.setSoldInBulk(false);
		assertEquals(Boolean.FALSE, CatalogueItemCopyDTO.of(item, 1.0, null).getSoldInBulk());
		item.setSoldInBulk(true);
		assertEquals(Boolean.TRUE, CatalogueItemCopyDTO.of(item, 1.0, null).getSoldInBulk());
	}
}
