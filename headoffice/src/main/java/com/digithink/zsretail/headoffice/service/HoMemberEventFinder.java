package com.digithink.zsretail.headoffice.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.communication.repository.CommunicationEventRepository;
import com.digithink.zsretail.core.communication.scheduler.CommunicationTask;
import com.digithink.zsretail.core.communication.service.CommunicationStates;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.service.CompanyInformationService;

/**
 * 3.3.0, communication step 6: MEMBER_CREATED for a member created on the head office's own pages, the one source of
 * its welcome. A member a store enrolled (its author STORE:&lt;code&gt;, whatever the receiver's outcome: CREATED, EXISTS
 * or MERGED) has its store's event only, pushed up. Picked: active members created from ACTIVE_SINCE here, without an
 * event of their card (dedup key MEMBER_CREATED:&lt;card&gt;, the store's key too: never two welcomes). Runs in the
 * communication cycle of a head office with the module on (the scheduler exists only then).
 */
@Component
@ConditionalOnInstallation(Installation.HEAD_OFFICE)
@Order(2)
public class HoMemberEventFinder implements CommunicationTask {

	public static final String CODE = "MEMBER_EVENTS";
	static final int PAGE_SIZE = 200;

	@PersistenceContext
	private EntityManager entityManager;

	private final CommunicationEventRepository events;
	private final Optional<CommunicationStates> states;
	private final CompanyInformationService companyInformation;

	public HoMemberEventFinder(CommunicationEventRepository events, Optional<CommunicationStates> states,
			CompanyInformationService companyInformation) {
		this.events = events;
		this.states = states;
		this.companyInformation = companyInformation;
	}

	@Override
	public String getCode() {
		return CODE;
	}

	@Override
	@Transactional
	public String run(Instant deadline) {
		if (!states.isPresent()) {
			return null;
		}
		LocalDateTime since = states.get().activeSince();
		List<LoyaltyMember> members = entityManager.createQuery("select m from LoyaltyMember m where m.active = true"
				+ " and m.createdAt >= :since and (m.createdBy is null or m.createdBy not like :store)"
				+ " and not exists (select e.id from CommunicationEvent e where e.dedupKey = concat(:prefix, m.cardNumber))"
				+ " order by m.id", LoyaltyMember.class).setParameter("since", since)
				.setParameter("store", HoLoyaltyReceiver.STORE_USER_PREFIX + "%")
				.setParameter("prefix", CommunicationTypes.MEMBER_CREATED + ":").setMaxResults(PAGE_SIZE).getResultList();
		if (members.isEmpty()) {
			return null;
		}
		CompanyInformationDTO company = companyInformation.get();
		for (LoyaltyMember member : members) {
			events.save(event(member, company == null ? null : company.getCompanyName()));
		}
		return members.size() + " member event(s) written";
	}

	static CommunicationEvent event(LoyaltyMember member, String companyName) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("cardNumber", member.getCardNumber());
		payload.put("firstName", member.getFirstName());
		payload.put("lastName", member.getLastName());
		payload.put("pointsBalance", member.getLoyaltyPoints() == null ? 0 : member.getLoyaltyPoints());
		payload.put("enrolledAt", member.getCreatedAt());
		payload.put("storeCode", null);
		payload.put("storeName", null);
		payload.put("companyName", companyName);
		CommunicationEvent event = new CommunicationEvent();
		event.setEventType(CommunicationTypes.MEMBER_CREATED);
		event.setSourceType(CommunicationTypes.LOYALTY_MEMBER);
		event.setSourceId(member.getId());
		event.setDedupKey(CommunicationTypes.MEMBER_CREATED + ":" + member.getCardNumber());
		event.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		event.setRecipientRef(member.getCardNumber());
		event.setPayload(CommunicationTypes.json(payload));
		event.setOccurredAt(member.getCreatedAt());
		event.setCreatedBy("System");
		event.setUpdatedBy("System");
		return event;
	}
}
