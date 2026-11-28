package com.digithink.zsretail.core.convention.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.convention.model.ConventionCard;
import com.digithink.zsretail.core.repository._BaseRepository;

/** 3.6.0, conventions step 1: the cards. The parts B4 and B5 add their queries here. */
@Repository
public interface ConventionCardRepository extends _BaseRepository<ConventionCard, Long> {

	/** The number as stored (the service trims and upper-cases it first); unique in the whole installation. */
	Optional<ConventionCard> findByCardNumber(String cardNumber);

	/** Part B3: the convention has a card, of any status (its card settings are fixed, it cannot be deleted). */
	boolean existsByConventionId(Long conventionId);

	/**
	 * Part B3: [convention id, status, count] for these conventions, one GROUP BY over ix_convention_card_convention
	 * (convention_id, status): the list's active and blocked counts of a page in one query.
	 */
	@Query("select c.convention.id, c.status, count(c) from ConventionCard c where c.convention.id in :ids"
			+ " group by c.convention.id, c.status")
	List<Object[]> countByStatus(@Param("ids") Collection<Long> ids);
}
