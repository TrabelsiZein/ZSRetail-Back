package com.digithink.zsretail.holink.repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeSalesPush;
import com.digithink.zsretail.holink.dto.SalesDocumentRef;
import com.digithink.zsretail.holink.enumeration.SalesCopyType;
import com.digithink.zsretail.holink.service.SalesDocumentSource;

/**
 * The store's documents read with JPQL for the sales copies (task 2.1): SalesHeader, ReturnHeader, CashierSession.
 * Read only, through the shared entity manager; no selling service and no existing repository is changed. Called in
 * transactions with a timeout (see SalesCopyFinder), so a row locked by a long ERP export cannot hold the link thread.
 */
@Repository
@ConditionalOnHeadOfficeSalesPush
public class JpaSalesDocumentSource implements SalesDocumentSource {

	private static final Map<SalesCopyType, String> CHANGED = new EnumMap<>(SalesCopyType.class);

	static {
		CHANGED.put(SalesCopyType.TICKET, changedQuery("SalesHeader", "salesNumber", "salesDate"));
		CHANGED.put(SalesCopyType.RETURN, changedQuery("ReturnHeader", "returnNumber", "returnDate"));
		CHANGED.put(SalesCopyType.SESSION, changedQuery("CashierSession", "sessionNumber", "closedAt"));
	}

	@PersistenceContext
	private EntityManager entityManager;

	/** Keyset query on (change time, id); the change time is updated_at, or the document date when it is empty. */
	static String changedQuery(String entity, String numberField, String dateField) {
		String changedAt = "coalesce(d.updatedAt, d." + dateField + ")";
		return "select d.id, d." + numberField + ", d." + dateField + ", d.status, " + changedAt + " from " + entity + " d"
				+ " where d." + dateField + " >= :from and " + changedAt + " <= :until"
				+ " and (" + changedAt + " > :afterChangedAt or (" + changedAt + " = :afterChangedAt and d.id > :afterId))"
				+ " order by " + changedAt + ", d.id";
	}

	@Override
	public List<SalesDocumentRef> findChanged(SalesCopyType type, LocalDateTime from, LocalDateTime afterChangedAt,
			long afterId, LocalDateTime until, int limit) {
		List<Object[]> rows = entityManager.createQuery(CHANGED.get(type), Object[].class)
				.setParameter("from", from)
				.setParameter("until", until)
				.setParameter("afterChangedAt", afterChangedAt)
				.setParameter("afterId", afterId)
				.setMaxResults(limit)
				.getResultList();
		List<SalesDocumentRef> refs = new ArrayList<>(rows.size());
		for (Object[] row : rows) {
			Enum<?> status = (Enum<?>) row[3];
			refs.add(new SalesDocumentRef(type, (Long) row[0], (String) row[1], (LocalDateTime) row[2],
					status == null ? null : status.name(), (LocalDateTime) row[4]));
		}
		return refs;
	}
}
