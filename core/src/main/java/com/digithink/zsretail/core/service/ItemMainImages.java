package com.digithink.zsretail.core.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemImage;
import com.digithink.zsretail.core.model.enumeration.ImageSource;
import com.digithink.zsretail.core.repository.ItemImageRepository;

/**
 * The main image given by address of items (item_image, source URL): each item's first URL image in the images' order
 * ({@link ItemImageRepository#IMAGE_ORDER}: the main one first; ZEN's frontImg). FILE rows are left out. One rule for
 * the store order catalogue (3.1.1) and the till's item tiles (3.1.2, ItemAPI): an item's Item.imageUrl (an uploaded
 * file) comes first, this address only when it has none.
 */
public final class ItemMainImages {

	/** Item ids per read of their images (the repository takes at most 2,000). */
	public static final int IDS_PER_READ = 1000;

	private ItemMainImages() {
	}

	/**
	 * 3.1.2: the till's item tiles (ItemAPI's POS reads). Images on (POS_SHOW_IMAGES): an item with an Item.imageUrl
	 * keeps it (an uploaded file, read at item-image/item/{id}); one without gets in imageUrl its main URL image (an
	 * address, shown as it is), when it has one. Images off: imageUrl null, item_image not read. Every item changed is
	 * first given to detach (out of the persistence context): the answer only, never saved.
	 */
	public static void forTiles(List<Item> items, boolean showImages, ItemImageRepository images, Consumer<Item> detach) {
		if (!showImages) {
			items.stream().filter(item -> item.getImageUrl() != null).forEach(item -> {
				detach.accept(item);
				item.setImageUrl(null);
			});
			return;
		}
		List<Item> without = items.stream().filter(item -> !hasText(item.getImageUrl())).collect(Collectors.toList());
		if (without.isEmpty()) {
			return;
		}
		Map<Long, String> urls = mainUrls(images, without.stream().map(Item::getId).distinct().collect(Collectors.toList()));
		without.stream().filter(item -> urls.containsKey(item.getId())).forEach(item -> {
			detach.accept(item);
			item.setImageUrl(urls.get(item.getId()));
		});
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}

	/** The main URL image of each of these items that has one, by item id; read IDS_PER_READ ids at a time. */
	public static Map<Long, String> mainUrls(ItemImageRepository images, List<Long> itemIds) {
		Map<Long, String> urls = new HashMap<>();
		for (int from = 0; from < itemIds.size(); from += IDS_PER_READ) {
			for (ItemImage image : images
					.findOfItems(itemIds.subList(from, Math.min(from + IDS_PER_READ, itemIds.size())))) {
				if (image.getSource() == ImageSource.URL && hasText(image.getPath())) {
					urls.putIfAbsent(image.getItemId(), image.getPath());
				}
			}
		}
		return urls;
	}
}
