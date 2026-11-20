package com.digithink.zsretail.core.communication.repository;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.communication.model.CommunicationTemplate;
import com.digithink.zsretail.core.repository._BaseRepository;

/** 3.3.0, step 3: the message texts (comm_template). */
@Repository
public interface CommunicationTemplateRepository extends _BaseRepository<CommunicationTemplate, Long> {

	Optional<CommunicationTemplate> findByCode(String code);
}
