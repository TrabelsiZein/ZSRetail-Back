package com.digithink.zsretail.store.rfid.service;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.service.ItemDeletionCheck;

/**
 * RFID tags step: an item with RFID tags is not deleted (DELETE /item/{id} answers 409 with this message, instead of
 * the foreign key error of rfid_tag.item_id). Its tags are moved to another item or deleted first (the Tags page).
 */
@Component
@ConditionalOnInstallation(Installation.RFID)
public class RfidItemDeletionCheck implements ItemDeletionCheck {

	private final RfidTagService tags;

	public RfidItemDeletionCheck(RfidTagService tags) {
		this.tags = tags;
	}

	@Override
	public String refusal(Long itemId) {
		long count = tags.countForItem(itemId);
		return count == 0 ? null
				: "This item has " + count + " RFID tag" + (count > 1 ? "s" : "") + ": move them to another item or delete"
						+ " them first (RFID, Tags page).";
	}
}
