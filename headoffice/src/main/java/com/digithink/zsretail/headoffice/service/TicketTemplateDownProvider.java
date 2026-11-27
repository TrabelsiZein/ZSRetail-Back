package com.digithink.zsretail.headoffice.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.tickettemplate.TicketDocumentType;
import com.digithink.zsretail.core.tickettemplate.link.TicketTemplateChanges;
import com.digithink.zsretail.core.tickettemplate.service.TicketTemplateService;
import com.digithink.zsretail.headoffice.model.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 3.5.0, ticket templates step 5: the head office side of the copies down TICKET_TEMPLATES. A record per document that
 * has a template saved here, its code the document type, sent to every store ({@link StoreTargets#all()}): a store
 * applies it only while its switch "Le siège gère les modèles de tickets" is on (off, it does not pull the domain; on,
 * it pulls everything again from the start). A save or a reset here is a change in its transaction
 * ({@link TicketTemplateChanges}); a reset's code is answered as removed (no template any more: the stores print the
 * built-in default). The copy is the template as saved, a text left null sent as the built-in one
 * (TicketTemplateService.copyForStores); no identity: each store prints its own Company Information.
 */
@Service
@ConditionalOnInstallation(Installation.HEAD_OFFICE)
public class TicketTemplateDownProvider implements DownDomainProvider, TicketTemplateChanges {

	static final ObjectMapper COPY_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	private final ObjectProvider<TicketTemplateService> service;
	private final ObjectProvider<CopiesDownFeed> feed;

	public TicketTemplateDownProvider(ObjectProvider<TicketTemplateService> service, ObjectProvider<CopiesDownFeed> feed) {
		this.service = service;
		this.feed = feed;
	}

	@Override
	public DataDomain getDomain() {
		return DataDomain.TICKET_TEMPLATES;
	}

	@Override
	public Map<String, JsonNode> load(Store store, List<String> codes) {
		Map<String, JsonNode> copies = new LinkedHashMap<>();
		for (String code : codes) {
			TicketDocumentType type = TicketDocumentType.of(code);
			if (type == null || !type.isAvailable()) {
				continue; // answered as removed
			}
			service.getObject().copyForStores(type).ifPresent(copy -> copies.put(code, COPY_MAPPER.valueToTree(copy)));
		}
		return copies;
	}

	@Override
	public Map<String, StoreTargets> currentTargets() {
		Map<String, StoreTargets> targets = new LinkedHashMap<>();
		for (TicketDocumentType type : service.getObject().savedTypes()) {
			targets.put(type.name(), StoreTargets.all());
		}
		return targets;
	}

	@Override
	public void changed(TicketDocumentType type) {
		CopiesDownFeed copies = feed.getIfAvailable();
		if (copies != null) {
			copies.recordChange(DataDomain.TICKET_TEMPLATES, type.name(), StoreTargets.all());
		}
	}
}
