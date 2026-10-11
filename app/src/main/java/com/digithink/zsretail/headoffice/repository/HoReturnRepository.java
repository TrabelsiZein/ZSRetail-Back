package com.digithink.zsretail.headoffice.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.headoffice.model.HoReturn;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoReturnRepository extends _BaseRepository<HoReturn, Long> {

	Optional<HoReturn> findByStoreIdAndReturnNumber(Long storeId, String returnNumber);

	/** Task 2.5: the returns list; parameters as in HoTicketRepository.search (never null). */
	@Query("select r from HoReturn r where (:storeId = 0L or r.store.id = :storeId)"
			+ " and r.returnDate >= :dateFrom and r.returnDate <= :dateTo and lower(r.returnNumber) like :number"
			+ " and (:status = '' or r.status = :status) and (:sessionNumber = '' or r.sessionNumber = :sessionNumber)")
	Page<HoReturn> search(@Param("storeId") long storeId, @Param("dateFrom") LocalDateTime dateFrom,
			@Param("dateTo") LocalDateTime dateTo, @Param("number") String number, @Param("status") String status,
			@Param("sessionNumber") String sessionNumber, Pageable page);

	/**
	 * Task 2.5: [store id, session number, return type, returns, total amount] of the returns with these statuses in
	 * these sessions. The store and number lists are crossed: the caller keeps only its own (store, session) pairs.
	 */
	@Query("select r.store.id, r.sessionNumber, r.returnType, count(r), sum(r.totalReturnAmount) from HoReturn r"
			+ " where r.store.id in :storeIds and r.sessionNumber in :sessionNumbers and r.status in :statuses"
			+ " group by r.store.id, r.sessionNumber, r.returnType")
	List<Object[]> returnsBySession(@Param("storeIds") Collection<Long> storeIds,
			@Param("sessionNumbers") Collection<String> sessionNumbers, @Param("statuses") Collection<String> statuses);

	/** Task 2.5: one row [returns, total amount] of the returns with these statuses made in [from, to). */
	@Query("select count(r), sum(r.totalReturnAmount) from HoReturn r"
			+ " where r.returnDate >= :from and r.returnDate < :to and r.status in :statuses")
	List<Object[]> returnsBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
			@Param("statuses") Collection<String> statuses);
}
