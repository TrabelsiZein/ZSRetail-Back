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
 * 3.1.2, bulk sale: "vendu en vrac" (Item.soldInBulk) is the store's own decision, a head office item's included. The
 * item copy carries the head office's value (true or false); the store takes it only for an item it creates from the
 * copy (null or false: not flagged), never writes it on an item it has, and never counts it in "unchanged", so a change
 * at the head office rewrites no item and never undoes the store's choice.
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
	@DisplayName("A new item takes the head office's value: true flagged; false or absent: not flagged (null)")
	void newItemTakesTheHeadOffice() {
		assertTrue(writer.saveItem(copy(true)).isWritten());
		assertEquals(Boolean.TRUE, item().getSoldInBulk());

		setUp();
		writer.saveItem(copy(false));
		assertNull(item().getSoldInBulk(), "false is stored as null");

		setUp();
		writer.saveItem(copy(null));
		assertNull(item().getSoldInBulk(), "an older head office: not flagged");
	}

	@Test
	@DisplayName("The store flags a head office item: a copy with false never clears it, and is not a change")
	void storeChoiceKeptAgainstFalse() {
		writer.saveItem(copy(false));
		item().setSoldInBulk(Boolean.TRUE); // PUT /item/{id}/bulk-flag on the store
		CatalogueCopyWriter.Outcome again = writer.saveItem(copy(false));
		assertTrue(again.isApplied());
		assertFalse(again.isWritten(), "the flag is not a difference");
		assertEquals(Boolean.TRUE, item().getSoldInBulk());
	}

	@Test
	@DisplayName("The store clears a flag the head office sent: a copy with true never sets it again, and is not a change")
	void storeChoiceKeptAgainstTrue() {
		writer.saveItem(copy(true));
		item().setSoldInBulk(null); // the store switched it off
		assertFalse(writer.saveItem(copy(true)).isWritten());
		assertNull(item().getSoldInBulk());
	}

	@Test
	@DisplayName("Another change of the item is written, the store's flag kept as it is")
	void otherChangeKeepsTheFlag() {
		writer.saveItem(copy(false));
		item().setSoldInBulk(Boolean.TRUE);
		CatalogueItemCopyDTO renamed = copy(false);
		renamed.setName("V H 52 1 litre");
		assertTrue(writer.saveItem(renamed).isWritten());
		assertEquals("V H 52 1 litre", item().getName());
		assertEquals(Boolean.TRUE, item().getSoldInBulk(), "kept");
	}

	@Test
	@DisplayName("The upgrade: items written by an older head office, then a 3.1.2 copy: unchanged, nothing rewritten")
	void upgradeRewritesNothing() {
		writer.saveItem(copy(null));
		assertFalse(writer.saveItem(copy(false)).isWritten());
		assertFalse(writer.saveItem(copy(true)).isWritten(), "even a flag at the head office");
		assertNull(item().getSoldInBulk());
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
