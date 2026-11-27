package com.digithink.zsretail.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.model.enumeration.DataOwner;

/**
 * 3.5.0, ticket templates step 5: TICKET_TEMPLATES is a domain of the copies down only: no ownership.&lt;x&gt; key, not in
 * the ownership domains (the lines read, the summary, GET /config, the heartbeat's report), its owner LOCAL for the
 * ownership code; a line ownership.ticket_templates means nothing.
 */
class TicketTemplatesDomainTest {

	@Test
	@DisplayName("No key; the ownership domains are the five of before, in order; its owner LOCAL")
	void notAnOwnershipDomain() {
		assertNull(DataDomain.TICKET_TEMPLATES.getPropertyKey());
		assertFalse(DataDomain.TICKET_TEMPLATES.isOwnership());
		assertEquals(Arrays.asList(DataDomain.CATALOGUE, DataDomain.CUSTOMERS, DataDomain.PROMOTIONS, DataDomain.LOYALTY,
				DataDomain.SUPPLY), DataDomain.ownershipDomains());
		assertTrue(DataDomain.ownershipDomains().stream().allMatch(DataDomain::isOwnership));
		MockEnvironment linked = new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde");
		NodeOwnership ownership = NodeOwnership.resolve(linked);
		assertEquals(DataOwner.LOCAL, ownership.ownerOf(DataDomain.TICKET_TEMPLATES));
		assertFalse(NodeOwnership.isHeadOfficePullSet(linked), "no domain owned by the head office");
		String summary = InstallationSummary.summary(new MockEnvironment(), ownership);
		assertFalse(summary.contains("ticket_templates"), summary);
		assertTrue(summary.contains("supply LOCAL"), summary);
	}
}
