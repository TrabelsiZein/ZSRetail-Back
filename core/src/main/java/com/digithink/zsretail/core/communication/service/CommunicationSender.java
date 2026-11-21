package com.digithink.zsretail.core.communication.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationCampaignStatus;
import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationCampaign;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.repository.CommunicationCampaignRepository;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider.SendResult;
import com.digithink.zsretail.core.communication.repository.CommunicationMessageRepository;
import com.digithink.zsretail.core.communication.scheduler.CommunicationTask;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.model.MessageConsent;
import com.digithink.zsretail.core.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.core.utils.ErrorTexts;

/**
 * 3.3.0, communication module, step 3: sends the PENDING messages that are due (no next attempt, or one passed), by id,
 * one at a time, until the cycle's deadline. Past its expires_at: EXPIRED, not sent. Its channel off now: SKIPPED (the
 * switch stops the messages already waiting). Accepted by the provider: SENT, sent_at, the provider's id. A temporary
 * failure (or a throw): one more attempt and a growing wait ({@link #WAITS}), FAILED after {@link #MAX_ATTEMPTS}; a
 * permanent failure: FAILED at once. Each message saved on its own. See docs/modules/communication.md.
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION_SENDS_HERE)
@Order(20)
public class CommunicationSender implements CommunicationTask {

	public static final String CODE = "MESSAGES_SENT";
	static final int PAGE_SIZE = 50;
	static final int MAX_ATTEMPTS = 5;

	/** The wait after the 1st, 2nd, 3rd and 4th temporary failure; the 5th makes the message FAILED. */
	static final List<Duration> WAITS = Arrays.asList(Duration.ofMinutes(1), Duration.ofMinutes(5),
			Duration.ofMinutes(15), Duration.ofMinutes(60));

	private final CommunicationMessageRepository messages;
	private final LoyaltyMemberRepository members;
	private final CommunicationSettings settings;
	private final Map<String, CommunicationProvider> providers = new LinkedHashMap<>();
	private final Supplier<LocalDateTime> clock;
	/** Step 7: the campaigns, to leave a cancelled one's messages unsent; null in the tests of before (no campaign). */
	private CommunicationCampaignRepository campaigns;
	/** Step 7: a campaign's status read at most every {@link #CAMPAIGN_STATUS_AGE} (comm-1 thread only). */
	private final Map<Long, CampaignStatusRead> campaignStatuses = new HashMap<>();
	static final Duration CAMPAIGN_STATUS_AGE = Duration.ofSeconds(2);

	private static final class CampaignStatusRead {
		final CommunicationCampaignStatus status;
		final Instant readAt;

		CampaignStatusRead(CommunicationCampaignStatus status, Instant readAt) {
			this.status = status;
			this.readAt = readAt;
		}
	}

	@Autowired
	public CommunicationSender(CommunicationMessageRepository messages, LoyaltyMemberRepository members,
			CommunicationSettings settings, List<CommunicationProvider> providers,
			CommunicationCampaignRepository campaigns) {
		this(messages, members, settings, providers, LocalDateTime::now);
		this.campaigns = campaigns;
	}

	/** Step 7: with the campaigns (tests). */
	public CommunicationSender withCampaigns(CommunicationCampaignRepository campaigns) {
		this.campaigns = campaigns;
		return this;
	}

	/** With a given clock: used by the tests. */
	public CommunicationSender(CommunicationMessageRepository messages, LoyaltyMemberRepository members,
			CommunicationSettings settings, List<CommunicationProvider> providers, Supplier<LocalDateTime> clock) {
		this.messages = messages;
		this.members = members;
		this.settings = settings;
		providers.forEach(provider -> this.providers.put(provider.getName(), provider));
		this.clock = clock;
	}

	@Override
	public String getCode() {
		return CODE;
	}

	/**
	 * Step 7: the automatic messages (an event's) first, then the campaigns' in the time left: a campaign of thousands of
	 * members never delays a welcome or a thanks (the next cycle starts again with the automatic ones).
	 */
	@Override
	public String run(Instant deadline) {
		Map<CommunicationMessageStatus, Integer> counts = new LinkedHashMap<>();
		int[] retried = { 0 };
		send(CommunicationMessageRepository.AUTOMATIC, deadline, counts, retried);
		send(CommunicationMessageRepository.CAMPAIGN, deadline, counts, retried);
		campaignStatuses.clear();
		if (counts.isEmpty() && retried[0] == 0) {
			return null;
		}
		StringBuilder done = new StringBuilder();
		counts.forEach((status, n) -> done.append(done.length() == 0 ? "" : ", ").append(n).append(' ')
				.append(status.name().toLowerCase(java.util.Locale.ROOT)));
		if (retried[0] > 0) {
			done.append(done.length() == 0 ? "" : ", ").append(retried[0]).append(" to try again");
		}
		return done.toString();
	}

	/** One queue, by id, until the deadline. */
	private void send(int queue, Instant deadline, Map<CommunicationMessageStatus, Integer> counts, int[] retried) {
		long afterId = 0;
		while (Instant.now().isBefore(deadline)) {
			List<CommunicationMessage> due = messages.findDue(CommunicationMessageStatus.PENDING, clock.get(), afterId,
					PageRequest.of(0, PAGE_SIZE), queue);
			if (due.isEmpty()) {
				break;
			}
			for (CommunicationMessage message : due) {
				afterId = message.getId();
				if (!Instant.now().isBefore(deadline)) {
					break;
				}
				CommunicationMessageStatus status = handle(message);
				if (status == null) {
					continue; // a cancelled campaign's: SKIPPED by the cancel itself
				}
				if (status == CommunicationMessageStatus.PENDING) {
					retried[0]++;
				} else {
					counts.merge(status, 1, Integer::sum);
				}
			}
			if (due.size() < PAGE_SIZE) {
				break;
			}
		}
	}

	/** Step 7: false when the message's campaign was cancelled (read at most every 2 s). */
	private boolean campaignRunning(Long campaignId) {
		if (campaignId == null || campaigns == null) {
			return true;
		}
		Instant now = Instant.now();
		CampaignStatusRead read = campaignStatuses.get(campaignId);
		if (read == null || read.readAt.plus(CAMPAIGN_STATUS_AGE).isBefore(now)) {
			CommunicationCampaignStatus status = campaigns.findById(campaignId).map(CommunicationCampaign::getStatus)
					.orElse(CommunicationCampaignStatus.CANCELLED);
			read = new CampaignStatusRead(status, now);
			campaignStatuses.put(campaignId, read);
		}
		return read.status != CommunicationCampaignStatus.CANCELLED;
	}

	/** One message: sent (or not) and saved. Returns its new status; null when its campaign was cancelled (not touched). */
	CommunicationMessageStatus handle(CommunicationMessage message) {
		if (!campaignRunning(message.getCampaignId())) {
			return null;
		}
		LocalDateTime now = clock.get();
		if (message.getExpiresAt() != null && !now.isBefore(message.getExpiresAt())) {
			return save(message, CommunicationMessageStatus.EXPIRED, "expired at " + message.getExpiresAt() + ", not sent");
		}
		if (!settings.isEnabled(message.getChannel())) {
			return save(message, CommunicationMessageStatus.SKIPPED, "channel "
					+ message.getChannel().toLowerCase(java.util.Locale.ROOT) + " off when it was to be sent");
		}
		// Step 5: the consent read again just before sending; withdrawn meanwhile (or the member gone): not sent
		String notAllowed = consentRefusal(message);
		if (notAllowed != null) {
			return save(message, CommunicationMessageStatus.SKIPPED, notAllowed);
		}
		CommunicationProvider provider = message.getProvider() == null ? null : providers.get(message.getProvider());
		if (provider == null) {
			return save(message, CommunicationMessageStatus.FAILED, "provider " + message.getProvider() + " unknown");
		}
		SendResult result;
		try {
			result = provider.send(message);
		} catch (RuntimeException e) {
			result = SendResult.temporaryFailure(ErrorTexts.cause(e));
		}
		if (result == null) {
			result = SendResult.temporaryFailure("no answer from the provider");
		}
		message.setAttempts(message.getAttempts() + 1);
		switch (result.getOutcome()) {
			case ACCEPTED:
				message.setSentAt(now);
				message.setProviderMessageId(result.getProviderMessageId());
				// Step 4: last_error kept, the row's history (a failed attempt before, a message sent again)
				return save(message, CommunicationMessageStatus.SENT, message.getLastError());
			case PERMANENT_FAILURE:
				return save(message, CommunicationMessageStatus.FAILED, result.getError());
			default:
				if (message.getAttempts() >= MAX_ATTEMPTS) {
					return save(message, CommunicationMessageStatus.FAILED, "attempt " + message.getAttempts() + " of "
							+ MAX_ATTEMPTS + " failed: " + result.getError());
				}
				message.setNextAttemptAt(now.plus(WAITS.get(message.getAttempts() - 1)));
				return save(message, CommunicationMessageStatus.PENDING, "attempt " + message.getAttempts() + " failed: "
						+ result.getError());
		}
	}

	/**
	 * Step 5: why a message to a loyalty member may not leave now (its consent withdrawn, the member gone), null when it
	 * may. A message to anyone else is not checked here. Step 7: the member made inactive, or its address no longer the
	 * message's (a phone or an email changed on the card since the message was made), not sent either.
	 */
	String consentRefusal(CommunicationMessage message) {
		if (!CommunicationTypes.LOYALTY_MEMBER.equals(message.getRecipientType()) || message.getRecipientRef() == null) {
			return null;
		}
		Optional<LoyaltyMember> member = members.findByCardNumber(message.getRecipientRef());
		if (!member.isPresent()) {
			return "member " + message.getRecipientRef() + " not found when it was to be sent";
		}
		if (!Boolean.TRUE.equals(member.get().getActive())) {
			return "member inactive when it was to be sent";
		}
		if (!MessageConsent.given(member.get())) {
			return "no consent when it was to be sent";
		}
		String address = CommunicationEventProcessor.address(message.getChannel(), member.get(), settings.getCountryCode());
		if (address == null || !address.equals(message.getAddress())) {
			return "address changed since the message was made";
		}
		return null;
	}

	private CommunicationMessageStatus save(CommunicationMessage message, CommunicationMessageStatus status, String error) {
		message.setStatus(status);
		message.setLastError(error == null || error.length() <= CommunicationMessage.LAST_ERROR_LENGTH ? error
				: error.substring(0, CommunicationMessage.LAST_ERROR_LENGTH));
		messages.save(message);
		return status;
	}
}
