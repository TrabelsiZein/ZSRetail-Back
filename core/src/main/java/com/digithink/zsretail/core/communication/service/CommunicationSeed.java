package com.digithink.zsretail.core.communication.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.model.CommunicationRule;
import com.digithink.zsretail.core.communication.model.CommunicationTemplate;
import com.digithink.zsretail.core.communication.repository.CommunicationRuleRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationTemplateRepository;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;

import lombok.extern.log4j.Log4j2;

/**
 * 3.3.0, communication module, step 3: at the start, where this installation sends, the two French SMS templates and
 * their two rules, written only when their code is missing and INACTIVE: nothing is sent until someone turns them on.
 * Step 7: a third template, a promotion for a campaign (PROMO_SMS_FR, no rule), inactive too.
 * A row that exists (changed, or turned on) is never touched. The texts use only the basic SMS alphabet and stay in one
 * SMS part (160 characters) with usual values (CommunicationSeedTextsTest).
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION_SENDS_HERE)
@Log4j2
public class CommunicationSeed {

	public static final String MEMBER_TEMPLATE = "MEMBER_CARD_SMS_FR";
	public static final String TICKET_TEMPLATE = "TICKET_THANKS_SMS_FR";
	public static final String MEMBER_RULE = "MEMBER_CARD_SMS";
	public static final String TICKET_RULE = "TICKET_THANKS_SMS";

	public static final String MEMBER_TEXT = "Bienvenue {firstName} ! Votre carte de fidélité {companyName} :"
			+ " {cardNumber}. Présentez-la à chaque achat pour cumuler des points.";
	public static final String TICKET_TEXT = "Merci {firstName} pour votre achat chez {companyName} ({total} TND) :"
			+ " +{pointsEarned} points, solde {balanceAfter} points (carte {cardNumber}).";

	/** Step 7: an example of a campaign's text, a short promotion (only a campaign's fields). */
	public static final String PROMO_TEMPLATE = "PROMO_SMS_FR";
	public static final String PROMO_TEXT = "{firstName}, profitez de nos offres de la semaine chez {companyName} !"
			+ " Votre solde : {pointsBalance} points.";

	private final CommunicationTemplateRepository templates;
	private final CommunicationRuleRepository rules;

	public CommunicationSeed(CommunicationTemplateRepository templates, CommunicationRuleRepository rules) {
		this.templates = templates;
		this.rules = rules;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void seed() {
		int written = template(MEMBER_TEMPLATE, "Carte de fidélité (SMS, français)", MEMBER_TEXT)
				+ template(TICKET_TEMPLATE, "Merci pour l'achat (SMS, français)", TICKET_TEXT)
				+ template(PROMO_TEMPLATE, "Promotion (SMS, français)", PROMO_TEXT) // step 7, no rule: a campaign's
				+ rule(MEMBER_RULE, CommunicationTypes.MEMBER_CREATED, MEMBER_TEMPLATE)
				+ rule(TICKET_RULE, CommunicationTypes.TICKET_VALIDATED, TICKET_TEMPLATE);
		if (written > 0) {
			log.info("Communication: {} template(s) and rule(s) written, inactive", written);
		}
	}

	private int template(String code, String name, String body) {
		if (templates.findByCode(code).isPresent()) {
			return 0;
		}
		CommunicationTemplate template = new CommunicationTemplate();
		template.setCode(code);
		template.setName(name);
		template.setChannel(CommunicationTypes.SMS);
		template.setLanguageCode("fr");
		template.setBody(body);
		template.setActive(false);
		templates.save(template);
		return 1;
	}

	private int rule(String code, String eventType, String templateCode) {
		if (rules.findByCode(code).isPresent()) {
			return 0;
		}
		CommunicationRule rule = new CommunicationRule();
		rule.setCode(code);
		rule.setEventType(eventType);
		rule.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		rule.setChannel(CommunicationTypes.SMS);
		rule.setTemplateCode(templateCode);
		rule.setLifetimeHours(CommunicationRule.DEFAULT_LIFETIME_HOURS);
		rule.setActive(false);
		rules.save(rule);
		return 1;
	}
}
