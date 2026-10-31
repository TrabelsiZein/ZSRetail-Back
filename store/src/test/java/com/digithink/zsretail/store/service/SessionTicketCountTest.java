package com.digithink.zsretail.store.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.inject;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;

/**
 * 3.1.1: the till's count of the tickets of its session (the right rail, catalogue hidden): the sales completed, the
 * ones refunded since included; never a pending, a cancelled ticket or another session's.
 */
class SessionTicketCountTest {

	private final List<SalesHeader> sales = new ArrayList<>();

	@Test
	@DisplayName("A session's tickets: completed and refunded counted, pending and cancelled not, another session's not")
	void countsCompletedAndRefundedOfTheSession() {
		CashierSession mine = session(1L);
		CashierSession other = session(2L);
		sale(mine, TransactionStatus.COMPLETED);
		sale(mine, TransactionStatus.COMPLETED);
		sale(mine, TransactionStatus.REFUNDED);
		sale(mine, TransactionStatus.PENDING);
		sale(mine, TransactionStatus.CANCELLED);
		sale(other, TransactionStatus.COMPLETED);

		CashierSessionService service = new CashierSessionService();
		inject(service, "salesHeaderRepository", proxy(SalesHeaderRepository.class, (m, a) -> {
			if (!"countByCashierSessionAndStatusIn".equals(m)) return UNHANDLED;
			Collection<?> statuses = (Collection<?>) a[1];
			return sales.stream().filter(s -> s.getCashierSession() == a[0] && statuses.contains(s.getStatus())).count();
		}));

		assertEquals(3L, service.countSessionTickets(mine));
		assertEquals(1L, service.countSessionTickets(other));
		assertEquals(0L, service.countSessionTickets(session(3L)));
	}

	private static CashierSession session(Long id) {
		CashierSession session = new CashierSession();
		session.setId(id);
		return session;
	}

	private void sale(CashierSession session, TransactionStatus status) {
		SalesHeader sale = new SalesHeader();
		sale.setCashierSession(session);
		sale.setStatus(status);
		sales.add(sale);
	}
}
