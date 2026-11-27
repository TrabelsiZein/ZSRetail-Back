package com.digithink.zsretail.store.holink.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.tickettemplate.TicketDocumentType;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateDTO;
import com.digithink.zsretail.core.tickettemplate.service.TicketTemplateService;
import com.digithink.zsretail.store.holink.dto.DownApplyResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import lombok.extern.log4j.Log4j2;

/**
 * 3.5.0, ticket templates step 5: the store side of the copies down TICKET_TEMPLATES. Pulled only while the head office
 * manages this store's templates ({@link #active}, {@link TicketTemplatesManaged}). Each record is a head office
 * template (its code the document type): saved as the document's row, origin HEAD_OFFICE, read leniently (a later
 * version's blocks or options dropped); a code removed (the head office reset it) deletes the row received, and the
 * built-in default prints. A document type this version does not know is waiting (a later head office).
 */
@Component
@ConditionalOnInstallation(Installation.LINKED_TO_HEAD_OFFICE)
@Log4j2
public class TicketTemplateDownHandler implements DownHandler {

	static final ObjectMapper COPY_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	private final TicketTemplateService service;
	private final TicketTemplatesManaged managed;

	public TicketTemplateDownHandler(TicketTemplateService service, TicketTemplatesManaged managed) {
		this.service = service;
		this.managed = managed;
	}

	@Override
	public DataDomain getDomain() {
		return DataDomain.TICKET_TEMPLATES;
	}

	@Override
	public boolean active() {
		return managed.managedByHeadOffice();
	}

	@Override
	public DownApplyResult apply(List<JsonNode> records, List<String> removed) {
		DownApplyResult result = DownApplyResult.none();
		for (JsonNode record : records) {
			String code = record.path("documentType").asText(null);
			TicketDocumentType type = TicketDocumentType.of(code);
			if (type == null || !type.isAvailable()) {
				result.addWaiting(String.valueOf(code), "a document this version does not know");
				continue;
			}
			try {
				TicketTemplateDTO copy = COPY_MAPPER.treeToValue(record, TicketTemplateDTO.class);
				if (service.receive(type, copy)) {
					result.addApplied();
				} else {
					result.addUnchanged();
				}
			} catch (Exception e) {
				log.warn("Head office link: the ticket template {} not applied ({})", code, e.getMessage());
				result.addError(code, e.getMessage());
			}
		}
		for (String code : removed) {
			TicketDocumentType type = TicketDocumentType.of(code);
			if (type != null && service.removeReceived(type)) {
				result.addRemoved();
			}
		}
		return result;
	}
}
