package com.digithink.zsretail.store.communication;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.communication.repository.CommunicationEventRepository;
import com.digithink.zsretail.core.communication.scheduler.CommunicationTask;
import com.digithink.zsretail.core.communication.service.CommunicationStates;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.store.communication.StoreEventSource.MemberRow;
import com.digithink.zsretail.store.communication.StoreEventSource.StoreLabel;

/**
 * 3.3.0, communication module, step 2: one MEMBER_CREATED event per loyalty member enrolled at this store after the
 * activation instant. Found by a query (nothing hooked into LoyaltyService): the active members enrolled here that
 * have no event yet (loyalty LOCAL: made here; loyalty owned by the head office: with a hol_loyalty_member_copy row
 * that is not a merge, never a copy received from the head office). A card the head office merges after its event was
 * written keeps its event: the head office, which handles those events, knows the alias. The event stays PENDING. See
 * docs/modules/communication.md.
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION)
@Order(2)
public class MemberEventFinder implements CommunicationTask {

	public static final String CODE = "MEMBER_EVENTS";
	static final int PAGE_SIZE = 200;

	private final StoreEventSource source;
	private final CommunicationEventRepository events;
	private final CommunicationStates states;
	private final TransactionOperations transactions;
	private final boolean loyaltyFromHeadOffice;

	@Autowired
	public MemberEventFinder(StoreEventSource source, CommunicationEventRepository events, CommunicationStates states,
			PlatformTransactionManager transactionManager, Environment environment) {
		this(source, events, states, TicketEventFinder.timed(transactionManager),
				Installation.LOYALTY_FROM_HEAD_OFFICE.isTrue(environment));
	}

	/** With given transactions and loyalty mode: used by the tests. */
	MemberEventFinder(StoreEventSource source, CommunicationEventRepository events, CommunicationStates states,
			TransactionOperations transactions, boolean loyaltyFromHeadOffice) {
		this.source = source;
		this.events = events;
		this.states = states;
		this.transactions = transactions;
		this.loyaltyFromHeadOffice = loyaltyFromHeadOffice;
	}

	@Override
	public String getCode() {
		return CODE;
	}

	@Override
	public String run(Instant deadline) {
		StoreLabel label = source.storeLabel();
		if (label.getCode() == null) {
			return null; // DEFAULT_LOCATION empty (TicketEventFinder says it once): the members wait, nothing is lost
		}
		int written = 0;
		int page;
		do {
			page = transactions.execute(status -> page(label));
			written += page;
		} while (page == PAGE_SIZE && Instant.now().isBefore(deadline));
		return written == 0 ? null : written + " member event(s) written";
	}

	/** One page of members without an event: each gets its event. */
	private int page(StoreLabel label) {
		List<MemberRow> members = source.membersEnrolledHere(states.activeSince(), loyaltyFromHeadOffice, PAGE_SIZE);
		List<CommunicationEvent> toSave = new ArrayList<>(members.size());
		for (MemberRow member : members) {
			toSave.add(event(member, label));
		}
		if (!toSave.isEmpty()) {
			events.saveAll(toSave);
		}
		return members.size();
	}

	private static CommunicationEvent event(MemberRow member, StoreLabel label) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("cardNumber", member.getCardNumber());
		payload.put("firstName", member.getFirstName());
		payload.put("lastName", member.getLastName());
		payload.put("pointsBalance", member.getPoints() == null ? 0 : member.getPoints());
		payload.put("enrolledAt", member.getCreatedAt());
		payload.put("storeCode", label.getCode());
		payload.put("storeName", label.getName());
		payload.put("companyName", label.getCompanyName());

		CommunicationEvent event = new CommunicationEvent();
		event.setEventType(CommunicationTypes.MEMBER_CREATED);
		event.setSourceType(CommunicationTypes.LOYALTY_MEMBER);
		event.setSourceId(member.getId());
		event.setDedupKey(CommunicationTypes.MEMBER_CREATED + ":" + member.getCardNumber());
		event.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		event.setRecipientRef(member.getCardNumber());
		event.setPayload(CommunicationTypes.json(payload));
		event.setOriginStoreCode(label.getCode());
		event.setOccurredAt(member.getCreatedAt());
		return event;
	}
}
