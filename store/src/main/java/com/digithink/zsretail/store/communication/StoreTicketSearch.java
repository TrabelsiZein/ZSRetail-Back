package com.digithink.zsretail.store.communication;

import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.communication.service.CommunicationTicketSearch;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;

/**
 * 3.3.0, communication step 4: the store's tickets whose number contains a text, for the search of the Communication
 * page (a message or an event found by its ticket number, through the event's source_id). JPQL, read only.
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION_SENDS_HERE)
public class StoreTicketSearch implements CommunicationTicketSearch {

	@PersistenceContext
	private EntityManager entityManager;

	@Override
	@Transactional(readOnly = true)
	public List<Long> ticketIds(String text, int max) {
		String like = "%" + text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
		return entityManager.createQuery("select s.id from SalesHeader s where s.salesNumber like :like escape '\\'"
				+ " order by s.id desc", Long.class).setParameter("like", like).setMaxResults(max).getResultList();
	}
}
