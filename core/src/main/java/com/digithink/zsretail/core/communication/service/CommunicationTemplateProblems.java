package com.digithink.zsretail.core.communication.service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.communication.enumeration.CommunicationCampaignStatus;
import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationCampaign;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.model.CommunicationTemplate;
import com.digithink.zsretail.core.communication.repository.CommunicationCampaignRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationMessageRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationTemplateRepository;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;

import lombok.extern.log4j.Log4j2;

/**
 * 3.3.0, communication step 8c: a provider's refusal of a template (CommunicationProvider.Outcome.TEMPLATE_PROBLEM,
 * WhatsApp: not approved, paused, not in that language, a wrong number of values) is the template's, the same for each
 * of its messages: never a 5,000 campaign failed one by one, never the whole channel paused (another template may be
 * fine). The template is marked (comm_template.provider_problem: the Templates and Rules tabs show it); its campaign is
 * STOPPED with the reason and its waiting messages SKIPPED in one statement; an automatic message is FAILED alone (the
 * next ones are tried: once the template is approved they leave). A message of the template accepted, or a save of the
 * template, clears the mark. See docs/modules/communication.md, "Step 8c".
 */
@Service
@ConditionalOnInstallation(Installation.COMMUNICATION_SENDS_HERE)
@Log4j2
public class CommunicationTemplateProblems {

	/** The reason of a campaign's waiting message skipped when the campaign is stopped. */
	public static final String CAMPAIGN_STOPPED = "campaign stopped: ";

	private final CommunicationTemplateRepository templates;
	private final CommunicationCampaignRepository campaigns;
	private final CommunicationMessageRepository messages;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public CommunicationTemplateProblems(CommunicationTemplateRepository templates,
			CommunicationCampaignRepository campaigns, CommunicationMessageRepository messages) {
		this(templates, campaigns, messages, LocalDateTime::now);
	}

	/** With a given clock: used by the tests. */
	public CommunicationTemplateProblems(CommunicationTemplateRepository templates,
			CommunicationCampaignRepository campaigns, CommunicationMessageRepository messages,
			Supplier<LocalDateTime> clock) {
		this.templates = templates;
		this.campaigns = campaigns;
		this.messages = messages;
		this.clock = clock;
	}

	/** The codes of the templates marked now (read once a cycle by the sender). */
	public Set<String> marked() {
		return templates.findByProviderProblemIsNotNull().stream().map(CommunicationTemplate::getCode)
				.collect(Collectors.toSet());
	}

	/**
	 * A message's template refused: the template marked; its campaign, still running, STOPPED with the reason and its
	 * waiting messages SKIPPED ({@link #CAMPAIGN_STOPPED} and the reason). Returns how many were skipped.
	 */
	@Transactional
	public int refused(CommunicationMessage message, String reason) {
		LocalDateTime now = clock.get();
		String text = reason == null ? "" : reason.length() <= 900 ? reason : reason.substring(0, 900);
		boolean[] newlyMarked = { false };
		if (message.getTemplateCode() != null) {
			templates.findByCode(message.getTemplateCode()).ifPresent(template -> {
				newlyMarked[0] = template.getProviderProblem() == null;
				template.setProviderProblem(text);
				template.setProviderProblemAt(now);
				templates.save(template);
			});
		}
		int skipped = 0;
		if (message.getCampaignId() != null) {
			CommunicationCampaign campaign = campaigns.findById(message.getCampaignId()).orElse(null);
			if (campaign != null && campaign.getStatus() == CommunicationCampaignStatus.RUNNING) {
				campaign.setStatus(CommunicationCampaignStatus.STOPPED);
				campaign.setStopReason(text);
				campaign.setEndedAt(now);
				campaigns.save(campaign);
				skipped = messages.skipWaiting(campaign.getId(), CommunicationMessageStatus.PENDING,
						CommunicationMessageStatus.SKIPPED, CAMPAIGN_STOPPED + text, now, "system");
				log.warn("Communication: campaign {} stopped, {} waiting message(s) skipped: {}", campaign.getCode(), skipped,
						text);
			}
		}
		// Step 8e: one WARN when the template is marked, not one per message (per sale) until the mark clears
		if (newlyMarked[0]) {
			log.warn("Communication: template {} refused by the provider: {}", message.getTemplateCode(), text);
		} else {
			log.debug("Communication: template {} refused again: {}", message.getTemplateCode(), text);
		}
		return skipped;
	}

	/** A message of the template accepted: its mark cleared. True when it had one. */
	@Transactional
	public boolean accepted(String templateCode) {
		CommunicationTemplate template = templateCode == null ? null : templates.findByCode(templateCode).orElse(null);
		if (template == null || template.getProviderProblem() == null) {
			return false;
		}
		template.setProviderProblem(null);
		template.setProviderProblemAt(null);
		templates.save(template);
		log.info("Communication: template {} accepted by the provider again", templateCode);
		return true;
	}
}
