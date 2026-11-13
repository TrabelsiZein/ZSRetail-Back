package com.digithink.zsretail.store.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.AcceptedTicket;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.model.ScannedTicketPayment;

/** 3.2.0, step 4: the scanned tickets that paid sales. */
public interface ScannedTicketPaymentRepository extends _BaseRepository<ScannedTicketPayment, Long> {

	/** The sale that holds this barcode now (at most one: the filtered unique index on live_barcode). */
	Optional<ScannedTicketPayment> findFirstByLiveBarcode(String liveBarcode);

	/** How many payments an accepted ticket made (such a line is never deleted, only switched off). */
	long countByAcceptedTicket(AcceptedTicket acceptedTicket);

	/** The sale was paid, at least in part, with scanned tickets (a return of it is never refunded in cash). */
	boolean existsByPayment_SalesHeader(SalesHeader salesHeader);

	/**
	 * Step 6: the scanned tickets of a session's sales (statuses excluded as the session's payments: PENDING, CANCELLED),
	 * their payment and method fetched: the closing's expected count and face total, the tickets X / Z.
	 */
	@Query("SELECT t FROM ScannedTicketPayment t JOIN FETCH t.payment p JOIN FETCH p.paymentMethod JOIN p.salesHeader sh"
			+ " WHERE sh.cashierSession = :session AND sh.status NOT IN :excludedStatuses ORDER BY t.id")
	List<ScannedTicketPayment> findByCashierSession(@Param("session") CashierSession session,
			@Param("excludedStatuses") List<TransactionStatus> excludedStatuses);
}
