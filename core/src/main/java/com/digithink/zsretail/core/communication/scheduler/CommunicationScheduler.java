package com.digithink.zsretail.core.communication.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.communication.service.CommunicationStates;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.utils.ErrorTexts;

import lombok.extern.log4j.Log4j2;

/**
 * 3.3.0, communication module, step 2: runs the module's tasks ({@link CommunicationTask}, in their order) on one
 * thread, comm-1, built like the head office link's scheduler: the first cycle {@link #FIRST_DELAY} after the start,
 * then communication.cycle-seconds (default 60) after the end of the previous one. A cycle stops starting new work
 * after {@link #CYCLE_BOUND}; a task that fails is logged and never stops the next task or the next cycle. At its start
 * the activation instant is written when it is not yet (the first start with the module on). Only with the module on:
 * off, neither this bean nor its thread exists.
 * <p>
 * The thread pool is deliberately not a bean: a TaskScheduler bean would replace Spring Boot's default one, and the
 * existing {@code @Scheduled} jobs (ERP) would move onto it.
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION)
@Log4j2
public class CommunicationScheduler {

	public static final String CYCLE_SECONDS_KEY = "communication.cycle-seconds";
	static final long DEFAULT_CYCLE_SECONDS = 60;
	static final Duration FIRST_DELAY = Duration.ofSeconds(30);
	static final Duration CYCLE_BOUND = Duration.ofSeconds(20);

	private final List<CommunicationTask> tasks;
	private final CommunicationStates states;
	private final long cycleSeconds;
	private final Supplier<Instant> clock;
	private ThreadPoolTaskScheduler taskScheduler;

	/** The tasks in their {@code @Order}. Throws like the startup on a cycle-seconds value that is not a whole number of at least 1. */
	@Autowired
	public CommunicationScheduler(List<CommunicationTask> tasks, CommunicationStates states,
			@Value("${" + CYCLE_SECONDS_KEY + ":}") String cycleSeconds) {
		this(tasks, states, cycleSeconds(cycleSeconds), Instant::now);
	}

	/** With a given clock: used by the tests. */
	CommunicationScheduler(List<CommunicationTask> tasks, CommunicationStates states, long cycleSeconds,
			Supplier<Instant> clock) {
		this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
		this.states = states;
		this.cycleSeconds = cycleSeconds;
		this.clock = clock;
	}

	/** communication.cycle-seconds: absent or empty, 60; otherwise a whole number, at least 1. */
	static long cycleSeconds(String raw) {
		String value = raw == null ? "" : raw.trim();
		if (value.isEmpty()) {
			return DEFAULT_CYCLE_SECONDS;
		}
		try {
			long seconds = Long.parseLong(value);
			if (seconds >= 1) {
				return seconds;
			}
		} catch (NumberFormatException e) {
			// refused below
		}
		throw new IllegalStateException("Invalid value '" + raw + "' for property " + CYCLE_SECONDS_KEY
				+ ": a whole number of seconds, at least 1 (absent or empty: " + DEFAULT_CYCLE_SECONDS + ")");
	}

	@EventListener(ApplicationReadyEvent.class)
	public synchronized void start() {
		if (taskScheduler != null) {
			return;
		}
		LocalDateTime activeSince = states.activeSince();
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("comm-");
		scheduler.setDaemon(true);
		scheduler.initialize();
		taskScheduler = scheduler;
		scheduler.scheduleWithFixedDelay(this::cycle, Instant.now().plus(FIRST_DELAY), Duration.ofSeconds(cycleSeconds));
		log.info("Communication: on since {}, a cycle every {} s on comm-1: {}", activeSince, cycleSeconds,
				tasks.isEmpty() ? "no task" : tasks.stream().map(CommunicationTask::getCode).collect(Collectors.joining(", ")));
	}

	@PreDestroy
	public synchronized void stop() {
		if (taskScheduler != null) {
			taskScheduler.shutdown();
			taskScheduler = null;
		}
	}

	/** One cycle: every task in order, each with the cycle's deadline. Never throws. */
	void cycle() {
		Instant deadline = clock.get().plus(CYCLE_BOUND);
		for (CommunicationTask task : tasks) {
			try {
				String done = task.run(deadline);
				if (done != null) {
					log.info("Communication: {}: {}", task.getCode(), done);
				}
			} catch (RuntimeException e) {
				log.warn("Communication: {} failed, tried again at the next cycle ({})", task.getCode(), ErrorTexts.cause(e));
			}
		}
	}
}
