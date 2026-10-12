package com.digithink.zsretail.store.rfid.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.ReturnLine;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.model.RfidSaleTag;
import com.digithink.zsretail.store.rfid.model.RfidTag;
import com.digithink.zsretail.store.rfid.model.RfidTagStatus;
import com.digithink.zsretail.store.rfid.repository.RfidSaleTagRepository;
import com.digithink.zsretail.store.rfid.repository.RfidTagRepository;
import com.digithink.zsretail.store.service.SaleTagHooks;

import lombok.extern.log4j.Log4j2;

/**
 * RFID POS step: the tags of the tickets (docs/modules/rfid.md, "POS"), the sale code's SaleTagHooks. Joins the ticket's
 * transaction. rfid_tag does not change while a ticket is open: a pending ticket holds its tags in rfid_sale_tag; at
 * payment its tags still in stock become SOLD with the ticket number; a return puts back in stock the tags of an item
 * returned whole, and of an item returned in part the tags read among its own. A tag sent by the POS that is not (or no
 * longer) a piece in stock of the line's item is skipped with a warning, never a refusal: the ticket goes through.
 * <p>
 * A free piece is scanned or read like the others (decision of 2026-10-12): the tags of an item count all its pieces on
 * the ticket, its paid line and its free line (a "buy N get M" promotion), and the rows stay on the paid line.
 */
@Service
@ConditionalOnInstallation(Installation.RFID)
@Log4j2
public class RfidSaleTags implements SaleTagHooks {

	private final RfidTagRepository tags;
	private final RfidSaleTagRepository saleTags;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public RfidSaleTags(RfidTagRepository tags, RfidSaleTagRepository saleTags) {
		this(tags, saleTags, LocalDateTime::now);
	}

	/** With a given clock: used by the tests. */
	public RfidSaleTags(RfidTagRepository tags, RfidSaleTagRepository saleTags, Supplier<LocalDateTime> clock) {
		this.tags = tags;
		this.saleTags = saleTags;
		this.clock = clock;
	}

	/** The ticket holds the tags of its lines now, in stock and of the line's item; those it held before are let go. */
	@Override
	@Transactional
	public void ticketHeld(SalesHeader ticket, List<LineTags> lines) {
		saleTags.deleteAll(saleTags.findBySalesHeader_Id(ticket.getId()));
		for (Kept kept : inStock(ticket, lines)) {
			saleTags.save(row(ticket, kept, false));
		}
	}

	/**
	 * Paid: the tags of its paid lines still in stock become SOLD with the ticket number, each kept with its line. A tag
	 * already sold (by another ticket meanwhile) is skipped with a warning.
	 */
	@Override
	@Transactional
	public void ticketCompleted(SalesHeader ticket, List<LineTags> lines) {
		saleTags.deleteAll(saleTags.findBySalesHeader_Id(ticket.getId()));
		LocalDateTime now = clock.get();
		for (Kept kept : inStock(ticket, lines)) {
			kept.tag.setStatus(RfidTagStatus.SOLD);
			kept.tag.setSoldAt(now);
			kept.tag.setTicketNumber(ticket.getSalesNumber());
			tags.save(kept.tag);
			saleTags.save(row(ticket, kept, true));
		}
	}

	@Override
	@Transactional
	public void ticketCancelled(SalesHeader ticket) {
		saleTags.deleteAll(saleTags.findBySalesHeader_Id(ticket.getId()));
	}

