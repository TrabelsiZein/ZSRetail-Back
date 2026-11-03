package com.digithink.zsretail.store.service;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.store.model.QuickQuantity;
import com.digithink.zsretail.store.repository.QuickQuantityRepository;

/**
 * 3.1.2, bulk sale, step 1: the store's quick quantities (generic CRUD, QuickQuantityAPI). A save is checked first
 * ({@link #check}): a label (trimmed, at most 100 characters), a quantity above 0 with at most 3 decimals (stored without
 * trailing zeros), the linked item, when given, read by its id (an unknown id refused). An item used as a linked item is
 * not deleted: QuickQuantityItemDeletionCheck.
 */
@Service
public class QuickQuantityService extends _BaseService<QuickQuantity, Long> {

	@Autowired
	private QuickQuantityRepository quickQuantityRepository;

	@Autowired
	private ItemRepository itemRepository;

	@Override
	protected _BaseRepository<QuickQuantity, Long> getRepository() {
		return quickQuantityRepository;
	}

	@Override
	public QuickQuantity save(QuickQuantity entity) throws Exception {
		check(entity);
		return super.save(entity);
	}

	/**
	 * Refuses (IllegalArgumentException, shown on the page) a quick quantity without a label, with a label too long, without
	 * a quantity above 0, with more than 3 decimals, or with an unknown linked item; otherwise trims the label, normalizes
	 * the quantity, replaces the linked item by the one read (only its id is sent) and sets a missing display order to 0.
	 */
	public void check(QuickQuantity entity) {
		if (entity == null) {
			throw new IllegalArgumentException("A quick quantity is required.");
		}
		String label = entity.getLabel() == null ? "" : entity.getLabel().trim();
		if (label.isEmpty()) {
			throw new IllegalArgumentException("The label is required.");
		}
		if (label.length() > QuickQuantity.LABEL_LENGTH) {
			throw new IllegalArgumentException("The label is longer than " + QuickQuantity.LABEL_LENGTH + " characters.");
		}
		entity.setLabel(label);
		BigDecimal quantity = entity.getQuantity();
		if (quantity == null || quantity.signum() <= 0) {
			throw new IllegalArgumentException("The quantity must be above 0.");
		}
		if (Quantities.decimals(quantity) > Quantities.SCALE) {
			throw new IllegalArgumentException("The quantity " + Quantities.plain(quantity) + " has more than "
					+ Quantities.SCALE + " decimals.");
		}
		entity.setQuantity(Quantities.normalize(quantity));
		Item linked = entity.getLinkedItem();
		if (linked != null) {
			if (linked.getId() == null) {
				entity.setLinkedItem(null); // an empty choice
			} else {
				entity.setLinkedItem(itemRepository.findById(linked.getId()).orElseThrow(
						() -> new IllegalArgumentException("The linked item " + linked.getId() + " does not exist.")));
			}
		}
		if (entity.getDisplayOrder() == null) {
			entity.setDisplayOrder(0);
		}
	}
}
