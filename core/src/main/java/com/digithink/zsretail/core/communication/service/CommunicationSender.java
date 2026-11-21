package com.digithink.zsretail.core.communication.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import com.digithink.zsretail.core.communication.provider.CommunicationProvider.Outcome;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider.SendResult;
import com.digithink.zsretail.core.communication.repository.CommunicationMessageRepository;
import com.digithink.zsretail.core.communication.scheduler.CommunicationTask;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.model.MessageConsent;
import com.digithink.zsretail.core.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.core.utils.ErrorTexts;

import lombok.extern.log4j.Log4j2;

/**
 * 3.3.0, communication module, step 3: sends the PENDING messages that are due (no next attempt, or one passed), by id,
 * one at a time, until the cycle's deadline. Past its expires_at: EXPIRED, not sent. Its channel off now: SKIPPED (the
 * switch stops the messages already waiting). Accepted by the provider: SENT, sent_at, the provider's id. A temporary
 * failure (or a throw): one more attempt and a growing wait ({@link #WAITS}), FAILED after {@link #MAX_ATTEMPTS}; a
 * permanent failure: FAILED at once. Each message saved on its own. Step 8a part 2: a channel problem (the provider
 * cannot send on the channel at all: a refused login, a server unreachable...) leaves the message waiting with its
 * attempts unused and pauses the channel ({@link CommunicationChannelStates}); one batch per provider and cycle (smtp:
 * one connection). See docs/modules/communication.md.
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION_SENDS_HERE)
@Order(20)
@Log4j2
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

	/** Step 8a part 2: each channel's pause and speed; null in the tests of before (a problem only stops the cycle). */
	private CommunicationChannelStates channelStates;

	@Autowired
	public CommunicationSender(CommunicationMessageRepository messages, LoyaltyMemberRepository members,
			CommunicationSettings settings, List<CommunicationProvider> providers,
			CommunicationCampaignRepository campaigns, CommunicationChannelStates channelStates) {
		this(messages, members, settings, providers, LocalDateTime::now);
		this.campaigns = campaigns;
		this.channelStates = channelStates;
	}

	/** Step 8a part 2: with the channels' pause and speed (tests). */
	public CommunicationSender withChannelStates(CommunicationChannelStates channelStates) {
		this.channelStates = channelStates;
		return this;
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
	 * members never delays a welcome or a thanks (the next cycle starts again with the automatic ones). Step 8a part 2:
	 * one batch per provider for the cycle (smtp: one connection), closed at its end; a channel paused is not read (its
	 * messages past their expiry EXPIRED in one statement); the first channel problem stops the channel for the cycle
	 * and pauses it; the first message accepted on a paused channel ends its pause; the time spent per channel measured.
	 */
	@Override
	public String run(Instant deadline) {
		moreDue = false;
		Cycle cycle = new Cycle();
		Map<CommunicationMessageStatus, Integer> counts = new LinkedHashMap<>();
		int[] retried = { 0 };
		if (channelStates != null) {
			LocalDateTime now = clock.get();
			channelStates.all().forEach((channel, state) -> {
				if (state.isPaused()) {
					cycle.paused.add(channel);
					if (state.getPausedUntil() != null && now.isBefore(state.getPausedUntil())) {
						cycle.stopped.add(channel);
					}
				}
			});
			for (String channel : cycle.stopped) {
				int expired = channelStates.expireWhilePaused(channel);
				if (expired > 0) {
					counts.merge(CommunicationMessageStatus.EXPIRED, expired, Integer::sum);
				}
			}
		}
		try {
			send(CommunicationMessageRepository.AUTOMATIC, deadline, counts, retried, cycle);
			send(CommunicationMessageRepository.CAMPAIGN, deadline, counts, retried, cycle);
		} finally {
			cycle.close();
			campaignStatuses.clear();
		}
		if (channelStates != null) {
			cycle.spent.forEach((key, tally) -> channelStates.measured(key.substring(0, key.indexOf('|')),
					key.substring(key.indexOf('|') + 1), tally[0], tally[1] / 1_000_000));
		}
		moreDue = anyDue(cycle.stopped);
		if (counts.isEmpty() && retried[0] == 0 && cycle.pausedNow.isEmpty()) {
			return null;
		}
		StringBuilder done = new StringBuilder();
		counts.forEach((status, n) -> done.append(done.length() == 0 ? "" : ", ").append(n).append(' ')
				.append(status.name().toLowerCase(java.util.Locale.ROOT)));
		if (retried[0] > 0) {
			done.append(done.length() == 0 ? "" : ", ").append(retried[0]).append(" to try again");
		}
		cycle.pausedNow.forEach(channel -> done.append(done.length() == 0 ? "" : ", ").append("channel ")
				.append(channel.toLowerCase(java.util.Locale.ROOT)).append(" paused"));
		return done.toString();
	}

	/** Step 8a part 2: what one cycle shares: the batches, the channels paused or stopped, the time spent. */
	private final class Cycle implements AutoCloseable {
		/** Paused when the cycle began, or by it: the first message accepted ends the pause. */
		final Set<String> paused = new HashSet<>();
		/** Not sent on in this cycle (paused, the wait not over; or a problem met now). */
		final Set<String> stopped = new HashSet<>();
		/** Paused by this cycle (the log line). */
		final Set<String> pausedNow = new java.util.LinkedHashSet<>();
		/** "CHANNEL|provider" -> {messages, nanos}: the messages handed to the provider, the time spent on them. */
		final Map<String, long[]> spent = new LinkedHashMap<>();
		private final Map<String, CommunicationProvider.Batch> batches = new LinkedHashMap<>();

		CommunicationProvider.Batch batch(CommunicationProvider provider) {
			return batches.computeIfAbsent(provider.getName(), name -> provider.openBatch());
		}

		@Override
		public void close() {
			batches.values().forEach(batch -> {
				try {
					batch.close();
				} catch (RuntimeException e) {
					log.warn("Communication: closing the {} batch failed ({})", batch, ErrorTexts.cause(e));
				}
			});
			batches.clear();
		}
	}

	/** One queue, by id, until the deadline. */
	private void send(int queue, Instant deadline, Map<CommunicationMessageStatus, Integer> counts, int[] retried,
			Cycle cycle) {
		long afterId = 0;
		while (Instant.now().isBefore(deadline)) {
			List<CommunicationMessage> due = cycle.stopped.isEmpty()
					? messages.findDue(CommunicationMessageStatus.PENDING, clock.get(), afterId, PageRequest.of(0, PAGE_SIZE),
							queue)
					: messages.findDueOutside(CommunicationMessageStatus.PENDING, clock.get(), afterId,
							PageRequest.of(0, PAGE_SIZE), queue, new ArrayList<>(cycle.stopped));
			if (due.isEmpty()) {
				break;
			}
			for (CommunicationMessage message : due) {
				afterId = message.getId();
				if (!Instant.now().isBefore(deadline)) {
					break;
				}
				if (cycle.stopped.contains(message.getChannel())) {
					continue; // its channel paused by an earlier message of this page
				}
				Handled handled = handle(message, cycle);
				if (handled.status == null) {
					continue; // a cancelled campaign's: SKIPPED by the cancel itself
				}
				if (handled.outcome == Outcome.CHANNEL_PROBLEM || handled.outcome == Outcome.UNCERTAIN) {
					cycle.stopped.add(message.getChannel());
					cycle.paused.add(message.getChannel());
					cycle.pausedNow.add(message.getChannel());
					if (channelStates != null) {
						channelStates.problem(message.getChannel(), pauseReason(handled), false, handled.retryAfter);
					}
					if (handled.outcome == Outcome.UNCERTAIN) {
						counts.merge(handled.status, 1, Integer::sum); // FAILED, sent again by hand only
					}
					continue; // a channel problem: waiting again, its attempts unused
				}
				if (handled.outcome != null) {
					long[] tally = cycle.spent.computeIfAbsent(message.getChannel() + "|" + message.getProvider(),
							key -> new long[2]);
					tally[0]++;
					tally[1] += handled.nanos;
					if (handled.outcome == Outcome.ACCEPTED && cycle.paused.remove(message.getChannel())
							&& channelStates != null) {
						channelStates.lift(message.getChannel(), "a message accepted");
					}
				}
				if (handled.status == CommunicationMessageStatus.PENDING) {
					retried[0]++;
				} else {
					counts.merge(handled.status, 1, Integer::sum);
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

	/**
	 * Step 8b: the reason of a message the provider gave no answer for (it may have left: sent again by hand only), its
	 * details after it in brackets.
	 */
	public static final String NO_ANSWER = "no answer from the provider, check before sending it again";

	/** Step 8a: the reason of a message left SENDING by a stop (it may have left: sent again by hand only). */
	public static final String INTERRUPTED = "interrupted while it was being sent (the application stopped): it may have"
			+ " left, check before sending it again";

	/**
	 * Step 8a, at the start, before the first cycle: the messages left SENDING become FAILED with {@link #INTERRUPTED},
	 * never sent again by themselves (one WARN line with their number).
	 */
	@org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
	@org.springframework.transaction.annotation.Transactional
	public void interruptedAtStart() {
		int left = messages.markInterrupted(CommunicationMessageStatus.SENDING, CommunicationMessageStatus.FAILED,
				INTERRUPTED, clock.get());
		if (left > 0) {
			log.warn("Communication: {} message(s) were being sent when the application stopped: FAILED, to send again by"
					+ " hand if they did not arrive", left);
		}
	}

	/**
	 * Step 8a: one message sent now, outside the cycle (the Canaux tab's test), on its own connection. Step 8a part 2:
	 * accepted, the channel's pause ends; a channel problem pauses the channel (a pause running keeps its wait).
	 */
	public Handled sendNow(CommunicationMessage message) {
		Handled handled = handle(message, null);
		if (channelStates != null && handled.outcome == Outcome.ACCEPTED) {
			channelStates.lift(message.getChannel(), "a test accepted");
		} else if (channelStates != null
				&& (handled.outcome == Outcome.CHANNEL_PROBLEM || handled.outcome == Outcome.UNCERTAIN)) {
			channelStates.problem(message.getChannel(), pauseReason(handled), true, handled.retryAfter);
		}
		return handled;
	}

	/** Step 8b: the pause's reason: the provider's words, "no answer from the provider: ..." for an uncertain one. */
	private static String pauseReason(Handled handled) {
		return handled.outcome == Outcome.UNCERTAIN ? "no answer from the provider: " + handled.error : handled.error;
	}

	/** Step 8b: messages still due after the cycle, on a channel not stopped by it: the next cycle comes sooner. */
	private volatile boolean moreDue;

	@Override
	public boolean moreToDo() {
		return moreDue;
	}

	/** Step 8b: one due message of either queue, outside the channels stopped (one row read each). */
	private boolean anyDue(Set<String> stopped) {
		for (int queue : new int[] { CommunicationMessageRepository.AUTOMATIC, CommunicationMessageRepository.CAMPAIGN }) {
			List<CommunicationMessage> one = stopped.isEmpty()
					? messages.findDue(CommunicationMessageStatus.PENDING, clock.get(), 0, PageRequest.of(0, 1), queue)
					: messages.findDueOutside(CommunicationMessageStatus.PENDING, clock.get(), 0, PageRequest.of(0, 1), queue,
							new ArrayList<>(stopped));
			if (one != null && !one.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * What became of one message: its new status (null when its campaign was cancelled, not touched), the provider's
	 * outcome (null when not given to the provider), the provider's error, the time spent on it (from the SENDING write
	 * to the result's).
	 */
	public static final class Handled {
		public final CommunicationMessageStatus status;
		public final Outcome outcome;
		public final String error;
		/** Step 8b: the wait the provider asked for (null: none). */
		public final java.time.Duration retryAfter;
		final long nanos;

		Handled(CommunicationMessageStatus status, Outcome outcome, String error, java.time.Duration retryAfter,
				long nanos) {
			this.status = status;
			this.outcome = outcome;
			this.error = error;
			this.retryAfter = retryAfter;
			this.nanos = nanos;
		}

		static Handled notGiven(CommunicationMessageStatus status) {
			return new Handled(status, null, null, null, 0);
		}
	}

	/**
	 * One message: sent (or not) and saved, through the cycle's batch (null: alone). Step 8a part 2: a channel problem
	 * leaves it waiting as it was (PENDING, its attempts and its next attempt unchanged).
	 */
	Handled handle(CommunicationMessage message, Cycle cycle) {
		if (!campaignRunning(message.getCampaignId())) {
			return Handled.notGiven(null);
		}
		LocalDateTime now = clock.get();
		if (message.getExpiresAt() != null && !now.isBefore(message.getExpiresAt())) {
			return Handled.notGiven(save(message, CommunicationMessageStatus.EXPIRED,
					"expired at " + message.getExpiresAt() + ", not sent"));
		}
		if (!settings.isEnabled(message.getChannel())) {
			return Handled.notGiven(save(message, CommunicationMessageStatus.SKIPPED, "channel "
					+ message.getChannel().toLowerCase(java.util.Locale.ROOT) + " off when it was to be sent"));
		}
		// Step 5: the consent read again just before sending; withdrawn meanwhile (or the member gone): not sent
		String notAllowed = consentRefusal(message);
		if (notAllowed != null) {
			return Handled.notGiven(save(message, CommunicationMessageStatus.SKIPPED, notAllowed));
		}
		CommunicationProvider provider = message.getProvider() == null ? null : providers.get(message.getProvider());
		if (provider == null) {
			return Handled.notGiven(save(message, CommunicationMessageStatus.FAILED,
					"provider " + message.getProvider() + " unknown"));
		}
		long started = System.nanoTime();
		// Step 8a, the test-recipient mode: given to the test address, the member's kept on the row
		String testAddress = settings.testRecipient(message.getChannel());
		message.setRedirectedTo(testAddress != null && !testAddress.equals(message.getAddress()) ? testAddress : null);
		// Step 8b: our reference for the provider ({messageId}), written once, kept when it is sent again
		if (message.getSendRef() == null) {
			message.setSendRef(UUID.randomUUID().toString().replace("-", ""));
		}
		// Step 8a, no double send: SENDING written before the provider is called; left so by a stop, never sent again
		message.setStatus(CommunicationMessageStatus.SENDING);
		messages.save(message);
		SendResult result;
		try {
			CommunicationMessage given = message.sentTo(message.getRedirectedTo());
			result = cycle == null ? provider.send(given) : cycle.batch(provider).send(given);
		} catch (RuntimeException e) {
			result = SendResult.temporaryFailure(ErrorTexts.cause(e));
		}
		if (result == null) {
			result = SendResult.temporaryFailure("no answer from the provider");
		}
		CommunicationMessageStatus status;
		switch (result.getOutcome()) {
			case ACCEPTED:
				message.setAttempts(message.getAttempts() + 1);
				message.setSentAt(now);
				message.setProviderMessageId(result.getProviderMessageId());
				// Step 4: last_error kept, the row's history (a failed attempt before, a message sent again)
				status = save(message, CommunicationMessageStatus.SENT, message.getLastError());
				break;
			case PERMANENT_FAILURE:
				message.setAttempts(message.getAttempts() + 1);
				status = save(message, CommunicationMessageStatus.FAILED, result.getError());
				break;
			case CHANNEL_PROBLEM:
				// Step 8a part 2: the channel's problem, not the message's: waiting again as it was
				status = save(message, CommunicationMessageStatus.PENDING, message.getLastError());
				break;
			case UNCERTAIN:
				// Step 8b: it left, no answer: the provider may have taken it; never sent again by itself
				message.setAttempts(message.getAttempts() + 1);
				status = save(message, CommunicationMessageStatus.FAILED, NO_ANSWER + " (" + result.getError() + ")");
				break;
			default:
				message.setAttempts(message.getAttempts() + 1);
				if (message.getAttempts() >= MAX_ATTEMPTS) {
					status = save(message, CommunicationMessageStatus.FAILED, "attempt " + message.getAttempts() + " of "
							+ MAX_ATTEMPTS + " failed: " + result.getError());
				} else {
					message.setNextAttemptAt(now.plus(WAITS.get(message.getAttempts() - 1)));
					status = save(message, CommunicationMessageStatus.PENDING, "attempt " + message.getAttempts()
							+ " failed: " + result.getError());
				}
		}
		return new Handled(status, result.getOutcome(), result.getError(), result.getRetryAfter(),
				System.nanoTime() - started);
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
