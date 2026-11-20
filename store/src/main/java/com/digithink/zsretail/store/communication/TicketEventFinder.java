package com.digithink.zsretail.store.communication;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.communication.model.CommunicationState;
import com.digithink.zsretail.core.communication.repository.CommunicationEventRepository;
import com.digithink.zsretail.core.communication.scheduler.CommunicationTask;
import com.digithink.zsretail.core.communication.service.CommunicationStates;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.store.communication.StoreEventSource.StoreLabel;
import com.digithink.zsretail.store.communication.StoreEventSource.TicketRow;

import lombok.extern.log4j.Log4j2;

/**
 * 3.3.0, communication module, step 2: one TICKET_VALIDATED event per ticket COMPLETED with a loyalty member, completed
 * after the activation instant. Found by a query (nothing hooked into the selling services), like the sales copies: a
 * cursor on (updated_at, id), the tickets changed less than {@link #SETTLE_DELAY} ago read at a later cycle (updated_at
 * is written before the sale's transaction commits). A ticket read again (the ERP export, the head office push or an
 * invoice moving its updated_at) already has its event, found by its source: never a second one. A ticket with 0 points
 * still gives one. The event stays PENDING. See docs/modules/communication.md.
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION)
@Order(1)
@Log4j2
public class TicketEventFinder implements CommunicationTask {

	public static final String CODE = "TICKET_EVENTS";
	static final String CURSOR = "TICKET_CURSOR";
	static final Duration SETTLE_DELAY = Duration.ofSeconds(30);
	static final int PAGE_SIZE = 200;
	static final int TIMEOUT_SECONDS = 15;

	private final StoreEventSource source;
	private final CommunicationEventRepository events;
	private final CommunicationStates states;
	private final TransactionOperations transactions;
	private final Supplier<LocalDateTime> clock;
	private boolean noStoreCodeLogged;

	@Autowired
	public TicketEventFinder(StoreEventSource source, CommunicationEventRepository events, CommunicationStates states,
			PlatformTransactionManager transactionManager) {
		this(source, events, states, timed(transactionManager), LocalDateTime::now);
	}

	/** With given transactions and clock: used by the tests. */
	TicketEventFinder(StoreEventSource source, CommunicationEventRepository events, CommunicationStates states,
			TransactionOperations transactions, Supplier<LocalDateTime> clock) {
		this.source = source;
		this.events = events;
		this.states = states;
		this.transactions = transactions;
		this.clock = clock;
	}

	static TransactionTemplate timed(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	@Override
	public String getCode() {
		return CODE;
	}

	@Override
	public String run(Instant deadline) {
		StoreLabel label = source.storeLabel();
		if (label.getCode() == null) {
			if (!noStoreCodeLogged) {
				log.warn("Communication: DEFAULT_LOCATION is empty in the general setup: no ticket event (the key and the"
						+ " messages carry the store code)");
				noStoreCodeLogged = true;
			}
			return null;
		}
		noStoreCodeLogged = false;
		int written = 0;
		int[] page;
		do {
			page = transactions.execute(status -> page(label));
			written += page[1];
		} while (page[0] == PAGE_SIZE && Instant.now().isBefore(deadline));
		return written == 0 ? null : written + " ticket event(s) written";
	}

	/** One page: {read, written}. The cursor moves to the last ticket read. */
	private int[] page(StoreLabel label) {
		LocalDateTime activeSince = states.activeSince();
		CommunicationState cursor = states.cursor(CURSOR, activeSince);
		List<TicketRow> tickets = source.completedTicketsWithMember(activeSince, cursor.getTimeValue(),
				cursor.getIdValue(), clock.get().minus(SETTLE_DELAY), PAGE_SIZE);
		if (tickets.isEmpty()) {
			return new int[] { 0, 0 };
		}
		List<Long> ids = new ArrayList<>(tickets.size());
		tickets.forEach(t -> ids.add(t.getId()));
		Set<Long> known = new HashSet<>(events.findSourceIds(CommunicationTypes.SALES_HEADER, ids));
		List<CommunicationEvent> toSave = new ArrayList<>();
		for (TicketRow ticket : tickets) {
			if (known.add(ticket.getId())) {
				toSave.add(event(ticket, label));
			}
		}
		if (!toSave.isEmpty()) {
			events.saveAll(toSave);
		}
		TicketRow last = tickets.get(tickets.size() - 1);
		cursor.setTimeValue(last.getChangedAt());
		cursor.setIdValue(last.getId());
		states.save(cursor);
		return new int[] { tickets.size(), toSave.size() };
	}

	private CommunicationEvent event(TicketRow ticket, StoreLabel label) {
		Integer balance = source.balanceAfterSale(ticket.getId());
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("ticketNumber", ticket.getSalesNumber());
		payload.put("ticketDate", ticket.getCompletedDate());
		payload.put("total", ticket.getTotalAmount());
		payload.put("pointsEarned", orZero(ticket.getPointsEarned()));
		payload.put("pointsSpent", orZero(ticket.getPointsRedeemed()));
		payload.put("pointsDeduction", ticket.getDeductionAmount() == null ? 0.0 : ticket.getDeductionAmount());
		// The sale's own last loyalty row; a sale without one (0 points, nothing spent): the member's balance now
		payload.put("balanceAfter", balance != null ? balance : orZero(ticket.getMemberPoints()));
		payload.put("cardNumber", ticket.getCardNumber());
		payload.put("firstName", ticket.getFirstName());
		payload.put("lastName", ticket.getLastName());
		payload.put("storeCode", label.getCode());
		payload.put("storeName", label.getName());
		payload.put("companyName", label.getCompanyName());

		CommunicationEvent event = new CommunicationEvent();
		event.setEventType(CommunicationTypes.TICKET_VALIDATED);
		event.setSourceType(CommunicationTypes.SALES_HEADER);
		event.setSourceId(ticket.getId());
		event.setDedupKey(CommunicationTypes.TICKET_VALIDATED + ":" + label.getCode() + ":" + ticket.getId());
		event.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		event.setRecipientRef(ticket.getCardNumber());
		event.setPayload(CommunicationTypes.json(payload));
		event.setOriginStoreCode(label.getCode());
		event.setOccurredAt(ticket.getCompletedDate());
		return event;
	}

	private static int orZero(Integer value) {
		return value == null ? 0 : value;
	}
}
