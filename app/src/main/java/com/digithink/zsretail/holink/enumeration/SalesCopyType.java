package com.digithink.zsretail.holink.enumeration;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import com.digithink.zsretail.model.enumeration.SessionStatus;
import com.digithink.zsretail.model.enumeration.TransactionStatus;

/**
 * A kind of document copied to the head office (task 2.1), with the statuses that mean "finished". Only a finished
 * document is sent the first time; a document already sent is sent again after any later change, whatever its status
 * then (e.g. a ticket cancelled after it was sent).
 */
public enum SalesCopyType {

	/**
	 * SalesHeader. COMPLETED; REFUNDED is never written by the code today, but a refunded ticket was completed first.
	 * PENDING (parked) and CANCELLED (a parked ticket cancelled, or emptied by a split) are not finished.
	 */
	TICKET(TransactionStatus.COMPLETED, TransactionStatus.REFUNDED),

	/** ReturnHeader. COMPLETED is the only status a return gets: it is validated when it is created. */
	RETURN(TransactionStatus.COMPLETED),

	/** CashierSession. CLOSED (counted by the cashier) and TERMINATED (verified by the responsible); not OPENED. */
	SESSION(SessionStatus.CLOSED, SessionStatus.TERMINATED);

	private final Set<String> finishedStatuses;

	SalesCopyType(Enum<?>... finished) {
		Set<String> names = new LinkedHashSet<>();
		for (Enum<?> status : finished) {
			names.add(status.name());
		}
		this.finishedStatuses = Collections.unmodifiableSet(names);
	}

	/** Status names (of TransactionStatus or SessionStatus) that mean the document is finished. */
	public Set<String> getFinishedStatuses() {
		return finishedStatuses;
	}

	public boolean isFinished(String status) {
		return status != null && finishedStatuses.contains(status);
	}
}
