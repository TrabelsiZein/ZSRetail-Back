package com.digithink.zsretail.core.communication.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.communication.model.CommunicationRule;
import com.digithink.zsretail.core.repository._BaseRepository;

/** 3.3.0, step 3: the rules event -> template -> channel (comm_rule). */
@Repository
public interface CommunicationRuleRepository extends _BaseRepository<CommunicationRule, Long> {

	Optional<CommunicationRule> findByCode(String code);

	/** The active rules of an event type, by code. */
	List<CommunicationRule> findByEventTypeAndActiveTrueOrderByCode(String eventType);
}
