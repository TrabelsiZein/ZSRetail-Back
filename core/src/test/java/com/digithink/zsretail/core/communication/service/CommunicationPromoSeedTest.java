package com.digithink.zsretail.core.communication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.model.CommunicationRule;
import com.digithink.zsretail.core.communication.model.CommunicationTemplate;
import com.digithink.zsretail.core.communication.repository.CommunicationRuleRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationTemplateRepository;

/**
 * 3.3.0, communication step 7: the example promotion written at the start (PROMO_SMS_FR): inactive, no rule, a campaign's
 * fields only, the basic SMS alphabet in one SMS part with long usual values; never written again once there.
 */
class CommunicationPromoSeedTest {

	/** GSM 03.38 basic alphabet (as CommunicationSettingsTest). */
	private static final String GSM7 = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRS"
			+ "TUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

	@Test
	@DisplayName("The promotion's text: a campaign's fields only, one SMS part, the basic SMS alphabet")
	void text() {
		assertTrue(CommunicationTemplates.saleFields(CommunicationSeed.PROMO_TEXT).isEmpty());
		Map<String, Object> values = new HashMap<>();
		values.put("firstName", "Mohamed-Amine");
		values.put("companyName", "Parapharmacie Fendri");
		values.put("pointsBalance", 123456);
		String sms = CommunicationTemplates.render("SEED", CommunicationSeed.PROMO_TEXT, values);
		assertEquals("Mohamed-Amine, profitez de nos offres de la semaine chez Parapharmacie Fendri ! Votre solde : 123456"
				+ " points.", sms);
		assertTrue(sms.length() <= 160, sms.length() + " characters");
		for (char c : sms.toCharArray()) {
			assertTrue(GSM7.indexOf(c) >= 0, "not in the basic SMS alphabet: '" + c + "'");
		}
	}

	@Test
	@DisplayName("Written inactive when missing, with no rule; a promotion there already is never touched")
	void seeded() {
		Map<String, CommunicationTemplate> templates = new LinkedHashMap<>();
		List<CommunicationRule> rules = new ArrayList<>();
		CommunicationTemplateRepository templateRepository = CampaignTestBed.stub(CommunicationTemplateRepository.class,
				(method, args) -> {
					switch (method) {
						case "findByCode":
							return java.util.Optional.ofNullable(templates.get(args[0]));
						case "save":
							CommunicationTemplate t = (CommunicationTemplate) args[0];
							templates.put(t.getCode(), t);
							return t;
						default:
							return CampaignTestBed.UNHANDLED;
					}
				});
		CommunicationRuleRepository ruleRepository = CampaignTestBed.stub(CommunicationRuleRepository.class,
				(method, args) -> {
					switch (method) {
						case "findByCode":
							return rules.stream().filter(r -> r.getCode().equals(args[0])).findFirst();
						case "save":
							rules.add((CommunicationRule) args[0]);
							return args[0];
						default:
							return CampaignTestBed.UNHANDLED;
					}
				});
		new CommunicationSeed(templateRepository, ruleRepository).seed();
		CommunicationTemplate promo = templates.get(CommunicationSeed.PROMO_TEMPLATE);
		assertFalse(promo.getActive());
		assertEquals(CommunicationTypes.SMS, promo.getChannel());
		assertEquals("fr", promo.getLanguageCode());
		assertTrue(rules.stream().noneMatch(r -> CommunicationSeed.PROMO_TEMPLATE.equals(r.getTemplateCode())));
		promo.setBody("Changed");
		promo.setActive(true);
		new CommunicationSeed(templateRepository, ruleRepository).seed();
		assertEquals("Changed", templates.get(CommunicationSeed.PROMO_TEMPLATE).getBody());
	}
}
