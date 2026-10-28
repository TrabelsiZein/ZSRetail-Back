package com.digithink.zsretail.store.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.link.dto.CatalogueItemCopyDTO;
import com.digithink.zsretail.core.model.ItemImage;
import com.digithink.zsretail.core.model.enumeration.ImageSource;
import com.digithink.zsretail.core.repository.ItemImageRepository;
import com.digithink.zsretail.core.support.InMemoryItems;

/**
 * 3.1.1 (item images): the store writes the URL images an item copy carries (its order, main and sort), replacing its
 * URL rows; its FILE rows (the store's own) untouched; the same images again: the copy unchanged; other images: the
 * copy written; a copy without the field (a head office of an older build): the rows kept.
 */
class CatalogueCopyImagesTest {

	static final String CODE = "43ZFCHD17~001";
	static final String FRONT = "https://images.zen.com.tn/uploads/front.JPG";
	static final String DETAILS = "https://images.zen.com.tn/uploads/details.JPG";

	private InMemoryItems store;
	private final List<ItemImage> rows = new ArrayList<>();
	private long nextImageId = 1;
	private CatalogueCopyWriter writer;

	@BeforeEach
	void setUp() {
		store = new InMemoryItems(1);
		ItemImageRepository images = mock(ItemImageRepository.class);
		when(images.findOfItem(anyLong())).thenAnswer(call -> rows.stream()
				.filter(row -> row.getItemId().equals(call.getArgument(0)))
				.sorted(Comparator.comparing((ItemImage row) -> row.isMain() ? 0 : 1)
						.thenComparing(ItemImage::getSortOrder).thenComparing(ItemImage::getId))
				.collect(Collectors.toList()));
		when(images.save(any(ItemImage.class))).thenAnswer(call -> {
			ItemImage row = call.getArgument(0);
			row.setId(nextImageId++);
			rows.add(row);
			return row;
		});
		doAnswer(call -> {
			for (Object row : (Iterable<?>) call.getArgument(0)) {
				rows.remove(row);
			}
			return null;
		}).when(images).deleteAll(any());
		writer = new CatalogueCopyWriter(store.familyRepository(), store.subFamilyRepository(), store.itemRepository(),
				store.barcodeRepository(), store.compositionRepository(), store.categoryRepository(),
				store.sectionRepository(), images);
	}

	private static CatalogueItemCopyDTO copy(List<CatalogueItemCopyDTO.Image> images) {
		CatalogueItemCopyDTO copy = new CatalogueItemCopyDTO();
		copy.setItemCode(CODE);
		copy.setName("CHEMISE");
		copy.setDefaultVAT(19);
		copy.setActive(true);
		copy.setUnitPrice(45.0);
		copy.setNavItemNo("43ZFCHD17");
		copy.setVariantCode("001");
		copy.setShowInPos(true); // as the head office sends it (CatalogueItemCopyDTO.of)
		copy.setImages(images);
		return copy;
	}

	private static List<CatalogueItemCopyDTO.Image> both() {
		return Arrays.asList(new CatalogueItemCopyDTO.Image(FRONT, true, 0),
				new CatalogueItemCopyDTO.Image(DETAILS, false, 1));
	}

	private Long itemId() {
		return store.itemRepository().findByItemCode(CODE).get().getId();
	}

	/** The item's rows as "SOURCE path main sort", in the images' order. */
	private List<String> rowsOfItem() {
		Long id = itemId();
		return rows.stream().filter(row -> row.getItemId().equals(id))
				.sorted(Comparator.comparing((ItemImage row) -> row.isMain() ? 0 : 1).thenComparing(ItemImage::getSortOrder))
				.map(row -> row.getSource() + " " + row.getPath() + " " + (row.isMain() ? "main" : "-") + " "
						+ row.getSortOrder())
				.collect(Collectors.toList());
	}

	@Test
	@DisplayName("A new item with its images: written as sent; the same copy again: unchanged, nothing rewritten")
	void writtenThenUnchanged() {
		assertTrue(writer.saveItem(copy(both())).isWritten());
		assertEquals(Arrays.asList("URL " + FRONT + " main 0", "URL " + DETAILS + " - 1"), rowsOfItem());
		long ids = nextImageId;
		CatalogueCopyWriter.Outcome again = writer.saveItem(copy(both()));
		assertTrue(again.isApplied());
		assertFalse(again.isWritten(), "the same images: the copy is unchanged");
		assertEquals(ids, nextImageId, "no row rewritten");
	}

	@Test
	@DisplayName("Other images: the copy written, the URL rows replaced, the store's FILE row kept; none: URL rows removed")
	void replacedFileKept() {
		writer.saveItem(copy(Collections.singletonList(new CatalogueItemCopyDTO.Image(FRONT, true, 0))));
		ItemImage file = new ItemImage();
		file.setItemId(itemId());
		file.setSource(ImageSource.FILE);
		file.setPath("own.jpg");
		file.setSortOrder(5);
		file.setId(nextImageId++);
		rows.add(file);

		assertTrue(writer.saveItem(copy(both())).isWritten());
		assertEquals(Arrays.asList("URL " + FRONT + " main 0", "URL " + DETAILS + " - 1", "FILE own.jpg - 5"),
				rowsOfItem());

		assertTrue(writer.saveItem(copy(new ArrayList<>())).isWritten());
		assertEquals(Collections.singletonList("FILE own.jpg - 5"), rowsOfItem());
	}

	@Test
	@DisplayName("A copy without images (an older head office): the rows kept, the copy unchanged when nothing else moved")
	void notSent() {
		writer.saveItem(copy(both()));
		CatalogueCopyWriter.Outcome outcome = writer.saveItem(copy(null));
		assertFalse(outcome.isWritten());
		assertEquals(2, rowsOfItem().size());
	}
}
