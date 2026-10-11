package com.digithink.zsretail.store.rfid.service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.RfidException.Reason;
import com.digithink.zsretail.store.rfid.dto.RfidAssignmentRead;
import com.digithink.zsretail.store.rfid.dto.RfidAssignmentRead.Group;
import com.digithink.zsretail.store.rfid.dto.RfidAssignmentRequest;
import com.digithink.zsretail.store.rfid.dto.RfidAssignmentResult;
import com.digithink.zsretail.store.rfid.dto.RfidAssignmentResult.Refusal;
import com.digithink.zsretail.store.rfid.dto.RfidReadResult;
import com.digithink.zsretail.store.rfid.model.RfidTag;
import com.digithink.zsretail.store.rfid.model.RfidTagSource;
import com.digithink.zsretail.store.rfid.model.RfidTagStatus;
import com.digithink.zsretail.store.rfid.repository.RfidTagRepository;

/**
 * RFID tags step: linking tags to an item (docs/modules/rfid.md, "Tag assignment").
 * <ul>
 * <li>{@link #read}: reads a reader (RfidReadService.testRead: the same refusals, inactive, busy, unreachable) and
 * sorts the tags seen into NEW, SAME_ITEM, OTHER_ITEM, SOLD for the item, each "weak" when its best signal is below the
 * reader's minimum signal (RfidReadService.isWeak, the rule of the Test read too). Writes nothing.</li>
 * <li>{@link #save}: links the EPCs kept by the user, checked again on the server: only an unknown tag is linked; a tag
 * on this item is left as it is (saving twice changes nothing); a tag on another item or sold is refused and reported,
 * never changed. One save at a time in this store (a lock, then one transaction), so two users never link one tag
 * twice; the unique EPC of the table guards the rest.</li>
 * </ul>
 * The item: an active item of the store. The expected quantity: none, or 0 to {@value #MAX_EXPECTED}.
 */
@Service
@ConditionalOnInstallation(Installation.RFID)
public class RfidAssignmentService {

	static final int MAX_EXPECTED = 100000;
	/** The most EPCs one save takes. */
	static final int MAX_EPCS = 5000;
	static final int MAX_SOURCE_DOCUMENT = 30;

	/** One save at a time in this store (one application per store). */
	private static final Object SAVES = new Object();

	private final RfidReadService reads;
	private final RfidTagService tagService;
	private final RfidTagRepository tags;
	private final TransactionOperations transactions;
	private final Supplier<String> user;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public RfidAssignmentService(RfidReadService reads, RfidTagService tagService, RfidTagRepository tags,
			CurrentUserProvider users, PlatformTransactionManager transactionManager) {
		this(reads, tagService, tags, new TransactionTemplate(transactionManager), users::getCurrentUserName,
				LocalDateTime::now);
	}

	/** With given transactions, user and clock: used by the tests. */
	public RfidAssignmentService(RfidReadService reads, RfidTagService tagService, RfidTagRepository tags,
			TransactionOperations transactions, Supplier<String> user, Supplier<LocalDateTime> clock) {
		this.reads = reads;
		this.tagService = tagService;
		this.tags = tags;
		this.transactions = transactions;
		this.user = user;
		this.clock = clock;
	}

	/** The item of an assignment: it exists and is active. */
	private Item activeItem(Long itemId) {
		Item item = tagService.item(itemId);
		if (!Boolean.TRUE.equals(item.getActive())) {
			throw new RfidException(Reason.INVALID, "The item " + item.getItemCode() + " is inactive: tags are linked to"
					+ " an active item.");
		}
		return item;
	}

	/** Reads the reader and sorts the tags seen for the item. */
	public RfidAssignmentRead read(RfidAssignmentRequest.Read request) {
		Item item = activeItem(request.getItemId());
		Integer expected = request.getExpectedQuantity();
		if (expected != null && (expected < 0 || expected > MAX_EXPECTED)) {
			throw new RfidException(Reason.INVALID, "The expected quantity is a whole number from 0 to " + MAX_EXPECTED
					+ ", not " + expected + ".");
		}
		if (request.getReaderId() == null) {
			throw new RfidException(Reason.INVALID, "Choose a reader.");
		}
		RfidReadResult result = reads.testRead(request.getReaderId());

		RfidAssignmentRead answer = new RfidAssignmentRead();
		answer.setReaderName(result.getReaderName());
		answer.setItemId(item.getId());
		answer.setItemCode(item.getItemCode());
		answer.setItemName(item.getName());
		answer.setExpectedQuantity(expected);
		answer.setReadTimeMs(result.getReadTimeMs());
		answer.setDurationMs(result.getDurationMs());
		answer.setMinSignal(result.getMinSignal());

		// The tags seen (the reader's RfidTag, not the table's)
		List<com.digithink.zsretail.store.rfid.reader.RfidTag> seen = result.getTags().stream()
				.filter(tag -> isEpc(tag.getEpc())).collect(Collectors.toList());
		if (seen.isEmpty()) {
			return answer;
		}
		double best = seen.stream().mapToDouble(com.digithink.zsretail.store.rfid.reader.RfidTag::getBestRssi).max()
				.getAsDouble();
		answer.setBestRssi(best);
		Map<String, RfidTag> known = known(
				seen.stream().map(tag -> RfidTagService.normaliseEpc(tag.getEpc())).collect(Collectors.toList()));
		for (com.digithink.zsretail.store.rfid.reader.RfidTag tag : seen) {
			String epc = RfidTagService.normaliseEpc(tag.getEpc());
			RfidAssignmentRead.Tag row = new RfidAssignmentRead.Tag();
			row.setEpc(epc);
			row.setBestRssi(tag.getBestRssi());
			row.setSeen(tag.getSeen());
			row.setWeak(tag.isWeak());
			RfidTag stored = known.get(epc);
			if (stored == null) {
				row.setGroup(Group.NEW);
				answer.getNewTags().add(row);
				continue;
			}
			Item on = stored.getItem();
			if (stored.getStatus() == RfidTagStatus.SOLD) {
				row.setGroup(Group.SOLD);
				answer.getSold().add(otherItem(row, on));
			} else if (on.getId().equals(item.getId())) {
				row.setGroup(Group.SAME_ITEM);
				answer.getSameItem().add(row);
			} else {
				row.setGroup(Group.OTHER_ITEM);
				answer.getOtherItem().add(otherItem(row, on));
			}
		}
		return answer;
	}

