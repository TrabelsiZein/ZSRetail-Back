package com.digithink.zsretail.store.cashmovement.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.store.cashmovement.model.ClosingWithdrawal;

/** 3.2.0, the opening amount: the withdrawals of the verifications, and the most recent closed session of a drawer. */
public interface ClosingWithdrawalRepository extends JpaRepository<ClosingWithdrawal, Long> {

	/** The withdrawals done on a day (the Journal's day), by their time. */
	List<ClosingWithdrawal> findByDoneAtGreaterThanEqualAndDoneAtLessThanOrderByDoneAtAsc(LocalDateTime from,
			LocalDateTime to);

	List<ClosingWithdrawal> findBySessionIdIn(Collection<Long> sessionIds);

	/** The drawer of the cashier: his closed sessions, the most recent closing first (ask for one: a page of 1). */
	@Query("select s from CashierSession s where s.cashier = :cashier and s.status in :statuses"
			+ " and s.closedAt is not null order by s.closedAt desc, s.id desc")
	List<CashierSession> lastClosedOfCashier(@Param("cashier") UserAccount cashier,
			@Param("statuses") Collection<SessionStatus> statuses, Pageable page);

	/** The drawer of the POS machine: the closed sessions opened on it, the most recent closing first. */
	@Query("select l.session from SessionPosMachine l where l.posMachine.id = :machineId and l.session.status in"
			+ " :statuses and l.session.closedAt is not null order by l.session.closedAt desc, l.session.id desc")
	List<CashierSession> lastClosedOfMachine(@Param("machineId") Long machineId,
			@Param("statuses") Collection<SessionStatus> statuses, Pageable page);
}
