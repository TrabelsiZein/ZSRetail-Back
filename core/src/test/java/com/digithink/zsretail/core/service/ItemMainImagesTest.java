package com.digithink.zsretail.core.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemImage;
import com.digithink.zsretail.core.model.enumeration.ImageSource;
import com.digithink.zsretail.core.repository.ItemImageRepository;

/**
 * 3.1.2: the till tiles' images (ItemMainImages.forTiles, ItemAPI's POS reads): Item.imageUrl (an uploaded file) first,
 * else the item's main URL image of item_image (ZEN's frontImg); nothing when POS_SHOW_IMAGES is off; every item changed
 * detached first. And the main URL images themselves (mainUrls, also the store order catalogue's).
 */
class ItemMainImagesTest {

	static final String FRONT = "https://images.zen.com.tn/uploads/front.JPG";
	static final String DETAILS = "https://images.zen.com.tn/uploads/details.JPG";

	/** The rows the repository holds, answered in that order (the query's order) for the ids asked. */
	private final List<ItemImage> rows = new ArrayList<>();
	/** The ids asked per read. */
	private final List<Collection<Long>> reads = new ArrayList<>();
	private final List<Item> detached = new ArrayList<>();

	private final ItemImageRepository images = proxy(ItemImageRepository.class, (method, args) -> {
		if (!"findOfItems".equals(method)) {
			return UNHANDLED;
		}
		@SuppressWarnings("unchecked")
		Collection<Long> ids = (Collection<Long>) args[0];
		reads.add(ids);
		return rows.stream().filter(row -> ids.contains(row.getItemId())).collect(Collectors.toList());
	});

	private static Item item(long id, String imageUrl) {
		Item item = new Item();
		item.setId(id);
		item.setImageUrl(imageUrl);
		return item;
	}

	private void row(long itemId, ImageSource source, String path) {
		ItemImage image = new ItemImage();
		image.setItemId(itemId);
		image.setSource(source);
		image.setPath(path);
		rows.add(image);
	}

	private void tiles(boolean showImages, Item... items) {
		ItemMainImages.forTiles(Arrays.asList(items), showImages, images, detached::add);
	}

	@Test
	@DisplayName("an uploaded file stays the tile's image; item_image is not even read when every item has one")
	void fileFirst() {
		row(1, ImageSource.URL, FRONT);
		Item withFile = item(1, "1.jpg");
		tiles(true, withFile);
		assertEquals("1.jpg", withFile.getImageUrl());
		assertTrue(reads.isEmpty());
		assertTrue(detached.isEmpty());
	}

	@Test
	@DisplayName("without a file: the first URL row (the main one, frontImg), the item detached first")
	void urlFallback() {
		row(2, ImageSource.URL, FRONT);
		row(2, ImageSource.URL, DETAILS);
		Item zen = item(2, null);
		Item blank = item(3, "  ");
		row(3, ImageSource.URL, DETAILS);
		tiles(true, zen, blank);
		assertEquals(FRONT, zen.getImageUrl());
		assertEquals(DETAILS, blank.getImageUrl());
		assertEquals(Arrays.asList(zen, blank), detached);
	}

	@Test
	@DisplayName("FILE rows and blank addresses are left out; an item without URL row keeps no image and is not detached")
	void fileRowsAndNone() {
		row(4, ImageSource.FILE, "4-a.jpg");
		row(4, ImageSource.URL, " ");
		row(4, ImageSource.URL, DETAILS);
		row(5, ImageSource.FILE, "5-a.jpg");
		Item four = item(4, null);
		Item five = item(5, null);
		Item six = item(6, null);
		tiles(true, four, five, six);
		assertEquals(DETAILS, four.getImageUrl());
		assertNull(five.getImageUrl());
		assertNull(six.getImageUrl());
		assertEquals(Arrays.asList(four), detached);
	}

	@Test
	@DisplayName("images off: no image at all, the items that had one detached, item_image never read")
	void imagesOff() {
		row(8, ImageSource.URL, FRONT);
		Item withFile = item(7, "7.jpg");
		Item zen = item(8, null);
		tiles(false, withFile, zen);
		assertNull(withFile.getImageUrl());
		assertNull(zen.getImageUrl());
		assertEquals(Arrays.asList(withFile), detached);
		assertTrue(reads.isEmpty());
	}

	@Test
	@DisplayName("only the items without a file are asked, at most 1,000 ids per read")
	void batches() {
		List<Item> items = LongStream.rangeClosed(1, 2_500).mapToObj(id -> item(id, null)).collect(Collectors.toList());
		items.add(item(9_999, "9999.jpg"));
		row(2_500, ImageSource.URL, FRONT);
		ItemMainImages.forTiles(items, true, images, detached::add);
		assertEquals(Arrays.asList(1_000, 1_000, 500), reads.stream().map(Collection::size).collect(Collectors.toList()));
		assertTrue(reads.stream().noneMatch(ids -> ids.contains(9_999L)));
		assertEquals(FRONT, items.get(2_499).getImageUrl());
		assertEquals(1, detached.size());
	}

	@Test
	@DisplayName("mainUrls: each item's first URL row, by item id")
	void mainUrls() {
		row(10, ImageSource.URL, FRONT);
		row(10, ImageSource.URL, DETAILS);
		row(11, ImageSource.FILE, "11.jpg");
		Map<Long, String> urls = ItemMainImages.mainUrls(images, Arrays.asList(10L, 11L, 12L));
		assertEquals(1, urls.size());
		assertEquals(FRONT, urls.get(10L));
	}
}
