package com.digithink.zsretail.core.convention.repository;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.convention.model.ConventionCard;
import com.digithink.zsretail.core.repository._BaseRepository;

/** 3.6.0, conventions step 1: the cards. The parts B4 and B5 add their queries here. */
@Repository
public interface ConventionCardRepository extends _BaseRepository<ConventionCard, Long> {

	/** The number as stored (the service trims and upper-cases it first); unique in the whole installation. */
	Optional<ConventionCard> findByCardNumber(String cardNumber);
}
