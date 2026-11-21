package com.digithink.zsretail.core.communication.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationChannelState;
import com.digithink.zsretail.core.communication.provider.TestCommunicationProvider;
import com.digithink.zsretail.core.communication.repository.CommunicationChannelStateRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationMessageRepository;
import com.digithink.zsretail.core.communication.scheduler.CommunicationScheduler;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;

import lombok.extern.log4j.Log4j2;

/**
 * 3.3.0, communication step 8a part 2: each channel's pause and measured speed (comm_channel_state), where the messages
 * are sent. Written by the sender (comm-1) and the Canaux tab's test (a request thread): every change reads the row
 * again, under this bean's lock.
 * <ul>
 * <li>A channel problem (CommunicationProvider.Outcome.CHANNEL_PROBLEM) pauses the channel: the next try after
 * {@link #PAUSES} (1, 5, 15, then every 60 min), the messages waiting without using their attempts. A message accepted
 * on the channel, or a test accepted on the Canaux tab, ends the pause. A test that meets the problem again leaves the
 * wait as it is (a click never makes it longer). A restart keeps the pause.</li>
 * <li>The speed: the messages handed to the provider in each cycle and the time the sender spent on them (its reads and
 * writes included), about the last {@link #SPEED_WINDOW} messages, per provider. {@link #perHour}: that speed times the
 * sender's share of an hour while a queue waits (it sends for at most the cycle's bound, 20 s, then waits 5 s, step 8b;
 * communication.cycle-seconds before). Not given for the provider test (it sends nothing) or a measure older than
 * {@link #RECENT}.</li>
 * </ul>
 * See docs/modules/communication.md, "Step 8a, part 2".
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION_SENDS_HERE)
@Log4j2
public class CommunicationChannelStates {

	/** The pause after the 1st, 2nd, 3rd problem in a row; from the 4th, every 60 min. */
	static final List<Duration> PAUSES = Arrays.asList(Duration.ofMinutes(1), Duration.ofMinutes(5),
			Duration.ofMinutes(15), Duration.ofMinutes(60));
	static final long SPEED_WINDOW = 1000;
	static final Duration RECENT = Duration.ofDays(30);
	/** The reason of a message expired while its channel was paused. */
	public static final String EXPIRED_WHILE_PAUSED = "expired while its channel was paused, not sent";

	private final CommunicationChannelStateRepository states;
	private final CommunicationMessageRepository messages;
	private final Supplier<LocalDateTime> clock;
	private final long cycleSeconds;

	@Autowired
	public CommunicationChannelStates(CommunicationChannelStateRepository states,
			CommunicationMessageRepository messages,
			@Value("${" + CommunicationScheduler.CYCLE_SECONDS_KEY + ":}") String cycleSeconds) {
		this(states, messages, LocalDateTime::now, CommunicationScheduler.cycleSeconds(cycleSeconds));
	}

	/** With a given clock and cycle: used by the tests. */
	public CommunicationChannelStates(CommunicationChannelStateRepository states,
			CommunicationMessageRepository messages, Supplier<LocalDateTime> clock, long cycleSeconds) {
		this.states = states;
		this.messages = messages;
		this.clock = clock;
		this.cycleSeconds = cycleSeconds;
	}

	/** Every channel's row, by channel (at most one per channel). */
	public synchronized Map<String, CommunicationChannelState> all() {
		Map<String, CommunicationChannelState> rows = new LinkedHashMap<>();
		states.findAll().forEach(row -> rows.put(row.getChannel(), row));
		return rows;
	}

	/** The channel's row, null when it was never paused nor measured. */
	public synchronized CommunicationChannelState of(String channel) {
		return states.findByChannel(channel).orElse(null);
	}

	/**
	 * A channel problem: the channel paused (its first problem) or its pause one step longer. From a test: a pause
	 * running keeps its wait (only its reason changes); no pause yet: the first wait. Returns the row as saved.
	 */
	public synchronized CommunicationChannelState problem(String channel, String reason, boolean fromTest) {
		return problem(channel, reason, fromTest, null);
	}

	/**
	 * Step 8b: a channel problem with the wait the provider asked for (HTTP 429 Retry-After): the next try after that
	 * wait instead of the pause's own (1 s to 24 h); null: the pause's own.
	 */
	public synchronized CommunicationChannelState problem(String channel, String reason, boolean fromTest,
			Duration providerWait) {
		LocalDateTime now = clock.get();
		CommunicationChannelState row = states.findByChannel(channel).orElseGet(() -> fresh(channel));
		boolean running = row.isPaused();
		if (!running) {
			row.setPausedSince(now);
			row.setProblems(0);
		}
		if (!(fromTest && running)) {
			row.setProblems(row.getProblems() + 1);
			row.setPausedUntil(now.plus(providerWait != null ? providerWait : pause(row.getProblems())));
		}
		row.setPauseReason(reason == null || reason.length() <= CommunicationChannelState.PAUSE_REASON_LENGTH ? reason
				: reason.substring(0, CommunicationChannelState.PAUSE_REASON_LENGTH));
		row.setUpdatedAt(now);
		CommunicationChannelState saved = states.save(row);
		log.warn("Communication: channel {} paused until {} ({} problem(s) in a row since {}{}): {}", channel,
				saved.getPausedUntil(), saved.getProblems(), saved.getPausedSince(), fromTest ? ", a test" : "", reason);
		return saved;
	}

	/** The wait after the n-th problem in a row. */
	static Duration pause(int problems) {
		return PAUSES.get(Math.max(1, Math.min(problems, PAUSES.size())) - 1);
	}

	/** A message accepted on the channel: its pause ends. True when it had one. */
	public synchronized boolean lift(String channel, String why) {
		CommunicationChannelState row = states.findByChannel(channel).orElse(null);
		if (row == null || !row.isPaused()) {
			return false;
		}
		LocalDateTime since = row.getPausedSince();
		row.setPausedSince(null);
		row.setPausedUntil(null);
		row.setProblems(0);
		row.setPauseReason(null);
		row.setUpdatedAt(clock.get());
		states.save(row);
		log.info("Communication: channel {} sends again ({}), paused since {}", channel, why, since);
		return true;
	}

	/**
	 * A paused channel's messages past their expiry: EXPIRED with {@link #EXPIRED_WHILE_PAUSED}, in one statement (the
	 * sender does not read them while the channel waits). Returns how many.
	 */
	@Transactional
	public int expireWhilePaused(String channel) {
		return messages.expireWaiting(channel, CommunicationMessageStatus.PENDING, CommunicationMessageStatus.EXPIRED,
				EXPIRED_WHILE_PAUSED, clock.get());
	}

	/** A cycle's messages handed to the provider and the time spent on them: added to the measure (the last ~1,000). */
	public synchronized void measured(String channel, String provider, long count, long millis) {
		if (count <= 0 || provider == null) {
			return;
		}
		CommunicationChannelState row = states.findByChannel(channel).orElseGet(() -> fresh(channel));
		long total = provider.equals(row.getSpeedProvider()) ? row.getSpeedMessages() : 0;
		long spent = provider.equals(row.getSpeedProvider()) ? row.getSpeedMillis() : 0;
		total += count;
		spent += Math.max(0, millis);
		if (total > SPEED_WINDOW) {
			spent = spent * SPEED_WINDOW / total;
			total = SPEED_WINDOW;
		}
		LocalDateTime now = clock.get();
		row.setSpeedMessages(total);
		row.setSpeedMillis(spent);
		row.setSpeedProvider(provider);
		row.setSpeedAt(now);
		row.setUpdatedAt(now);
		states.save(row);
	}

	/**
	 * About how many messages an hour the channel sends while a queue waits, from its recent measure on this provider;
	 * null without one (never measured, another provider, older than {@link #RECENT}, the provider test).
	 */
	public Integer perHour(String channel, String provider) {
		if (provider == null || TestCommunicationProvider.NAME.equals(provider)) {
			return null;
		}
		CommunicationChannelState row = of(channel);
		if (row == null || row.getSpeedMessages() <= 0 || !provider.equals(row.getSpeedProvider())
				|| row.getSpeedAt() == null || row.getSpeedAt().isBefore(clock.get().minus(RECENT))) {
			return null;
		}
		return perHour(row.getSpeedMessages(), row.getSpeedMillis(), cycleSeconds);
	}

	/**
	 * The speed (messages in millis) times the sender's share of an hour: the cycle's bound, then the wait. Step 8b: while
	 * messages wait, the next cycle comes after CommunicationScheduler.FAST_DELAY (5 s), not communication.cycle-seconds.
	 */
	static int perHour(long messages, long millis, long cycleSeconds) {
		double perMilli = messages / (double) Math.max(1, millis);
		double bound = CommunicationScheduler.CYCLE_BOUND.getSeconds();
		double share = bound / (bound + CommunicationScheduler.busyDelay(cycleSeconds).getSeconds());
		return (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(perMilli * 3_600_000d * share)));
	}

	/** The pause as the page shows it: {since, until, problems, reason}; null when the channel is not paused. */
	public static Map<String, Object> pauseView(CommunicationChannelState row) {
		if (row == null || !row.isPaused()) {
			return null;
		}
		Map<String, Object> pause = new LinkedHashMap<>();
		pause.put("since", row.getPausedSince());
		pause.put("until", row.getPausedUntil());
		pause.put("problems", row.getProblems());
		pause.put("reason", row.getPauseReason());
		return pause;
	}

	/** How many messages of the channel wait (PENDING): sent before a new campaign's. */
	public long waiting(String channel) {
		return messages.countByChannelAndStatus(channel, CommunicationMessageStatus.PENDING);
	}

	private static CommunicationChannelState fresh(String channel) {
		CommunicationChannelState row = new CommunicationChannelState();
		row.setChannel(channel);
		return row;
	}

}
