package com.digithink.zsretail.holink.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeOwned;
import com.digithink.zsretail.model.enumeration.DataDomain;

/**
 * Head office plan, step 5: when each member's balance was last refreshed from the head office (a fresh balance asked
 * at the till, or the answer of a change made through the head office), by card, in memory: a restart forgets it and
 * the till asks again. A store whose head office requires it spends points only within {@link #WINDOW} of it.
 */
@Component
@ConditionalOnHeadOfficeOwned(DataDomain.LOYALTY)
public class LoyaltyFreshness {

	public static final Duration WINDOW = Duration.ofMinutes(2);

	private final Map<String, LocalDateTime> refreshed = new ConcurrentHashMap<>();
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public LoyaltyFreshness() {
		this(LocalDateTime::now);
	}

	/** With a given clock: used by the tests. */
	public LoyaltyFreshness(Supplier<LocalDateTime> clock) {
		this.clock = clock;
	}

	/** The card's balance was just received from the head office; returns that time. */
	public LocalDateTime markFresh(String cardNumber) {
		LocalDateTime now = clock.get();
		if (cardNumber != null) {
			refreshed.put(cardNumber, now);
		}
		return now;
	}

	/** True when the card's balance was received from the head office within the window. */
	public boolean isFresh(String cardNumber) {
		LocalDateTime at = cardNumber == null ? null : refreshed.get(cardNumber);
		return at != null && !at.plus(WINDOW).isBefore(clock.get());
	}
}
