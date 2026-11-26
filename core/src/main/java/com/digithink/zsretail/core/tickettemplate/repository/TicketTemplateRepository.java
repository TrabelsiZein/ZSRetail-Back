package com.digithink.zsretail.core.tickettemplate.repository;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.core.tickettemplate.model.TicketTemplate;

/** 3.5.0, ticket templates step 2: the templates (ticket_template), one per document type. */
@Repository
public interface TicketTemplateRepository extends _BaseRepository<TicketTemplate, Long> {

	Optional<TicketTemplate> findByDocumentType(String documentType);
}