	private static RfidAssignmentRead.Tag otherItem(RfidAssignmentRead.Tag row, Item on) {
		row.setOtherItemId(on.getId());
		row.setOtherItemCode(on.getItemCode());
		row.setOtherItemName(on.getName());
		return row;
	}

	/** Links the EPCs kept by the user to the item, checked again here (see the class comment). */
	public RfidAssignmentResult save(RfidAssignmentRequest.Save request) {
		Item item = activeItem(request.getItemId());
		RfidTagSource source = request.getSourceType() == null ? RfidTagSource.MANUAL : request.getSourceType();
		String document = null;
		Integer line = null;
		if (source == RfidTagSource.RECEPTION) {
			document = request.getSourceDocument() == null ? "" : request.getSourceDocument().trim();
			line = request.getSourceLine();
			if (document.isEmpty() || document.length() > MAX_SOURCE_DOCUMENT || line == null || line < 1) {
				throw new RfidException(Reason.INVALID, "A reception gives its delivery number (at most "
						+ MAX_SOURCE_DOCUMENT + " characters) and its line.");
			}
		}
		List<String> raw = request.getEpcs() == null ? java.util.Collections.emptyList() : request.getEpcs();
		if (raw.size() > MAX_EPCS) {
			throw new RfidException(Reason.INVALID, "At most " + MAX_EPCS + " tags in one save, not " + raw.size() + ".");
		}

		RfidAssignmentResult result = new RfidAssignmentResult();
		result.setItemId(item.getId());
		result.setItemCode(item.getItemCode());
		result.setItemName(item.getName());
		Set<String> epcs = new LinkedHashSet<>();
		for (String value : raw) {
			if (isEpc(value)) {
				epcs.add(RfidTagService.normaliseEpc(value));
			} else {
				result.getRefused().add(refused(value == null ? "" : value.trim(), Refusal.INVALID, null,
						"'" + (value == null ? "" : value.trim()) + "' is not an RFID tag (EPC)."));
			}
		}
		String sourceDocument = document;
		Integer sourceLine = line;
		synchronized (SAVES) {
			transactions.executeWithoutResult(status -> link(item, epcs, source, sourceDocument, sourceLine, result));
		}
		return result;
	}

	private void link(Item item, Set<String> epcs, RfidTagSource source, String document, Integer line,
			RfidAssignmentResult result) {
		Map<String, RfidTag> known = known(epcs);
		LocalDateTime now = clock.get();
		String by = user.get();
		for (String epc : epcs) {
			RfidTag stored = known.get(epc);
			if (stored == null) {
				RfidTag tag = new RfidTag();
				tag.setEpc(epc);
				tag.setItem(item);
				tag.setStatus(RfidTagStatus.IN_STOCK);
				tag.setSourceType(source);
				tag.setSourceDocument(document);
				tag.setSourceLine(line);
				tag.setCreatedAt(now);
				tag.setCreatedBy(by);
				tags.save(tag);
				result.getLinked().add(epc);
			} else if (stored.getStatus() == RfidTagStatus.SOLD) {
				result.getRefused().add(refused(epc, Refusal.SOLD, stored.getItem(),
						"The tag " + epc + " is sold (item " + stored.getItem().getItemCode() + "): it is not linked again."));
			} else if (stored.getItem().getId().equals(item.getId())) {
				result.getAlreadyOnItem().add(epc);
			} else {
				result.getRefused().add(refused(epc, Refusal.OTHER_ITEM, stored.getItem(),
						"The tag " + epc + " is on the item " + stored.getItem().getItemCode() + " "
								+ stored.getItem().getName() + ": move it on the Tags page if it is wrong."));
			}
		}
	}

	private static RfidAssignmentResult.Refused refused(String epc, Refusal reason, Item on, String message) {
		RfidAssignmentResult.Refused refused = new RfidAssignmentResult.Refused();
		refused.setEpc(epc);
		refused.setReason(reason);
		if (on != null) {
			refused.setItemCode(on.getItemCode());
			refused.setItemName(on.getName());
		}
		refused.setMessage(message);
		return refused;
	}

	/** The tags of the table among these EPCs (normalised), by EPC. */
	private Map<String, RfidTag> known(java.util.Collection<String> epcs) {
		if (epcs.isEmpty()) {
			return java.util.Collections.emptyMap();
		}
		return tags.findByEpcIn(epcs).stream()
				.collect(Collectors.toMap(RfidTag::getEpc, Function.identity()));
	}

	private static boolean isEpc(String value) {
		try {
			RfidTagService.normaliseEpc(value);
			return true;
		} catch (RfidException e) {
			return false;
		}
	}
}
