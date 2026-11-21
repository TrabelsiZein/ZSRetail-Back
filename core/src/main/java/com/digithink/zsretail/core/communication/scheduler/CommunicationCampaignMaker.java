package com.digithink.zsretail.core.communication.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationCampaignStatus;
import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationCampaign;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.repository.CommunicationCampaignRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationMessageRepository;
import com.digithink.zsretail.core.communication.service.CampaignAudience;
import com.digithink.zsretail.core.communication.service.CommunicationCampaigns;
import com.digithink.zsretail.core.communication.service.CommunicationSettings;
import com.digithink.zsretail.core.communication.service.CommunicationTemplates;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;
import com.digithink.zsretail.core.repository.LoyaltyMemberRepository;

/**
 * 3.3.0, communication step 7: the messages of the running campaigns, made in the background, never in the request
 * that launched them. Between the processor (10) and the sender (20) of each cycle, at most {@link #BUDGET} of it: the
 * automatic messages are made before and sent first. A campaign is made by batches of {@link #BATCH} members in id
 * order, each batch in its own transaction with the campaign's cursor (last_member_id): a restart goes on after the
 * last batch written. One message per campaign and address (dedup key CAMPAIGN:&lt;id&gt;:&lt;address&gt;, unique): a
 * batch made twice (a launch run twice, a restart) writes no double. The members judged as the window counted them
 * (CampaignAudience). Every member read: made, the campaign DONE once no message of it waits.
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION_SENDS_HERE)
@Order(15)
public class CommunicationCampaignMaker implements CommunicationTask {

	public static final String CODE = "CAMPAIGN_MESSAGES";
	static final int BATCH = 200;
	static final Duration BUDGET = Duration.ofSeconds(8);
	static final int TIMEOUT_SECONDS = 30;

	private final CommunicationCampaignRepository campaigns;
	private final CommunicationMessageRepository messages;
	private final LoyaltyMemberRepository members;
	private final CommunicationCampaigns service;
	private final CommunicationSettings settings;
	private final TransactionOperations transactions;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public CommunicationCampaignMaker(CommunicationCampaignRepository campaigns, CommunicationMessageRepository messages,
			LoyaltyMemberRepository members, CommunicationCampaigns service, CommunicationSettings settings,
			PlatformTransactionManager transactionManager) {
		this(campaigns, messages, members, service, settings, timed(transactionManager), LocalDateTime::now);
	}

	/** With given transactions and clock: used by the tests. */
	public CommunicationCampaignMaker(CommunicationCampaignRepository campaigns, CommunicationMessageRepository messages,
			LoyaltyMemberRepository members, CommunicationCampaigns service, CommunicationSettings settings,
			TransactionOperations transactions, Supplier<LocalDateTime> clock) {
		this.campaigns = campaigns;
		this.messages = messages;
		this.members = members;
		this.service = service;
		this.settings = settings;
		this.transactions = transactions;
		this.clock = clock;
	}

	private static TransactionTemplate timed(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	@Override
	public String getCode() {
		return CODE;
	}

	@Override
	public String run(Instant deadline) {
		Instant budget = Instant.now().plus(BUDGET);
		Instant until = budget.isBefore(deadline) ? budget : deadline;
		List<String> done = new ArrayList<>();
		for (CommunicationCampaign running : campaigns.findByStatusOrderById(CommunicationCampaignStatus.RUNNING)) {
			int made = 0;
			while (Instant.now().isBefore(until)) {
				Batch batch = transactions.execute(status -> batch(running.getId()));
				if (batch == null) {
					break;
				}
				made += batch.made;
				if (batch.last) {
					break;
				}
			}
			if (made > 0) {
				done.add(made + " message(s) made for " + running.getCode());
			}
			Boolean ended = transactions.execute(status -> endIfDone(running.getId()));
			if (Boolean.TRUE.equals(ended)) {
				done.add(running.getCode() + " done");
			}
		}
		return done.isEmpty() ? null : String.join(", ", done);
	}

	/** The result of one batch: its messages, and whether every member is read. */
	public static final class Batch {
		int made;
		boolean last;

		public int getMade() {
			return made;
		}

		public boolean isLast() {
			return last;
		}
	}

	/**
	 * One batch of a campaign, to run in its transaction (the tests: a restart after it); null when the campaign is not to
	 * make any more (cancelled, made).
	 */
	public Batch batch(long campaignId) {
		CommunicationCampaign campaign = campaigns.findById(campaignId).orElse(null);
		if (campaign == null || campaign.getStatus() != CommunicationCampaignStatus.RUNNING
				|| campaign.getMadeAt() != null) {
			return null;
		}
		Batch batch = new Batch();
		LocalDateTime now = clock.get();
		List<Object[]> rows = members.findCampaignCandidates(RecordOrigin.HEAD_OFFICE, campaign.getLastMemberId(),
				PageRequest.of(0, BATCH));
		CampaignAudience audience = service.audienceOf(campaign.getChannel(),
				CommunicationTemplates.usedFields(campaign.getSubject(), campaign.getBody()));
		Set<String> taken = new HashSet<>();
		List<CampaignAudience.Judged> receivers = new ArrayList<>();
		int leftOut = 0;
		long last = campaign.getLastMemberId();
		for (Object[] row : rows) {
			CampaignAudience.Judged judged = audience.judge(row, taken);
			last = judged.getMemberId();
			if (judged.getOutcome() == CampaignAudience.Outcome.RECEIVE) {
				receivers.add(judged);
			} else if (judged.getOutcome() != CampaignAudience.Outcome.ALIAS) {
				leftOut++;
			}
		}
		// The addresses of the campaign's messages already made (an earlier batch, a batch made again): never twice
		Map<String, CampaignAudience.Judged> byKey = new LinkedHashMap<>();
		receivers.forEach(r -> byKey.put(dedupKey(campaignId, r.getAddress()), r));
		Set<String> existing = byKey.isEmpty() ? new HashSet<>() : new HashSet<>(messages.findExistingDedupKeys(byKey.keySet()));
		for (Map.Entry<String, CampaignAudience.Judged> receiver : byKey.entrySet()) {
			if (existing.contains(receiver.getKey())) {
				leftOut++;
				continue;
			}
			messages.save(message(campaign, receiver.getValue(), receiver.getKey()));
			batch.made++;
		}
		campaign.setMade(campaign.getMade() + batch.made);
		campaign.setLeftOut(campaign.getLeftOut() + leftOut);
		campaign.setLastMemberId(last);
		if (rows.size() < BATCH) {
			campaign.setMadeAt(now);
			batch.last = true;
		}
		campaigns.save(campaign);
		return batch;
	}

	/** DONE once every member is read and none of its messages waits; true when it became DONE now. */
	Boolean endIfDone(long campaignId) {
		CommunicationCampaign campaign = campaigns.findById(campaignId).orElse(null);
		if (campaign == null || campaign.getStatus() != CommunicationCampaignStatus.RUNNING
				|| campaign.getMadeAt() == null
				|| messages.countByCampaignIdAndStatus(campaignId, CommunicationMessageStatus.PENDING) > 0) {
			return false;
		}
		campaign.setStatus(CommunicationCampaignStatus.DONE);
		campaign.setEndedAt(clock.get());
		campaigns.save(campaign);
		return true;
	}

	/** One message per campaign and address. */
	public static String dedupKey(long campaignId, String address) {
		return "CAMPAIGN:" + campaignId + ":" + CampaignAudience.key(address);
	}

	private CommunicationMessage message(CommunicationCampaign campaign, CampaignAudience.Judged receiver, String key) {
		CommunicationMessage message = new CommunicationMessage();
		message.setCampaignId(campaign.getId());
		message.setChannel(campaign.getChannel());
		message.setProvider(settings.provider(campaign.getChannel()));
		message.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		message.setRecipientRef(receiver.getCard());
		message.setAddress(receiver.getAddress());
		message.setSubject(campaign.getSubject() == null ? null
				: CommunicationTemplates.render(campaign.getTemplateCode(), campaign.getSubject(), receiver.getValues()));
		message.setBody(CommunicationTemplates.render(campaign.getTemplateCode(), campaign.getBody(), receiver.getValues()));
		message.setTemplateCode(campaign.getTemplateCode());
		message.setLanguageCode(campaign.getLanguageCode());
		message.setExpiresAt(campaign.getLaunchedAt().plusHours(campaign.getLifetimeHours()));
		message.setDedupKey(key);
		message.setStatus(CommunicationMessageStatus.PENDING);
		message.setCreatedBy(campaign.getLaunchedBy());
		return message;
	}
}
