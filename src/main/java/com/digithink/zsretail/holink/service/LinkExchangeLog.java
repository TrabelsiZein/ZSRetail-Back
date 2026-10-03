package com.digithink.zsretail.holink.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeLink;
import com.digithink.zsretail.holink.enumeration.ExchangeDirection;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.model.LinkExchange;
import com.digithink.zsretail.holink.repository.LinkExchangeRepository;

import lombok.extern.log4j.Log4j2;

/**
 * The exchange log of the head office link (task 2.6): one hol_exchange_log row per exchange that sent something or
 * failed, written by the jobs. Writing never makes an exchange fail: each row is saved in its own transaction, and a
 * failure is logged and swallowed. Rows older than headoffice.log-retention-days are purged at most once a day.
 */
@Component
@ConditionalOnHeadOfficeLink
@Log4j2
public class LinkExchangeLog {

	static final Duration PURGE_EVERY = Duration.ofDays(1);
	static final int TIMEOUT_SECONDS = 15;
	static final int DEFAULT_SIZE = 20;
	static final int MAX_SIZE = 200;

	static final LocalDateTime NO_START = LocalDateTime.of(1900, 1, 1, 0, 0);
	static final LocalDateTime NO_END = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

	private final LinkExchangeRepository repository;
	private final TransactionOperations transactions;
	private final int retentionDays;
	private volatile LocalDateTime lastPurge;

	@Autowired
	public LinkExchangeLog(LinkExchangeRepository repository, PlatformTransactionManager transactionManager,
			@Value("${headoffice.log-retention-days:30}") int retentionDays) {
		this(repository, ownTransaction(transactionManager), retentionDays);
	}

	/** With given transactions: used by the tests. */
	public LinkExchangeLog(LinkExchangeRepository repository, TransactionOperations transactions, int retentionDays) {
		this.repository = repository;
		this.transactions = transactions;
		this.retentionDays = retentionDays;
	}

	/** A new transaction, never the caller's: a failure here cannot roll back the exchange's own work. */
	private static TransactionTemplate ownTransaction(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	public int getRetentionDays() {
		return retentionDays;
	}

	/** Writes one row. Never throws: a failure is logged at WARN and the exchange goes on. */
	public void record(String job, ExchangeDirection direction, int records, LinkJobResult result, String error,
			LocalDateTime at, long durationMs) {
		try {
			LinkExchange row = new LinkExchange();
			row.setExchangeDate(at);
			row.setJob(job);
			row.setDirection(direction);
			row.setRecordCount(records);
			row.setResult(result);
			row.setError(error == null || error.length() <= LinkExchange.ERROR_LENGTH ? error
					: error.substring(0, LinkExchange.ERROR_LENGTH));
			row.setDurationMs(durationMs);
			transactions.executeWithoutResult(status -> repository.save(row));
		} catch (RuntimeException e) {
			log.warn("Head office link: exchange log row not written ({} {} {}): {}", job, direction, result,
					SalesCopyFinder.cause(e));
		}
	}

	/** Purges at most once per {@link #PURGE_EVERY} (the first call after the start purges). Never throws. */
	public void purgeIfDue(LocalDateTime now) {
		LocalDateTime last = lastPurge;
		if (last != null && now.isBefore(last.plus(PURGE_EVERY))) {
			return;
		}
		lastPurge = now;
		try {
			int deleted = purge(now);
			if (deleted > 0) {
				log.info("Head office link: {} exchange log rows older than {} days purged", deleted, retentionDays);
			}
		} catch (RuntimeException e) {
			log.warn("Head office link: exchange log purge failed, retried in a day ({})", SalesCopyFinder.cause(e));
		}
	}

	/** Deletes the rows older than the retention; returns how many. */
	int purge(LocalDateTime now) {
		Integer deleted = transactions.execute(status -> repository.deleteOlderThan(now.minusDays(retentionDays)));
		return deleted == null ? 0 : deleted;
	}

	/**
	 * The log page: newest first. job: a job code, blank = every job; result: SUCCESS, WARNING or ERROR (any case),
	 * blank or "all" = every result; dates as yyyy-MM-dd (from: start of the day, to: end of the day) or
	 * yyyy-MM-ddTHH:mm[:ss]; page from 0, size 20 by default, 1 to 200. Throws IllegalArgumentException on a value that
	 * cannot be read. Answer: {content, totalElements, totalPages, number, size}.
	 */
	public Map<String, Object> search(String job, String result, String dateFrom, String dateTo, Integer page,
			Integer size) {
		int pageNumber = page == null ? 0 : page;
		if (pageNumber < 0) {
			throw new IllegalArgumentException("page must be 0 or more");
		}
		int pageSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
		List<LinkJobResult> results;
		if (blank(result) || "all".equalsIgnoreCase(result.trim())) {
			results = Arrays.asList(LinkJobResult.values());
		} else {
			try {
				results = Collections.singletonList(LinkJobResult.valueOf(result.trim().toUpperCase()));
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException(
						"Invalid result '" + result + "': allowed values are " + Arrays.toString(LinkJobResult.values()));
			}
		}
		Page<LinkExchange> rows = repository.search(blank(job) ? "" : job.trim().toUpperCase(), results,
				date(dateFrom, "dateFrom", false), date(dateTo, "dateTo", true), PageRequest.of(pageNumber, pageSize,
						Sort.by(Sort.Order.desc("exchangeDate"), Sort.Order.desc("id"))));
		List<Map<String, Object>> content = new ArrayList<>();
		for (LinkExchange row : rows.getContent()) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", row.getId());
			item.put("exchangeDate", row.getExchangeDate());
			item.put("job", row.getJob());
			item.put("direction", row.getDirection());
			item.put("recordCount", row.getRecordCount());
			item.put("result", row.getResult());
			item.put("error", row.getError());
			item.put("durationMs", row.getDurationMs());
			content.add(item);
		}
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("content", content);
		answer.put("totalElements", rows.getTotalElements());
		answer.put("totalPages", rows.getTotalPages());
		answer.put("number", rows.getNumber());
		answer.put("size", rows.getSize());
		return answer;
	}

	private static LocalDateTime date(String value, String name, boolean end) {
		if (blank(value)) {
			return end ? NO_END : NO_START;
		}
		String text = value.trim();
		try {
			if (text.length() == 10) {
				LocalDate day = LocalDate.parse(text);
				return end ? day.atTime(23, 59, 59, 999_999_900) : day.atStartOfDay();
			}
			return LocalDateTime.parse(text);
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException(
					"Invalid " + name + " '" + value + "': expected yyyy-MM-dd or yyyy-MM-ddTHH:mm");
		}
	}

	private static boolean blank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
