package com.digithink.zsretail.store.repository;

import java.util.Optional;

import com.digithink.zsretail.core.model.AcceptedTicket;
import com.digithink.zsretail.core.model.SalesHeader;
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
}
