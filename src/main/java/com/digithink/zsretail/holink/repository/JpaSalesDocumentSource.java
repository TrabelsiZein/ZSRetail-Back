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
import com.digithink.zsretail.holink.service.SalesCopyMapper;
import com.digithink.zsretail.holink.service.SalesDocumentSource;
import com.digithink.zsretail.repository.CashierSessionRepository;
import com.digithink.zsretail.repository.PaymentRepository;
import com.digithink.zsretail.repository.ReturnHeaderRepository;
import com.digithink.zsretail.repository.ReturnLineRepository;
import com.digithink.zsretail.repository.SalesHeaderRepository;
import com.digithink.zsretail.repository.SalesLineRepository;
import com.digithink.zsretail.repository.SessionCashCountRepository;

/**
 * The store's documents read for the sales copies (tasks 2.1, 2.4): SalesHeader, ReturnHeader, CashierSession and
 * their lines. Read only: the search is a JPQL query through the shared entity manager, the copies are built from the
 * existing repositories' find methods; no selling service and no existing repository is changed. Called in
 * transactions with a timeout (read only for the copies), so a row locked by a long ERP export cannot hold the link
 * thread.
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

	private final SalesHeaderRepository salesHeaders;
	private final SalesLineRepository salesLines;
	private final PaymentRepository payments;
	private final ReturnHeaderRepository returnHeaders;
	private final ReturnLineRepository returnLines;
	private final CashierSessionRepository sessions;
	private final SessionCashCountRepository cashCounts;

	public JpaSalesDocumentSource(SalesHeaderRepository salesHeaders, SalesLineRepository salesLines,
			PaymentRepository payments, ReturnHeaderRepository returnHeaders, ReturnLineRepository returnLines,
			CashierSessionRepository sessions, SessionCashCountRepository cashCounts) {
		this.salesHeaders = salesHeaders;
		this.salesLines = salesLines;
		this.payments = payments;
		this.returnHeaders = returnHeaders;
		this.returnLines = returnLines;
		this.sessions = sessions;
		this.cashCounts = cashCounts;
	}

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

	@Override
	public Object loadCopy(SalesCopyType type, Long localId) {
		switch (type) {
			case TICKET:
				return salesHeaders.findById(localId)
						.map(header -> SalesCopyMapper.ticket(header, salesLines.findBySalesHeader(header),
								payments.findBySalesHeader(header)))
						.orElse(null);
			case RETURN:
				return returnHeaders.findById(localId)
						.map(header -> SalesCopyMapper.returnCopy(header, returnLines.findByReturnHeader(header)))
						.orElse(null);
			default:
				return sessions.findById(localId)
						.map(session -> SalesCopyMapper.session(session, cashCounts.findByCashierSession(session)))
						.orElse(null);
		}
	}
}
