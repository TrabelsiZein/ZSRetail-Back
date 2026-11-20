package com.digithink.zsretail.core.communication.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider.SendResult;
import com.digithink.zsretail.core.communication.repository.CommunicationMessageRepository;
import com.digithink.zsretail.core.communication.scheduler.CommunicationTask;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
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
	private final CommunicationSettings settings;
	private final Map<String, CommunicationProvider> providers = new LinkedHashMap<>();
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public CommunicationSender(CommunicationMessageRepository messages, CommunicationSettings settings,
			List<CommunicationProvider> providers) {
		this(messages, settings, providers, LocalDateTime::now);
	}

	/** With a given clock: used by the tests. */
	public CommunicationSender(CommunicationMessageRepository messages, CommunicationSettings settings,
			List<CommunicationProvider> providers, Supplier<LocalDateTime> clock) {
		this.messages = messages;
		this.settings = settings;
		providers.forEach(provider -> this.providers.put(provider.getName(), provider));
		this.clock = clock;
	}

	@Override
	public String getCode() {
		return CODE;
	}

	@Override
	public String run(Instant deadline) {
		Map<CommunicationMessageStatus, Integer> counts = new LinkedHashMap<>();
		int retried = 0;
		long afterId = 0;
		while (Instant.now().isBefore(deadline)) {
			List<CommunicationMessage> due = messages.findDue(CommunicationMessageStatus.PENDING, clock.get(), afterId,
					PageRequest.of(0, PAGE_SIZE));
			if (due.isEmpty()) {
				break;
			}
			for (CommunicationMessage message : due) {
				afterId = message.getId();
				if (!Instant.now().isBefore(deadline)) {
					break;
				}
				CommunicationMessageStatus status = handle(message);
				if (status == CommunicationMessageStatus.PENDING) {
					retried++;
				} else {
					counts.merge(status, 1, Integer::sum);
				}
			}
			if (due.size() < PAGE_SIZE) {
				break;
			}
		}
		if (counts.isEmpty() && retried == 0) {
			return null;
		}
		StringBuilder done = new StringBuilder();
		counts.forEach((status, n) -> done.append(done.length() == 0 ? "" : ", ").append(n).append(' ')
				.append(status.name().toLowerCase(java.util.Locale.ROOT)));
		if (retried > 0) {
			done.append(done.length() == 0 ? "" : ", ").append(retried).append(" to try again");
		}
		return done.toString();
	}

	/** One message: sent (or not) and saved. Returns its new status. */
	CommunicationMessageStatus handle(CommunicationMessage message) {
		LocalDateTime now = clock.get();
		if (message.getExpiresAt() != null && !now.isBefore(message.getExpiresAt())) {
			return save(message, CommunicationMessageStatus.EXPIRED, "expired at " + message.getExpiresAt() + ", not sent");
		}
		if (!settings.isEnabled(message.getChannel())) {
			return save(message, CommunicationMessageStatus.SKIPPED, "channel "
					+ message.getChannel().toLowerCase(java.util.Locale.ROOT) + " off when it was to be sent");
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

	private CommunicationMessageStatus save(CommunicationMessage message, CommunicationMessageStatus status, String error) {
		message.setStatus(status);
		message.setLastError(error == null || error.length() <= CommunicationMessage.LAST_ERROR_LENGTH ? error
				: error.substring(0, CommunicationMessage.LAST_ERROR_LENGTH));
		messages.save(message);
		return status;
	}
}
