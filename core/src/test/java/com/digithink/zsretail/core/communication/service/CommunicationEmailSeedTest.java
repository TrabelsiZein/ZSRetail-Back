package com.digithink.zsretail.core.communication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.model.CommunicationRule;
import com.digithink.zsretail.core.communication.model.CommunicationTemplate;
import com.digithink.zsretail.core.communication.repository.CommunicationRuleRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationTemplateRepository;

/**
 * 3.3.0, communication step 8a: the email examples of the welcome and the thanks written at the start, inactive, with a
 * subject and their rules on the email channel; only known fields; the welcome's fields a campaign has too.
 */
class CommunicationEmailSeedTest {

	@Test
	@DisplayName("The welcome and the thanks by email: written inactive with a subject, their rules on the email channel")
	void seeded() {
		Map<String, CommunicationTemplate> templates = new LinkedHashMap<>();
		List<CommunicationRule> rules = new ArrayList<>();
		CommunicationTemplateRepository templateRepository = CampaignTestBed.stub(CommunicationTemplateRepository.class,
				(method, args) -> {
					if ("findByCode".equals(method)) {
						return Optional.ofNullable(templates.get(args[0]));
					}
					if ("save".equals(method)) {
						templates.put(((CommunicationTemplate) args[0]).getCode(), (CommunicationTemplate) args[0]);
						return args[0];
					}
					return CampaignTestBed.UNHANDLED;
				});
		CommunicationRuleRepository ruleRepository = CampaignTestBed.stub(CommunicationRuleRepository.class,
				(method, args) -> {
					if ("findByCode".equals(method)) {
						return rules.stream().filter(r -> r.getCode().equals(args[0])).findFirst();
					}
					if ("save".equals(method)) {
						rules.add((CommunicationRule) args[0]);
						return args[0];
					}
					return CampaignTestBed.UNHANDLED;
				});
		new CommunicationSeed(templateRepository, ruleRepository).seed();
		for (String code : new String[] { CommunicationSeed.MEMBER_EMAIL_TEMPLATE, CommunicationSeed.TICKET_EMAIL_TEMPLATE }) {
			CommunicationTemplate template = templates.get(code);
			assertEquals(CommunicationTypes.EMAIL, template.getChannel(), code);
			assertFalse(template.getActive(), code);
			assertTrue(template.getSubject().contains("{companyName}"), code);
			assertTrue(CommunicationTemplates.unknownFields(template.getBody()).isEmpty(), code);
			assertTrue(CommunicationTemplates.unknownFields(template.getSubject()).isEmpty(), code);
		}
		CommunicationRule welcome = rules.stream().filter(r -> CommunicationSeed.MEMBER_EMAIL_RULE.equals(r.getCode()))
				.findFirst().get();
		assertEquals(CommunicationTypes.EMAIL, welcome.getChannel());
		assertEquals(CommunicationTypes.MEMBER_CREATED, welcome.getEventType());
		assertFalse(welcome.getActive());
		CommunicationRule thanks = rules.stream().filter(r -> CommunicationSeed.TICKET_EMAIL_RULE.equals(r.getCode()))
				.findFirst().get();
		assertEquals(CommunicationSeed.TICKET_EMAIL_TEMPLATE, thanks.getTemplateCode());
		assertEquals(CommunicationTypes.EMAIL, thanks.getChannel());
		assertTrue(CommunicationTemplates.saleFields(CommunicationSeed.MEMBER_EMAIL_SUBJECT,
				CommunicationSeed.MEMBER_EMAIL_TEXT).isEmpty(), "the welcome by email fits a campaign too");
	}
}
