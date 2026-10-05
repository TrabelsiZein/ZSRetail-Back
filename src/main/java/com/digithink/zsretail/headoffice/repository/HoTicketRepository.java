package com.digithink.zsretail.headoffice.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.headoffice.model.HoTicket;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoTicketRepository extends _BaseRepository<HoTicket, Long> {

	Optional<HoTicket> findByStoreIdAndSalesNumber(Long storeId, String salesNumber);

	/** Task 3.3: tickets of every store whose header discount came from this promotion code. */
	long countByPromotionCode(String promotionCode);

	/** Task 3.3: ticket lines of every store discounted by this promotion code. */
	@Query("select count(l) from HoTicketLine l where l.promotionCode = :code")
	long countLinesByPromotionCode(@Param("code") String code);

	/**
	 * Task 2.5: the tickets list. No parameter is null (SQL Server cannot type a null date): storeId 0 = every store,
	 * number "%" = any, status and sessionNumber "" = any; the dates are always given.
	 */
	@Query("select t from HoTicket t where (:storeId = 0L or t.store.id = :storeId)"
			+ " and t.salesDate >= :dateFrom and t.salesDate <= :dateTo and lower(t.salesNumber) like :number"
			+ " and (:status = '' or t.status = :status) and (:sessionNumber = '' or t.sessionNumber = :sessionNumber)")
	Page<HoTicket> search(@Param("storeId") long storeId, @Param("dateFrom") LocalDateTime dateFrom,
			@Param("dateTo") LocalDateTime dateTo, @Param("number") String number, @Param("status") String status,
			@Param("sessionNumber") String sessionNumber, Pageable page);

	/** Task 2.5: [ticket id, number of lines] for these tickets. Never called with an empty collection. */
	@Query("select l.ticket.id, count(l) from HoTicketLine l where l.ticket.id in :ids group by l.ticket.id")
	List<Object[]> countLines(@Param("ids") Collection<Long> ids);

	/** Task 2.5: [ticket id, number of payments] for these tickets. Never called with an empty collection. */
	@Query("select p.ticket.id, count(p) from HoTicketPayment p where p.ticket.id in :ids group by p.ticket.id")
	List<Object[]> countPayments(@Param("ids") Collection<Long> ids);

	/**
	 * Task 2.5: [store id, session number, tickets, total amount] of the tickets with these statuses in these sessions.
	 * The store and number lists are crossed: the caller keeps only its own (store, session) pairs.
	 */
	@Query("select t.store.id, t.sessionNumber, count(t), sum(t.totalAmount) from HoTicket t"
			+ " where t.store.id in :storeIds and t.sessionNumber in :sessionNumbers and t.status in :statuses"
			+ " group by t.store.id, t.sessionNumber")
	List<Object[]> salesBySession(@Param("storeIds") Collection<Long> storeIds,
			@Param("sessionNumbers") Collection<String> sessionNumbers, @Param("statuses") Collection<String> statuses);

	/**
	 * Session details, payment summary: [ticket id, payment method code, payment method name, amount] of every payment
	 * of the tickets with these statuses of one store's session (the tickets salesBySession counts). One query.
	 */
	@Query("select p.ticket.id, p.paymentMethodCode, p.paymentMethodName, p.amount from HoTicketPayment p"
			+ " where p.ticket.store.id = :storeId and p.ticket.sessionNumber = :sessionNumber"
			+ " and p.ticket.status in :statuses")
	List<Object[]> paymentsBySession(@Param("storeId") Long storeId, @Param("sessionNumber") String sessionNumber,
			@Param("statuses") Collection<String> statuses);

	/** Task 2.5: one row [tickets, total amount] of the tickets with these statuses sold in [from, to). */
	@Query("select count(t), sum(t.totalAmount) from HoTicket t"
			+ " where t.salesDate >= :from and t.salesDate < :to and t.status in :statuses")
	List<Object[]> salesBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
			@Param("statuses") Collection<String> statuses);

	/** Task 2.5: the tickets of these stores with these numbers (the returned tickets of a returns page). */
	List<HoTicket> findByStoreIdInAndSalesNumberIn(Collection<Long> storeIds, Collection<String> salesNumbers);

	long countByStatus(String status);
}
