package com.digithink.zsretail.holink.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficePull;
import com.digithink.zsretail.holink.dto.DownRecordRowDTO;
import com.digithink.zsretail.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.holink.model.DownRecord;
import com.digithink.zsretail.holink.repository.DownRecordRepository;
import com.digithink.zsretail.model.enumeration.DataDomain;

/**
 * Head office plan, task 3.5: the store's tracking of the records received from the head office (hol_down_record), one
 * row per domain and code: APPLIED, WAITING or ERROR with the reason, and the copy last received for the retries. A
 * row is written only when something changed, so applying the same answer twice writes nothing. Generic: used by the
 * handler of each domain.
 */
@Component
@ConditionalOnHeadOfficePull
public class DownRecordLog {

	static final List<DownRecordStatus> TO_RETRY = Arrays.asList(DownRecordStatus.WAITING, DownRecordStatus.ERROR);

	private final DownRecordRepository repository;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public DownRecordLog(DownRecordRepository repository) {
		this(repository, LocalDateTime::now);
	}

	/** With a given clock: used by the tests. */
	public DownRecordLog(DownRecordRepository repository, Supplier<LocalDateTime> clock) {
		this.repository = repository;
		this.clock = clock;
	}

	/**
	 * Records what the store did with a record, inside the caller's transaction. payload null keeps the stored copy (a
	 * retry). Texts are cut to 1000 characters. Returns true when the row was written (new, or something changed).
	 */
	public boolean track(DataDomain domain, String code, String name, DownRecordStatus status, String reason,
			String info, String payload) {
		Optional<DownRecord> found = repository.findByDomainAndRecordCode(domain, code);
		DownRecord row = found.orElseGet(DownRecord::new);
		String cutReason = cut(reason);
		String cutInfo = cut(info);
		String newPayload = payload == null ? row.getPayload() : payload;
		String newName = name == null ? row.getRecordName() : name;
		boolean payloadChanged = !Objects.equals(newPayload, row.getPayload());
		boolean statusChanged = row.getStatus() != status;
		if (found.isPresent() && !payloadChanged && !statusChanged && Objects.equals(cutReason, row.getReason())
				&& Objects.equals(cutInfo, row.getInfo()) && Objects.equals(newName, row.getRecordName())) {
			return false;
		}
		LocalDateTime now = clock.get();
		row.setDomain(domain);
		row.setRecordCode(code);
		row.setRecordName(newName);
		row.setReason(cutReason);
		row.setInfo(cutInfo);
		if (!found.isPresent() || payloadChanged) {
			row.setPayload(newPayload);
			row.setReceivedAt(now);
		}
		if (!found.isPresent() || statusChanged) {
			row.setStatus(status);
			row.setStatusSince(now);
		}
		repository.save(row);
		return true;
	}

	public Optional<DownRecord> find(DataDomain domain, String code) {
		return repository.findByDomainAndRecordCode(domain, code);
	}

	/** Deletes the row of a record removed for this store; true when there was one. */
	public boolean remove(DataDomain domain, String code) {
		Optional<DownRecord> row = repository.findByDomainAndRecordCode(domain, code);
		row.ifPresent(repository::delete);
		return row.isPresent();
	}

	/** The rows WAITING or in ERROR, retried at every cycle, by code. */
	public List<DownRecord> toRetry(DataDomain domain) {
		List<DownRecord> rows = new ArrayList<>(repository.findByDomainAndStatusIn(domain, TO_RETRY));
		rows.sort(Comparator.comparing(DownRecord::getRecordCode));
		return rows;
	}

	/** {APPLIED: n, WAITING: n, ERROR: n}, every status present. */
	public Map<String, Long> counts(DataDomain domain) {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (DownRecordStatus status : DownRecordStatus.values()) {
			counts.put(status.name(), 0L);
		}
		for (Object[] row : repository.countByStatus(domain)) {
			counts.put(((DownRecordStatus) row[0]).name(), ((Number) row[1]).longValue());
		}
		return counts;
	}

	/** Rows per page of the list: 20 by default, at most 200. */
	static final int LIST_DEFAULT_SIZE = 20;
	static final int LIST_MAX_SIZE = 200;

	/**
	 * The list of the link page, paged by the database: {domain, counts: {APPLIED, WAITING, ERROR}, records: [{code,
	 * name, status, reason, info, receivedAt, statusSince}], totalElements, page, size}. Only the records to check
	 * (WAITING and ERROR): ERROR first, then WAITING, each by code; APPLIED rows are never listed (the counts give them).
	 * The payload is never read. status: WAITING or ERROR (any case), blank or "all" = both; IllegalArgumentException
	 * otherwise (APPLIED included). page from 0 (default 0), size 1 to 200 (default 20), as the loyalty list.
	 */
	public Map<String, Object> list(DataDomain domain, String status, Integer page, Integer size) {
		Collection<DownRecordStatus> statuses = parseStatuses(status);
		int number = page == null || page < 0 ? 0 : page;
		int pageSize = size == null || size < 1 ? LIST_DEFAULT_SIZE : Math.min(size, LIST_MAX_SIZE);
		Page<DownRecordRowDTO> rows = repository.findToCheck(domain, statuses, PageRequest.of(number, pageSize));
		List<Map<String, Object>> records = new ArrayList<>();
		for (DownRecordRowDTO row : rows.getContent()) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("code", row.getRecordCode());
			item.put("name", row.getRecordName());
			item.put("status", row.getStatus());
			item.put("reason", row.getReason());
			item.put("info", row.getInfo());
			item.put("receivedAt", row.getReceivedAt());
			item.put("statusSince", row.getStatusSince());
			records.add(item);
		}
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("domain", domain.name());
		answer.put("counts", counts(domain));
		answer.put("records", records);
		answer.put("totalElements", rows.getTotalElements());
		answer.put("page", number);
		answer.put("size", pageSize);
		return answer;
	}

	/** The statuses listed: WAITING and ERROR, or one of them. */
	private static Collection<DownRecordStatus> parseStatuses(String status) {
		if (status == null || status.trim().isEmpty() || "all".equalsIgnoreCase(status.trim())) {
			return TO_RETRY;
		}
		String name = status.trim().toUpperCase();
		for (DownRecordStatus listed : TO_RETRY) {
			if (listed.name().equals(name)) {
				return Collections.singleton(listed);
			}
		}
		throw new IllegalArgumentException("Invalid status '" + status + "': allowed values are " + TO_RETRY
				+ " (the applied records are not listed, the counts give them)");
	}

	private static String cut(String text) {
		return text == null || text.length() <= DownRecord.TEXT_LENGTH ? text : text.substring(0, DownRecord.TEXT_LENGTH);
	}
}
