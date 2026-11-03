package com.digithink.zsretail.store.service;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.service.ItemDeletionCheck;
import com.digithink.zsretail.store.repository.QuickQuantityRepository;

/**
 * 3.1.2, bulk sale: an item used as the linked item (container) of a quick quantity is not deleted (DELETE /item/{id}
 * answers 409 with this message, instead of the foreign key error of quick_quantity.linked_item_id). The quick quantity
 * is changed or deleted first (Settings, Quick quantities). Every store (the table exists on every store).
 */
@Component
public class QuickQuantityItemDeletionCheck implements ItemDeletionCheck {

	private final QuickQuantityRepository quickQuantities;

	public QuickQuantityItemDeletionCheck(QuickQuantityRepository quickQuantities) {
		this.quickQuantities = quickQuantities;
	}

	@Override
	public String refusal(Long itemId) {
		long count = itemId == null ? 0 : quickQuantities.countByLinkedItemId(itemId);
		return count == 0 ? null
				: "This item is the linked item of " + count + " quick quantit" + (count > 1 ? "ies" : "y")
						+ ": change or delete " + (count > 1 ? "them" : "it") + " first (Settings, Quick quantities).";
	}
}
