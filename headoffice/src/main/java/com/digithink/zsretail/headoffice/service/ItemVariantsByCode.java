package com.digithink.zsretail.headoffice.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.repository.ItemRepository;

/**
 * Test log I-24: the variant description of the head office items, by item code, so the head office lists (ticket and
 * return details, network stock, delivery notes) show "name — variant" as the store's receipt does (the front's
 * itemLabel). One query per page; a code without an item or without a variant has no entry. No repository: empty.
 */
final class ItemVariantsByCode {

	private ItemVariantsByCode() {
	}

	static Map<String, String> of(ItemRepository items, Collection<String> codes) {
		Map<String, String> byCode = new HashMap<>();
		if (items == null || codes == null) return byCode;
		Set<String> wanted = new LinkedHashSet<>();
		codes.stream().filter(Objects::nonNull).forEach(wanted::add);
		if (wanted.isEmpty()) return byCode;
		for (Item item : items.findByItemCodeIn(wanted)) {
			String variant = item.getVariantDescription();
			if (item.getItemCode() != null && variant != null && !variant.trim().isEmpty()) {
				byCode.put(item.getItemCode(), variant);
			}
		}
		return byCode;
	}
}
