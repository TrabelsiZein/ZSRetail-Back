package com.digithink.zsretail.holink.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.model.LoyaltyMemberCopy;
import com.digithink.zsretail.repository._BaseRepository;

public interface LoyaltyMemberCopyRepository extends _BaseRepository<LoyaltyMemberCopy, Long> {

	Optional<LoyaltyMemberCopy> findByCardNumber(String cardNumber);

	/** The cards of this store merged into this card at the head office. */
	List<LoyaltyMemberCopy> findBySurvivingCardNumber(String survivingCardNumber);

	/** The next members to send: never tried first, then oldest first. */
	@Query("select c from LoyaltyMemberCopy c where c.status in :statuses order by c.attempts, c.id")
	List<LoyaltyMemberCopy> findQueue(@Param("statuses") Collection<SalesCopyStatus> statuses, Pageable page);

	/** [status, count] per status, for the Head office link page. */
	@Query("select c.status, count(c) from LoyaltyMemberCopy c group by c.status")
	List<Object[]> countByStatus();

	/** The link page list: ERROR, PENDING, SENT (the names in order), newest first in each. */
	@Query(value = "select c from LoyaltyMemberCopy c where c.status in :statuses order by c.status, c.id desc",
			countQuery = "select count(c) from LoyaltyMemberCopy c where c.status in :statuses")
	Page<LoyaltyMemberCopy> findPage(@Param("statuses") Collection<SalesCopyStatus> statuses, Pageable page);
}