	/**
	 * Back in stock, per item of the ticket (2026-10-12): when every line of the item on the ticket, paid and free, is
	 * fully returned after this return, every tag of the item still sold; otherwise the tags read among the item's own,
	 * in the order read, at most the pieces of the item this return takes back. A tag not read stays SOLD: a failed or
	 * skipped read never blocks a return. (ReturnedLine.wholeRemaining is per line: a paid line returned whole while its
	 * free piece is kept is not the whole item.)
	 */
	@Override
	@Transactional
	public void returned(ReturnHeader ret, List<ReturnedLine> lines, List<String> epcsRead) {
		Set<String> read = new LinkedHashSet<>();
		for (String raw : epcsRead) {
			String epc = epcOrNull(raw);
			if (epc != null) {
				read.add(epc);
			}
		}
		SalesHeader ticket = null;
		Map<Long, List<ReturnLine>> byItem = new LinkedHashMap<>();
		for (ReturnedLine returned : lines) {
			ReturnLine line = returned.getLine();
			SalesLine sold = line.getOriginalSalesLine();
			if (sold == null || sold.getItem() == null || sold.getSalesHeader() == null) {
				continue;
			}
			ticket = sold.getSalesHeader();
			byItem.computeIfAbsent(sold.getItem().getId(), id -> new ArrayList<>()).add(line);
		}
		if (ticket == null) {
			return;
		}
		Map<Long, List<RfidSaleTag>> sold = saleTags.findBySalesHeader_Id(ticket.getId()).stream()
				.filter(row -> row.getSalesLine() != null && row.getReturnLine() == null)
				.collect(Collectors.groupingBy(row -> row.getItem().getId(), LinkedHashMap::new, Collectors.toList()));
		for (Map.Entry<Long, List<ReturnLine>> item : byItem.entrySet()) {
			List<RfidSaleTag> rows = sold.getOrDefault(item.getKey(), new ArrayList<>());
			if (rows.isEmpty()) {
				continue;
			}
			if (!itemFullyReturned(ticket.getId(), item.getKey())) {
				int most = item.getValue().stream().mapToInt(line -> pieces(line.getQuantity())).sum();
				Map<String, RfidSaleTag> own = rows.stream().collect(Collectors.toMap(RfidSaleTag::getEpc, Function.identity()));
				rows = read.stream().map(own::get).filter(java.util.Objects::nonNull).limit(most).collect(Collectors.toList());
			}
			for (RfidSaleTag row : rows) {
				read.remove(row.getEpc());
				row.setReturnLine(returnLineFor(row, item.getValue()));
				saleTags.save(row);
				backInStock(row.getEpc(), ticket);
			}
		}
		if (!read.isEmpty()) {
			log.warn("RFID return {}: tags read that are not sold on its lines returned, left as they are: {}",
					ret.getReturnNumber(), read);
		}
	}

	/** Every line of the item on the ticket (paid and free) is fully returned, this return included. */
	private boolean itemFullyReturned(Long ticketId, Long itemId) {
		List<SalesLine> lines = saleTags.linesOf(ticketId, itemId);
		return !lines.isEmpty() && lines.stream().allMatch(line -> line.getQuantity() == null
				|| saleTags.returnedQuantity(line.getId()).compareTo(line.getQuantity()) >= 0);
	}

	/** The return line of the row's own sales line (the paid line), else the first line of the item in this return. */
	private static ReturnLine returnLineFor(RfidSaleTag row, List<ReturnLine> lines) {
		return lines.stream()
				.filter(line -> line.getOriginalSalesLine().getId() != null
						&& line.getOriginalSalesLine().getId().equals(row.getSalesLine().getId()))
				.findFirst().orElse(lines.get(0));
	}

	/** A tag of this ticket that is SOLD with its number goes back IN_STOCK; any other state is left with a warning. */
	private void backInStock(String epc, SalesHeader ticket) {
		RfidTag tag = tags.findByEpc(epc).orElse(null);
		String number = ticket == null ? null : ticket.getSalesNumber();
		if (tag == null || tag.getStatus() != RfidTagStatus.SOLD || number == null
				|| !number.equals(tag.getTicketNumber())) {
			log.warn("RFID return of ticket {}: the tag {} is not sold by this ticket any more ({}), left as it is", number,
					epc, tag == null ? "no such tag" : tag.getStatus() + " " + tag.getTicketNumber());
			return;
		}
		tag.setStatus(RfidTagStatus.IN_STOCK);
		tag.setSoldAt(null);
		tag.setTicketNumber(null);
		tags.save(tag);
	}

