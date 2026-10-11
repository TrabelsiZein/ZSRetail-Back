package com.digithink.zsretail.store.rfid.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
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
 * payment its tags still in stock become SOLD with the ticket number; a return puts back in stock the tags of a line
 * returned whole, and of a line returned in part the tags read among its own. A tag sent by the POS that is not (or no
 * longer) a piece in stock of the line's item is skipped with a warning, never a refusal: the ticket goes through.
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
	 * Back in stock: every tag of a line returned whole (all that was left of it); of a line returned in part, the tags
	 * read among its own, in the order read, at most the quantity returned. A tag not read stays SOLD: a failed or skipped read never blocks
	 * a return.
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
		for (ReturnedLine returned : lines) {
			ReturnLine line = returned.getLine();
			SalesLine sold = line.getOriginalSalesLine();
			if (sold == null || sold.getId() == null) {
				continue;
			}
			List<RfidSaleTag> rows = saleTags.findBySalesLine_IdAndReturnLineIsNull(sold.getId());
			if (!returned.isWholeRemaining()) {
				int most = pieces(line.getQuantity());
				Map<String, RfidSaleTag> own = rows.stream().collect(Collectors.toMap(RfidSaleTag::getEpc, Function.identity()));
				rows = read.stream().map(own::get).filter(java.util.Objects::nonNull).limit(most).collect(Collectors.toList());
			}
			for (RfidSaleTag row : rows) {
				read.remove(row.getEpc());
				row.setReturnLine(line);
				saleTags.save(row);
				backInStock(row.getEpc(), sold.getSalesHeader());
			}
		}
		if (!read.isEmpty()) {
			log.warn("RFID return {}: tags read that are not sold on its lines returned, left as they are: {}",
					ret.getReturnNumber(), read);
		}
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

	/** A tag kept for a line: in stock, of the line's item, once per ticket, at most the line's whole pieces. */
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
		Map<SalesLine, Integer> perLine = new IdentityHashMap<>();
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
			} else if (perLine.getOrDefault(line, 0) >= pieces(line.getQuantity())) {
				why = "more tags than pieces on the line";
			}
			if (why != null) {
				skipped.add(epc + " " + why);
				continue;
			}
			perLine.merge(line, 1, Integer::sum);
			kept.add(new Kept(line, tag));
		}
		if (!skipped.isEmpty()) {
			log.warn("RFID ticket {} ({}): tags skipped, the ticket goes through without them: {}",
					ticket.getSalesNumber(), ticket.getStatus(), skipped);
		}
		return kept;
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
