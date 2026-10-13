package com.digithink.zsretail.core.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import javax.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.model.enumeration.SynchronizationStatus;

public interface CashierSessionRepository extends _BaseRepository<CashierSession, Long> {

	Optional<CashierSession> findBySessionNumber(String sessionNumber);

	/**
	 * Load a session with a pessimistic write lock. Used to serialize all
	 * PaymentHeader/PaymentLine mutations for the session (post-sale async creation
	 * vs. admin payment-method change vs. concurrent edits).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT s FROM CashierSession s WHERE s.id = :id")
	Optional<CashierSession> findByIdForUpdate(@Param("id") Long id);

	List<CashierSession> findByCashier(UserAccount cashier);

	List<CashierSession> findByStatus(SessionStatus status);

	/** Inventory count: how many sessions are open now (a warning on the screen, it blocks nothing). */
	long countByStatus(SessionStatus status);

	Optional<CashierSession> findByCashierAndStatus(UserAccount cashier, SessionStatus status);

	List<CashierSession> findByVerifiedBy(UserAccount user);
	
	long countByOpenedAtGreaterThanEqual(LocalDateTime date);

	/** 3.1.0, store ticket Z: the sessions of the given statuses closed in [from, to), oldest opening first. */
	List<CashierSession> findByStatusInAndClosedAtGreaterThanEqualAndClosedAtLessThanOrderByOpenedAtAsc(
			List<SessionStatus> statuses, LocalDateTime from, LocalDateTime to);

	/**
	 * 3.1.0, store ticket X: (the sessions of the given statuses closed in [from, to)) or (the sessions of the given
	 * status), oldest opening first ("And" binds before "Or" in a derived query).
	 */
	List<CashierSession> findByStatusInAndClosedAtGreaterThanEqualAndClosedAtLessThanOrStatusOrderByOpenedAtAsc(
			List<SessionStatus> statuses, LocalDateTime from, LocalDateTime to, SessionStatus status);

	// Query methods for synchronization
	// Find sessions that are CLOSED or TERMINATED and not totally synched
	List<CashierSession> findByStatusInAndSynchronizationStatusNot(
			java.util.List<SessionStatus> statuses, SynchronizationStatus synchronizationStatus);
}