	/** A tag kept for a line: in stock, of the line's item, once per ticket, at most the item's whole pieces on it. */
	private static final class Kept {
		final SalesLine line;
		final RfidTag tag;

		Kept(SalesLine line, RfidTag tag) {
			this.line = line;
			this.tag = tag;
		}
	}

	private List<Kept> inStock(SalesHeader ticket, List<LineTags> lines) {
		Map<String, LineTags> wanted = new LinkedHashMap<>();
		for (LineTags line : lines) {
			for (String raw : line.getEpcs()) {
				String epc = epcOrNull(raw);
				if (epc != null) {
					wanted.putIfAbsent(epc, line);
				}
			}
		}
		if (wanted.isEmpty()) {
			return new ArrayList<>();
		}
		Map<String, RfidTag> known = tags.findByEpcIn(wanted.keySet()).stream()
				.collect(Collectors.toMap(RfidTag::getEpc, Function.identity()));
		Map<Long, Integer> perItem = new HashMap<>();
		Map<Long, Integer> piecesOfItem = new HashMap<>();
		List<Kept> kept = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		for (Map.Entry<String, LineTags> entry : wanted.entrySet()) {
			String epc = entry.getKey();
			SalesLine line = entry.getValue().getLine();
			RfidTag tag = known.get(epc);
			String why = null;
			if (tag == null) {
				why = "unknown";
			} else if (tag.getStatus() != RfidTagStatus.IN_STOCK) {
				why = "already sold (ticket " + tag.getTicketNumber() + ")";
			} else if (tag.getItem() == null || line.getItem() == null
					|| !tag.getItem().getId().equals(line.getItem().getId())) {
				why = "of another item";
			} else if (perItem.getOrDefault(tag.getItem().getId(), 0) >= piecesOfItem
					.computeIfAbsent(tag.getItem().getId(), itemId -> piecesOnTicket(ticket, itemId, line))) {
				why = "more tags than pieces of the item on the ticket";
			}
			if (why != null) {
				skipped.add(epc + " " + why);
				continue;
			}
			perItem.merge(tag.getItem().getId(), 1, Integer::sum);
			kept.add(new Kept(line, tag));
		}
		if (!skipped.isEmpty()) {
			log.warn("RFID ticket {} ({}): tags skipped, the ticket goes through without them: {}",
					ticket.getSalesNumber(), ticket.getStatus(), skipped);
		}
		return kept;
	}

	/**
	 * The whole pieces of the item on the ticket: its paid line and its free line (2026-10-12: quantity + freeQuantity
	 * of the line the POS sent); a pending ticket's line holds all the pieces. The line given when the ticket has none
	 * saved (yet).
	 */
	private int piecesOnTicket(SalesHeader ticket, Long itemId, SalesLine line) {
		List<SalesLine> lines = ticket.getId() == null ? new ArrayList<>() : saleTags.linesOf(ticket.getId(), itemId);
		if (lines.isEmpty()) {
			return pieces(line.getQuantity());
		}
		return lines.stream().mapToInt(saved -> pieces(saved.getQuantity())).sum();
	}

	private static RfidSaleTag row(SalesHeader ticket, Kept kept, boolean withLine) {
		RfidSaleTag row = new RfidSaleTag();
		row.setSalesHeader(ticket);
		row.setItem(kept.tag.getItem());
		row.setEpc(kept.tag.getEpc());
		row.setSalesLine(withLine ? kept.line : null);
		return row;
	}

	/** The whole pieces of a quantity (a decimal quantity is not a piece count: its whole part). */
	private static int pieces(java.math.BigDecimal quantity) {
		return quantity == null || quantity.signum() <= 0 ? 0 : quantity.intValue();
	}

	private static String epcOrNull(String raw) {
		try {
			return RfidTagService.normaliseEpc(raw);
		} catch (RfidException notAnEpc) {
			log.warn("RFID: '{}' is not an EPC, skipped", raw);
			return null;
		}
	}
}
