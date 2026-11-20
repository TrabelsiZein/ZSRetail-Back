package com.digithink.zsretail.headoffice.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationEventStatus;
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.communication.repository.CommunicationEventRepository;
import com.digithink.zsretail.core.communication.service.CommunicationStates;
import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.link.dto.CommunicationEventAnswerDTO;
import com.digithink.zsretail.core.link.dto.CommunicationEventCopyDTO;
import com.digithink.zsretail.headoffice.model.Store;

import lombok.extern.log4j.Log4j2;

/**
 * 3.3.0, communication step 6: the events a store whose loyalty is the head office's sends up (POST
 * /ho/communication/events). With the module off here: taken false, nothing stored (the store pauses its push). Else
 * each event is stored once (by its dedup key; sent again: accepted, nothing written), with the calling store's code as
 * its origin and the store's occurred_at (the lifetime counts from it). An event that happened before the module was on
 * here (ACTIVE_SINCE) is stored SKIPPED: nothing older is ever messaged. The rest waits PENDING for the processor. The
 * receiver writes no event of its own: a store's enrolment has its store's MEMBER_CREATED event only.
 */
@Service
@ConditionalOnInstallation(Installation.HEAD_OFFICE)
@Log4j2
public class HoCommunicationReceiver {

	static final int MAX_BATCH = 500;
	public static final String BEFORE_ACTIVATION = "happened before the communication module was on at the head office";
	public static final String ALREADY_THERE = "already at the head office";

	private final CommunicationEventRepository events;
	private final ApplicationModeService mode;
	private final Optional<CommunicationStates> states;
	private final TransactionOperations transactions;

	@Autowired
	public HoCommunicationReceiver(CommunicationEventRepository events, ApplicationModeService mode,
			Optional<CommunicationStates> states, PlatformTransactionManager transactionManager) {
		this(events, mode, states, new TransactionTemplate(transactionManager));
	}

	/** With given transactions: used by the tests. */
	public HoCommunicationReceiver(CommunicationEventRepository events, ApplicationModeService mode,
			Optional<CommunicationStates> states, TransactionOperations transactions) {
		this.events = events;
		this.mode = mode;
		this.states = states;
		this.transactions = transactions;
	}

	public CommunicationEventAnswerDTO receive(Store store, List<CommunicationEventCopyDTO> copies) {
		if (!mode.isCommunicationEnabled() || !states.isPresent()) {
			return CommunicationEventAnswerDTO.notTaken();
		}
		List<CommunicationEventAnswerDTO.Result> results = new ArrayList<>();
		if (copies == null) {
			return new CommunicationEventAnswerDTO(true, results);
		}
		LocalDateTime activeSince = states.get().activeSince();
		int stored = 0;
		for (CommunicationEventCopyDTO copy : copies.subList(0, Math.min(copies.size(), MAX_BATCH))) {
			CommunicationEventAnswerDTO.Result result;
			try {
				result = transactions.execute(status -> receiveOne(store, copy, activeSince));
			} catch (RuntimeException e) {
				result = new CommunicationEventAnswerDTO.Result(copy == null ? null : copy.getDedupKey(), false,
						"not stored: " + e.getClass().getSimpleName());
			}
			if (result != null && result.isAccepted() && result.getMessage() == null) {
				stored++;
			}
			results.add(result);
		}
		if (stored > 0) {
			log.info("Head office: {} communication event(s) received from store '{}'", stored, store.getCode());
		}
		return new CommunicationEventAnswerDTO(true, results);
	}

	private CommunicationEventAnswerDTO.Result receiveOne(Store store, CommunicationEventCopyDTO copy,
			LocalDateTime activeSince) {
		if (copy == null || blank(copy.getDedupKey())) {
			return new CommunicationEventAnswerDTO.Result(null, false, "dedupKey is required");
		}
		String key = copy.getDedupKey().trim();
		if (blank(copy.getEventType()) || blank(copy.getSourceType()) || copy.getOccurredAt() == null) {
			return new CommunicationEventAnswerDTO.Result(key, false, "eventType, sourceType and occurredAt are required");
		}
		if (events.findByDedupKey(key).isPresent()) {
			return new CommunicationEventAnswerDTO.Result(key, true, ALREADY_THERE);
		}
		String by = HoLoyaltyReceiver.STORE_USER_PREFIX + store.getCode();
		CommunicationEvent event = new CommunicationEvent();
		event.setEventType(copy.getEventType().trim());
		event.setSourceType(copy.getSourceType().trim());
		event.setSourceId(copy.getSourceId());
		event.setDedupKey(key);
		event.setRecipientType(blank(copy.getRecipientType()) ? CommunicationTypes.LOYALTY_MEMBER : copy.getRecipientType());
		event.setRecipientRef(copy.getRecipientRef());
		event.setPayload(copy.getPayload());
		event.setOriginStoreCode(store.getCode());
		event.setOccurredAt(copy.getOccurredAt());
		event.setCreatedBy(by);
		event.setUpdatedBy(by);
		if (activeSince != null && copy.getOccurredAt().isBefore(activeSince)) {
			event.setStatus(CommunicationEventStatus.SKIPPED);
			event.setLastError(BEFORE_ACTIVATION);
			event.setProcessedAt(LocalDateTime.now());
		} else {
			event.setStatus(CommunicationEventStatus.PENDING);
		}
		events.save(event);
		return new CommunicationEventAnswerDTO.Result(key, true, null);
	}

	private static boolean blank(String text) {
		return text == null || text.trim().isEmpty();
	}
}
