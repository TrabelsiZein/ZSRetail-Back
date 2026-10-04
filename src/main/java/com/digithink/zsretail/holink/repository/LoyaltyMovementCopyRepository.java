package com.digithink.zsretail.holink.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.model.LoyaltyMovementCopy;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository._BaseRepository;

/**
 * Head office plan, step 4: the store's tracking of its loyalty movements sent to the head office, and the read-only
 * queries on loyalty_transaction that find them. Scalar rows only, so a movement never loads its sale.
 */
public interface LoyaltyMovementCopyRepository extends _BaseRepository<LoyaltyMovementCopy, Long> {

	/** Row of {@link #findUntracked}: [id, cardNumber, type, points, balanceBefore, balanceAfter, createdAt]. */
	@Query("select t.id, m.cardNumber, t.type, t.points, t.balanceBefore, t.balanceAfter, t.createdAt"
			+ " from LoyaltyTransaction t join t.loyaltyMember m where m.origin = :origin"
			+ " and not exists (select c.id from LoyaltyMovementCopy c where c.localId = t.id) order by t.id")
	List<Object[]> findUntracked(@Param("origin") RecordOrigin origin, Pageable page);

	/**
	 * The next movements to send, oldest first; a movement waits while the upload of its card's member is not accepted
	 * (members are sent first).
	 */
	@Query("select c from LoyaltyMovementCopy c where c.status in :statuses and not exists (select u.id from"
			+ " LoyaltyMemberCopy u where u.cardNumber = c.cardNumber and u.status <> :sent) order by c.localId")
	List<LoyaltyMovementCopy> findQueue(@Param("statuses") Collection<SalesCopyStatus> statuses,
			@Param("sent") SalesCopyStatus sent, Pageable page);

	/**
	 * What a movement copy is built from: [id, cardNumber, type, points, balanceBefore, balanceAfter, createdAt,
	 * salesNumber, returnNumber, programCode, expiryDate, description, createdBy].
	 */
	@Query("select t.id, m.cardNumber, t.type, t.points, t.balanceBefore, t.balanceAfter, t.createdAt, s.salesNumber,"
			+ " r.returnNumber, p.programCode, t.expiryDate, t.description, t.createdBy from LoyaltyTransaction t"
			+ " join t.loyaltyMember m left join t.salesHeader s left join t.returnHeader r left join t.loyaltyProgram p"
			+ " where t.id in :ids")
	List<Object[]> findMovementDetails(@Param("ids") Collection<Long> ids);

	/**
	 * The movements of these members the head office has not applied yet (no row, or a row not SENT), oldest first:
	 * [id, type, points, balanceBefore, balanceAfter, salesHeaderId].
	 */
	@Query("select t.id, t.type, t.points, t.balanceBefore, t.balanceAfter, s.id from LoyaltyTransaction t"
			+ " left join t.salesHeader s where t.loyaltyMember.id in :memberIds and not exists (select c.id from"
			+ " LoyaltyMovementCopy c where c.localId = t.id and c.status = :sent) order by t.id")
	List<Object[]> findNotApplied(@Param("memberIds") Collection<Long> memberIds, @Param("sent") SalesCopyStatus sent);

	List<LoyaltyMovementCopy> findByLocalIdIn(Collection<Long> localIds);

	/** [status, count] per status, for the Head office link page. */
	@Query("select c.status, count(c) from LoyaltyMovementCopy c group by c.status")
	List<Object[]> countByStatus();

	/** The link page list: ERROR, PENDING, SENT (the names in order), newest first in each. */
	@Query(value = "select c from LoyaltyMovementCopy c where c.status in :statuses order by c.status, c.localId desc",
			countQuery = "select count(c) from LoyaltyMovementCopy c where c.status in :statuses")
	Page<LoyaltyMovementCopy> findPage(@Param("statuses") Collection<SalesCopyStatus> statuses, Pageable page);
}
