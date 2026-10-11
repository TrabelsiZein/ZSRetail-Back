package com.digithink.zsretail.store.rfid.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.RfidException.Reason;
import com.digithink.zsretail.store.rfid.dto.RfidPosReadRequest;
import com.digithink.zsretail.store.rfid.dto.RfidPosReadResult;
import com.digithink.zsretail.store.rfid.dto.RfidPosReadResult.RfidPosItem;
import com.digithink.zsretail.store.rfid.dto.RfidReadResult;
import com.digithink.zsretail.store.rfid.dto.RfidTicketTag;
import com.digithink.zsretail.store.rfid.model.PosMachine;
import com.digithink.zsretail.store.rfid.model.RfidTag;
import com.digithink.zsretail.store.rfid.model.RfidTagStatus;
import com.digithink.zsretail.store.rfid.repository.RfidSaleTagRepository;
import com.digithink.zsretail.store.rfid.repository.RfidTagRepository;

/**
 * RFID POS step (docs/modules/rfid.md, "POS"): the RFID button of the cart, a read with the reader of this POS machine
 * sorted for the cart, and the tags of a ticket (a pending ticket opened again, a ticket returned). Writes nothing:
 * the ticket's tags are written with the ticket (RfidSaleTags).
 */
@Service
@ConditionalOnInstallation(Installation.RFID)
public class RfidPosService {

	private final RfidReadService reads;
	private final PosMachineService machines;
	private final RfidTagRepository tags;
	private final RfidSaleTagRepository saleTags;
	private final SalesHeaderRepository tickets;

	public RfidPosService(RfidReadService reads, PosMachineService machines, RfidTagRepository tags,
			RfidSaleTagRepository saleTags, SalesHeaderRepository tickets) {
		this.reads = reads;
		this.machines = machines;
		this.tags = tags;
		this.saleTags = saleTags;
		this.tickets = tickets;
	}

	/**
	 * A read for the cart, with the reader of the POS machine of this address. Each tag in one group, in this order:
	 * not read clearly (weak), unknown, already sold, already in the cart, on another pending ticket, else added to its
	 * item. NOT_READY "This POS machine (address) has no reader." when no machine has the address or it has no reader;
	 * the other refusals of a read as POST /rfid/read.
	 */
	public RfidPosReadResult read(String remoteAddress, RfidPosReadRequest request) {
		String address = RfidAddresses.normalise(remoteAddress);
		PosMachine machine = machines.findByRequestAddress(address).orElse(null);
		if (machine == null || machine.getReader() == null) {
			throw new RfidException(Reason.NOT_READY, "This POS machine (" + address + ") has no reader.");
		}
		RfidReadResult read = reads.readForRequest(remoteAddress);
		RfidPosReadResult result = new RfidPosReadResult();
		result.setPosMachineName(read.getPosMachineName());
		result.setReaderName(read.getReaderName());
		result.setDurationMs(read.getDurationMs());

		Set<String> clear = new LinkedHashSet<>();
		for (com.digithink.zsretail.store.rfid.reader.RfidTag seen : read.getTags()) {
			if (seen.isWeak()) {
				result.setNotReadClearly(result.getNotReadClearly() + 1);
				continue;
			}
			String epc = epcOrNull(seen.getEpc());
			if (epc == null) {
				result.setUnknown(result.getUnknown() + 1);
			} else {
				clear.add(epc);
			}
		}
		Set<String> cart = new HashSet<>();
		if (request != null && request.getCartEpcs() != null) {
			for (String raw : request.getCartEpcs()) {
				String epc = epcOrNull(raw);
				if (epc != null) {
					cart.add(epc);
				}
			}
		}
		Map<String, RfidTag> known = clear.isEmpty() ? Collections.emptyMap()
				: tags.findByEpcIn(clear).stream().collect(Collectors.toMap(RfidTag::getEpc, Function.identity()));
		List<String> free = clear.stream().filter(epc -> known.containsKey(epc) && !cart.contains(epc)
				&& known.get(epc).getStatus() == RfidTagStatus.IN_STOCK).collect(Collectors.toList());
		Set<String> held = free.isEmpty() ? Collections.emptySet()
				: new HashSet<>(saleTags.heldBy(free, TransactionStatus.PENDING,
						request == null ? null : request.getPendingTicketId()));

		Map<Long, RfidPosItem> items = new LinkedHashMap<>();
		for (String epc : clear) {
			RfidTag tag = known.get(epc);
			if (tag == null) {
				result.setUnknown(result.getUnknown() + 1);
			} else if (tag.getStatus() != RfidTagStatus.IN_STOCK) {
				result.setAlreadySold(result.getAlreadySold() + 1);
			} else if (cart.contains(epc)) {
				result.setInCart(result.getInCart() + 1);
			} else if (held.contains(epc)) {
				result.setOnPendingTicket(result.getOnPendingTicket() + 1);
			} else {
				RfidPosItem item = items.computeIfAbsent(tag.getItem().getId(), id -> {
					RfidPosItem added = new RfidPosItem();
					added.setItemId(id);
					added.setItemCode(tag.getItem().getItemCode());
					added.setItemName(tag.getItem().getName());
					return added;
				});
				item.getEpcs().add(epc);
				result.setAdded(result.getAdded() + 1);
			}
		}
		result.setItems(new ArrayList<>(items.values()));
		return result;
	}

	/**
	 * The tags of a ticket, by its id or its number (one of the two). NOT_FOUND when there is no such ticket; INVALID
	 * when neither is given.
	 */
	@Transactional(readOnly = true)
	public List<RfidTicketTag> ticketTags(Long salesHeaderId, String ticketNumber) {
		SalesHeader ticket;
		if (salesHeaderId != null) {
			ticket = tickets.findById(salesHeaderId)
					.orElseThrow(() -> new RfidException(Reason.NOT_FOUND, "No ticket number " + salesHeaderId + "."));
		} else if (ticketNumber != null && !ticketNumber.trim().isEmpty()) {
			ticket = tickets.findBySalesNumber(ticketNumber.trim())
					.orElseThrow(() -> new RfidException(Reason.NOT_FOUND, "No ticket " + ticketNumber.trim() + "."));
		} else {
			throw new RfidException(Reason.INVALID, "Give the ticket (salesHeaderId or ticketNumber).");
		}
		return saleTags.findBySalesHeader_Id(ticket.getId()).stream()
				.map(row -> new RfidTicketTag(row.getEpc(), row.getItem().getId(),
						row.getSalesLine() == null ? null : row.getSalesLine().getId(), row.getReturnLine() != null))
				.collect(Collectors.toList());
	}

	private static String epcOrNull(String raw) {
		try {
			return RfidTagService.normaliseEpc(raw);
		} catch (RfidException notAnEpc) {
			return null;
		}
	}
}
