package com.digithink.zsretail.store.communication;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.service.CampaignStoreNames;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.link.dto.LoyaltyMemberResultDTO;
import com.digithink.zsretail.core.model.Location;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.LocationRepository;
import com.digithink.zsretail.core.service.CompanyInformationService;
import com.digithink.zsretail.core.service.GeneralSetupService;

/**
 * 3.3.0, communication module, step 2: the store's finders' queries (JPQL, read only). See {@link StoreEventSource}.
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION)
public class JpaStoreEventSource implements StoreEventSource, CampaignStoreNames {

	static final String STORE_CODE_SETTING = "DEFAULT_LOCATION";

	/** Keyset on (updated_at, id): a parked ticket completed later has an old id and a new updated_at, so it is read. */
	static final String TICKETS = "select s.id, s.updatedAt, s.salesNumber, s.salesDate, s.completedDate, s.totalAmount,"
			+ " s.loyaltyPointsEarned, s.loyaltyPointsRedeemed, s.loyaltyDeductionAmount, m.cardNumber, m.firstName,"
			+ " m.lastName, m.loyaltyPoints from SalesHeader s join s.loyaltyMember m"
			+ " where s.status = :completed and s.completedDate >= :from and s.updatedAt <= :until"
			+ " and (s.updatedAt > :afterChangedAt or (s.updatedAt = :afterChangedAt and s.id > :afterId))"
			+ " order by s.updatedAt, s.id";

	static final String BALANCE_AFTER = "select t.balanceAfter from LoyaltyTransaction t where t.salesHeader.id = :id"
			+ " order by t.id desc";

	private static final String MEMBERS = "select m.id, m.cardNumber, m.firstName, m.lastName, m.loyaltyPoints,"
			+ " m.createdAt from LoyaltyMember m where m.createdAt >= :from and m.active = true and %s"
			+ " and not exists (select e.id from CommunicationEvent e where e.sourceType = :source and e.sourceId = m.id)"
			+ " order by m.id";

	/** Loyalty LOCAL: every member made here; a card of the network register left from before is not. */
	static final String MEMBERS_LOCAL = String.format(MEMBERS, "(m.origin is null or m.origin <> :headOffice)");

	/** Loyalty owned by the head office: enrolled here (a copy row, written with the member), not merged. */
	static final String MEMBERS_FROM_HEAD_OFFICE = String.format(MEMBERS, "exists (select c.id from LoyaltyMemberCopy c"
			+ " where c.memberId = m.id and (c.outcome is null or c.outcome <> :merged))");

	@PersistenceContext
	private EntityManager entityManager;

	private final GeneralSetupService generalSetup;
	private final LocationRepository locations;
	private final CompanyInformationService companyInformation;

	@Autowired
	public JpaStoreEventSource(GeneralSetupService generalSetup, LocationRepository locations,
			CompanyInformationService companyInformation) {
		this.generalSetup = generalSetup;
		this.locations = locations;
		this.companyInformation = companyInformation;
	}

	/** With a given entity manager and no store label: used by the query tests. */
	JpaStoreEventSource(EntityManager entityManager) {
		this(null, null, null);
		this.entityManager = entityManager;
	}

	@Override
	public List<TicketRow> completedTicketsWithMember(LocalDateTime completedFrom, LocalDateTime afterChangedAt,
			long afterId, LocalDateTime until, int limit) {
		List<Object[]> rows = entityManager.createQuery(TICKETS, Object[].class)
				.setParameter("completed", TransactionStatus.COMPLETED)
				.setParameter("from", completedFrom)
				.setParameter("until", until)
				.setParameter("afterChangedAt", afterChangedAt)
				.setParameter("afterId", afterId)
				.setMaxResults(limit)
				.getResultList();
		List<TicketRow> tickets = new ArrayList<>(rows.size());
		for (Object[] r : rows) {
			tickets.add(new TicketRow((Long) r[0], (LocalDateTime) r[1], (String) r[2], (LocalDateTime) r[3],
					(LocalDateTime) r[4], (Double) r[5], (Integer) r[6], (Integer) r[7], (Double) r[8], (String) r[9],
					(String) r[10], (String) r[11], (Integer) r[12]));
		}
		return tickets;
	}

	@Override
	public Integer balanceAfterSale(long salesHeaderId) {
		List<Integer> balances = entityManager.createQuery(BALANCE_AFTER, Integer.class).setParameter("id", salesHeaderId)
				.setMaxResults(1).getResultList();
		return balances.isEmpty() ? null : balances.get(0);
	}

	@Override
	public List<MemberRow> membersEnrolledHere(LocalDateTime createdFrom, boolean loyaltyFromHeadOffice, int limit) {
		javax.persistence.TypedQuery<Object[]> query = entityManager
				.createQuery(loyaltyFromHeadOffice ? MEMBERS_FROM_HEAD_OFFICE : MEMBERS_LOCAL, Object[].class)
				.setParameter("from", createdFrom)
				.setParameter("source", CommunicationTypes.LOYALTY_MEMBER);
		if (loyaltyFromHeadOffice) {
			query.setParameter("merged", LoyaltyMemberResultDTO.MERGED);
		} else {
			query.setParameter("headOffice", RecordOrigin.HEAD_OFFICE);
		}
		List<MemberRow> members = new ArrayList<>();
		for (Object[] r : query.setMaxResults(limit).getResultList()) {
			members.add(new MemberRow((Long) r[0], (String) r[1], (String) r[2], (String) r[3], (Integer) r[4],
					(LocalDateTime) r[5]));
		}
		return members;
	}

	@Override
	public StoreLabel storeLabel() {
		String value = generalSetup.findValueByCode(STORE_CODE_SETTING);
		String code = value == null || value.trim().isEmpty() ? null : value.trim();
		String name = code == null ? null : locations.findByLocationCode(code).map(Location::getName).orElse(null);
		CompanyInformationDTO company = companyInformation.get();
		return new StoreLabel(code, name, company == null ? null : company.getCompanyName());
	}

	/** Step 7: a store's campaign shows its own name ({storeName}), read once for a batch. */
	@Override
	public java.util.function.BiFunction<String, String, String> resolver() {
		String own = storeLabel().getName();
		return (createdBy, card) -> own;
	}
}
