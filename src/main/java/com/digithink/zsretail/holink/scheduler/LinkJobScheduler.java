package com.digithink.zsretail.holink.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import javax.annotation.PreDestroy;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeLink;
import com.digithink.zsretail.holink.dto.LinkJobDTO;
import com.digithink.zsretail.holink.dto.LinkJobRun;
import com.digithink.zsretail.holink.service.LinkExchangeLog;
import com.digithink.zsretail.holink.service.LinkJobService;
import com.digithink.zsretail.holink.service.SalesCopyFinder;

import lombok.extern.log4j.Log4j2;

/**
 * Runs the jobs of the head office link (task 2.6) on one thread, ho-link-1: the jobs never run at the same time, and
 * only this thread calls the head office; no request, sale or session waits for it. Each job: first run after its
 * first delay, then its frequency (saved or default, read at each run) after the end of the previous run, or
 * {@link #CATCH_UP_DELAY} while it has more to do. Each run is recorded (last run, result, message, duration) and the
 * exchange log is purged when due.
 * <p>
 * The thread pool is deliberately not a bean: a TaskScheduler bean would replace Spring Boot's default one, and the
 * existing {@code @Scheduled} jobs (ERP, franchise) would move onto it.
 */
@Component
@ConditionalOnHeadOfficeLink
@Log4j2
public class LinkJobScheduler {

	static final Duration CATCH_UP_DELAY = Duration.ofSeconds(5);

	/** Longest wait of "Run now": a run in progress plus its own. */
	static final Duration RUN_NOW_TIMEOUT = Duration.ofSeconds(30);

	private final List<LinkJob> jobs;
	private final LinkJobService jobService;
	private final LinkExchangeLog exchangeLog;

	private final Map<String, Boolean> catchingUp = new ConcurrentHashMap<>();
	private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
	private ThreadPoolTaskScheduler taskScheduler;
	private Instant startedAt;

	/** The jobs in their {@code @Order}. */
	public LinkJobScheduler(List<LinkJob> jobs, LinkJobService jobService, LinkExchangeLog exchangeLog) {
		this.jobs = Collections.unmodifiableList(new ArrayList<>(jobs));
		this.jobService = jobService;
		this.exchangeLog = exchangeLog;
	}

	@EventListener(ApplicationReadyEvent.class)
	public synchronized void start() {
		if (taskScheduler != null) {
			return;
		}
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("ho-link-");
		scheduler.setDaemon(true);
		scheduler.initialize();
		taskScheduler = scheduler;
		startedAt = Instant.now();
		for (LinkJob job : jobs) {
			schedule(job, startedAt.plus(job.getFirstDelay()));
		}
		log.info("Head office link: jobs on ho-link-1: {}", jobs.stream()
				.map(job -> job.getCode() + " every " + jobService.intervalSeconds(job) + " s").collect(Collectors.joining(", ")));
	}

	@PreDestroy
	public synchronized void stop() {
		if (taskScheduler != null) {
			taskScheduler.shutdown();
			taskScheduler = null;
			futures.clear();
		}
	}

	public List<LinkJob> getJobs() {
		return jobs;
	}

	public Optional<LinkJob> job(String code) {
		return jobs.stream().filter(job -> job.getCode().equalsIgnoreCase(code == null ? "" : code.trim())).findFirst();
	}

	/** First run at {@code firstRun}, then {@link #nextRun}. */
	private void schedule(LinkJob job, Instant firstRun) {
		futures.put(job.getCode(), taskScheduler.schedule(() -> execute(job), context -> nextRun(job, context, firstRun)));
	}

	Date nextRun(LinkJob job, TriggerContext context, Instant firstRun) {
		Date last = context.lastCompletionTime();
		if (last == null) {
			return Date.from(firstRun);
		}
		return new Date(last.getTime() + nextDelay(job).toMillis());
	}

	/** The catch-up delay while the job has more to do, otherwise its frequency in force. */
	Duration nextDelay(LinkJob job) {
		if (Boolean.TRUE.equals(catchingUp.get(job.getCode()))) {
			return CATCH_UP_DELAY;
		}
		try {
			return Duration.ofSeconds(jobService.intervalSeconds(job));
		} catch (RuntimeException e) {
			return Duration.ofSeconds(job.getDefaultIntervalSeconds());
		}
	}

	/** One run of a job, recorded. Never throws: a job that throws is recorded as an ERROR run. */
	LinkJobRun execute(LinkJob job) {
		LocalDateTime at = LocalDateTime.now();
		long started = System.nanoTime();
		LinkJobRun run;
		try {
			run = job.run();
			if (run == null) {
				run = LinkJobRun.error("no result");
			}
		} catch (RuntimeException e) {
			run = LinkJobRun.error("job failed (" + SalesCopyFinder.cause(e) + ")");
			log.warn("Head office link: job {} failed, next run at its usual time ({})", job.getCode(),
					SalesCopyFinder.cause(e));
		}
		catchingUp.put(job.getCode(), run.isRunAgainSoon());
		jobService.recordRun(job.getCode(), run, at, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
		exchangeLog.afterRun(job.getCode(), run.getResult(), LocalDateTime.now()); // step 5: one row when it works again
		exchangeLog.purgeIfDue(LocalDateTime.now());
		return run;
	}

	/**
	 * "Run now": one run of the job on the ho-link thread, queued behind a run in progress, waited for up to
	 * {@link #RUN_NOW_TIMEOUT}. Returns false when the wait ended first or the jobs are not started (no run then from
	 * the request thread).
	 */
	public boolean runNow(LinkJob job) {
		ThreadPoolTaskScheduler scheduler;
		synchronized (this) {
			scheduler = taskScheduler;
		}
		if (scheduler == null) {
			return false;
		}
		try {
			scheduler.submit(() -> execute(job)).get(RUN_NOW_TIMEOUT.getSeconds(), TimeUnit.SECONDS);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
			log.debug("Head office link: run now of {} did not complete ({})", job.getCode(), e.toString());
		}
		return false;
	}

	/** After a frequency change: the next run comes one new frequency from now. */
	public synchronized void reschedule(LinkJob job) {
		if (taskScheduler == null) {
			return;
		}
		ScheduledFuture<?> current = futures.remove(job.getCode());
		if (current != null) {
			current.cancel(false); // a run in progress finishes
		}
		catchingUp.remove(job.getCode());
		schedule(job, Instant.now().plus(nextDelay(job)));
	}

	/** The jobs list of the page, in order. */
	public List<LinkJobDTO> views() {
		return jobs.stream().map(this::view).collect(Collectors.toList());
	}

	public LinkJobDTO view(LinkJob job) {
		LinkJobService.Memory state = jobService.state(job.getCode());
		ScheduledFuture<?> future = futures.get(job.getCode());
		LocalDateTime nextRunAt = future == null || future.isCancelled() ? null
				: LocalDateTime.now().plusNanos(TimeUnit.MILLISECONDS.toNanos(Math.max(0, future.getDelay(TimeUnit.MILLISECONDS))));
		return new LinkJobDTO(job.getCode(), jobService.intervalSeconds(job), job.getDefaultIntervalSeconds(),
				state.getIntervalSeconds() != null, LinkJobService.MIN_INTERVAL_SECONDS,
				LinkJobService.MAX_INTERVAL_SECONDS, state.getLastRunAt(), state.getLastResult(), state.getLastMessage(),
				state.getLastDurationMs(), nextRunAt);
	}
}
