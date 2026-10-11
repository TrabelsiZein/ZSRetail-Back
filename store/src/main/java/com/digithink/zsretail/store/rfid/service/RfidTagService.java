package com.digithink.zsretail.store.rfid.service;

import java.time.LocalDateTime;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.RfidException.Reason;
import com.digithink.zsretail.store.rfid.dto.RfidTagDTO;
import com.digithink.zsretail.store.rfid.model.RfidTag;
import com.digithink.zsretail.store.rfid.model.RfidTagStatus;
import com.digithink.zsretail.store.rfid.repository.RfidTagRepository;

/**
 * RFID tags step: the store's tags (docs/modules/rfid.md). The Tags page: a page of tags with filters, "move a tag to
 * another item" (an explicit action, IN_STOCK tags only) and delete (IN_STOCK tags only: a SOLD tag is the record of
 * the piece sold). The EPC rule of every tag: {@link #normaliseEpc}. Refusals are {@link RfidException}.
 */
@Service
@ConditionalOnInstallation(Installation.RFID)
public class RfidTagService {

	/** The largest page of the Tags page. */
	static final int MAX_PAGE_SIZE = 200;

	private static final Pattern EPC = Pattern.compile("[0-9A-F]+");

	private final RfidTagRepository tags;
	private final ItemRepository items;
	private final Supplier<String> user;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public RfidTagService(RfidTagRepository tags, ItemRepository items, CurrentUserProvider users) {
		this(tags, items, users::getCurrentUserName, LocalDateTime::now);
	}

	/** With a given user and clock: used by the tests. */
	public RfidTagService(RfidTagRepository tags, ItemRepository items, Supplier<String> user,
			Supplier<LocalDateTime> clock) {
		this.tags = tags;
		this.items = items;
		this.user = user;
		this.clock = clock;
	}

	/**
	 * An EPC as it is kept: trimmed, upper case, hexadecimal digits only, at most {@value RfidTag#MAX_EPC_LENGTH}.
	 * INVALID otherwise.
	 */
	public static String normaliseEpc(String raw) {
		String epc = raw == null ? "" : raw.trim().toUpperCase();
		if (epc.isEmpty() || epc.length() > RfidTag.MAX_EPC_LENGTH || !EPC.matcher(epc).matches()) {
			throw new RfidException(Reason.INVALID, "'" + (raw == null ? "" : raw.trim()) + "' is not an RFID tag (EPC):"
					+ " hexadecimal digits only, at most " + RfidTag.MAX_EPC_LENGTH + ".");
		}
		return epc;
	}

	/** The item of this id; NOT_FOUND when there is none. */
	Item item(Long itemId) {
		if (itemId == null) {
			throw new RfidException(Reason.INVALID, "Choose an item.");
		}
		return items.findById(itemId)
				.orElseThrow(() -> new RfidException(Reason.NOT_FOUND, "No item number " + itemId + "."));
	}

	/**
	 * The Tags page: newest first, a page of at most {@value #MAX_PAGE_SIZE}; itemId and status when given, epc a part of
	 * the EPC (any case, blank: none).
	 */
	@Transactional(readOnly = true)
	public Page<RfidTagDTO> list(Long itemId, RfidTagStatus status, String epcContains, int page, int size) {
		String epc = epcContains == null || epcContains.trim().isEmpty() ? null : epcContains.trim().toUpperCase();
		PageRequest request = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
		return tags.search(itemId, status, epc, request).map(RfidTagDTO::of);
	}

	/** The tag of this id; NOT_FOUND when there is none. */
	RfidTag get(Long tagId) {
		return tags.findById(tagId)
				.orElseThrow(() -> new RfidException(Reason.NOT_FOUND, "No RFID tag number " + tagId + "."));
	}

	/**
	 * Moves an IN_STOCK tag to another item (the explicit action of the Tags page). NOT_FOUND for the tag or the item,
	 * NOT_READY for a SOLD tag. The same item: nothing changes.
	 */
	@Transactional
	public RfidTagDTO move(Long tagId, Long itemId) {
		RfidTag tag = get(tagId);
		Item item = item(itemId);
		if (tag.getStatus() != RfidTagStatus.IN_STOCK) {
			throw new RfidException(Reason.NOT_READY, "The tag " + tag.getEpc() + " is sold: it is not moved.");
		}
		if (tag.getItem() != null && item.getId().equals(tag.getItem().getId())) {
			return RfidTagDTO.of(tag);
		}
		tag.setItem(item);
		tag.setUpdatedBy(user.get());
		tag.setUpdatedAt(clock.get());
		return RfidTagDTO.of(tags.save(tag));
	}

	/** Deletes an IN_STOCK tag. NOT_FOUND when there is none, NOT_READY for a SOLD tag (the record of the piece sold). */
	@Transactional
	public void delete(Long tagId) {
		RfidTag tag = get(tagId);
		if (tag.getStatus() != RfidTagStatus.IN_STOCK) {
			throw new RfidException(Reason.NOT_READY, "The tag " + tag.getEpc() + " is sold: it is kept as the record of"
					+ " the piece sold.");
		}
		tags.deleteById(tagId);
	}

	/** How many tags this item has (the item deletion check). */
	public long countForItem(Long itemId) {
		return tags.countByItem_Id(itemId);
	}
}
