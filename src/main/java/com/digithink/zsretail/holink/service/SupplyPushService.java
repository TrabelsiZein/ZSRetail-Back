package com.digithink.zsretail.holink.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeSupply;
import com.digithink.zsretail.headoffice.dto.DeliveryConfirmationDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyResultDTO;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.SalesPushAnswer;
import com.digithink.zsretail.holink.enumeration.ExchangeDirection;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.model.ReceivedDelivery;
import com.digithink.zsretail.holink.model.ReceivedDeliveryLine;
import com.digithink.zsretail.holink.repository.ReceivedDeliveryRepository;

import lombok.Getter;
import lombok.ToString;

/**
 * Head office plan, task 7A.4: sends up the store's BL confirmations, run by the SUPPLY_PUSH job on the ho-link thread.
 * A confirmed BL (push_status PENDING) is sent in a batch, by its number; accepted: SENT, never sent again; rejected:
 * ERROR with the reason, retried at later cycles after the BLs never tried. Not delivered (unreachable, key refused,
 * no license, unreadable answer): nothing changes, the cycle stops. The head office applies a BL number once, so a
 * confirmation sent again after a lost answer changes nothing there. One exchange log row per batch; a failure that
 * repeats is written once.
 */
@Service
@ConditionalOnHeadOfficeSupply
public class SupplyPushService {

	public static final String JOB_CODE = "SUPPLY_PUSH";

	static final int BATCH_SIZE = 20;
	static final int BATCHES = 2;
	static final Duration CYCLE_BOUND = Duration.ofSeconds(20);
	static final int TIMEOUT_SECONDS = 15;

	static final List<SalesCopyStatus> TO_SEND = Arrays.asList(SalesCopyStatus.PENDING, SalesCopyStatus.ERROR);

	private final HeadOfficeClient client;
	private final ReceivedDeliveryRepository deliveries;
	private final LinkExchangeLog exchangeLog;
	private final TransactionOperations transactions;
	private final Supplier<LocalDateTime> clock;
	private final long defaultIntervalSeconds;

	@Autowired
	public SupplyPushService(HeadOfficeClient client, ReceivedDeliveryRepository deliveries, LinkExchangeLog exchangeLog,
			PlatformTransactionManager transactionManager,
			@Value("${headoffice.supply-push.interval-seconds:60}") long intervalSeconds) {
		this(client, deliveries, exchangeLog, timed(transactionManager), LocalDateTime::now, intervalSeconds);
	}

	/** With given transactions and clock: used by the tests. */
	public SupplyPushService(HeadOfficeClient client, ReceivedDeliveryRepository deliveries, LinkExchangeLog exchangeLog,
			TransactionOperations transactions, Supplier<LocalDateTime> clock, long intervalSeconds) {
		this.client = client;
		this.deliveries = deliveries;
		this.exchangeLog = exchangeLog;
		this.transactions = transactions;
		this.clock = clock;
		this.defaultIntervalSeconds = intervalSeconds;
	}

	private static TransactionTemplate timed(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	public long getDefaultIntervalSeconds() {
		return defaultIntervalSeconds;
	}

	/** One cycle. Never throws for a batch: a failure is in the cycle. */
	public Cycle runCycle() {
		Cycle cycle = new Cycle();
		long started = System.nanoTime();
		for (int batch = 0; batch < BATCHES && cycle.delivering(); batch++) {
			if (batch > 0 && System.nanoTime() - started > CYCLE_BOUND.toNanos()) {
				cycle.more = true;
				break;
			}
			// The first batch retries the rejected confirmations too; the next one takes those never sent
			if (!pushConfirmations(cycle, batch == 0 ? TO_SEND : Collections.singletonList(SalesCopyStatus.PENDING))) {
				break;
			}
		}
		return cycle;
	}

	private boolean pushConfirmations(Cycle cycle, Collection<SalesCopyStatus> statuses) {
		List<ReceivedDelivery> rows = transactions
				.execute(status -> deliveries.findPushQueue(statuses, PageRequest.of(0, BATCH_SIZE)));
		rows = rows == null ? new ArrayList<>() : new ArrayList<>(rows);
		rows.removeIf(row -> cycle.tried.contains(row.getId()));
		if (rows.isEmpty()) {
			return false;
		}
		LocalDateTime at = clock.get();
		long started = System.nanoTime();
		List<DeliveryConfirmationDTO> body = new ArrayList<>();
		for (ReceivedDelivery row : rows) {
			cycle.tried.add(row.getId());
			body.add(confirmationOf(row));
		}
		SalesPushAnswer answer = client.pushDeliveryConfirmations(body);
		if (!answer.isDelivered()) {
			cycle.notDelivered(answer.getState(), answer.getMessage());
			exchangeLog.recordFailure(JOB_CODE, ExchangeDirection.UP, 0, answer.getState() + ": " + answer.getMessage(),
					at, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
			return false;
		}
		cycle.state = HeadOfficeLinkState.ONLINE;
		Map<String, SalesCopyResultDTO> results = new HashMap<>();
		for (SalesCopyResultDTO result : answer.getResults()) {
			if (result != null && result.getDocumentNumber() != null) {
				results.putIfAbsent(result.getDocumentNumber(), result);
			}
		}
		int accepted = 0;
		int rejected = 0;
		String firstProblem = null;
		for (ReceivedDelivery row : rows) {
			SalesCopyResultDTO result = results.get(row.getNumber());
			if (result != null && result.isAccepted()) {
				transactions.executeWithoutResult(status -> mark(row.getId(), SalesCopyStatus.SENT, null, at));
				accepted++;
			} else {
				String reason = result == null ? "no result from the head office for this BL"
						: result.getMessage() == null ? "rejected" : result.getMessage();
				transactions.executeWithoutResult(status -> mark(row.getId(), SalesCopyStatus.ERROR, reason, at));
				rejected++;
				firstProblem = firstProblem == null ? row.getNumber() + ": " + reason : firstProblem;
			}
		}
		cycle.confirmationsSent += accepted;
		cycle.confirmationsRejected += rejected;
		exchangeLog.record(JOB_CODE, ExchangeDirection.UP, rows.size(),
				accepted == 0 ? LinkJobResult.ERROR : rejected > 0 ? LinkJobResult.WARNING : LinkJobResult.SUCCESS,
				firstProblem, at, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
		cycle.more |= rows.size() >= BATCH_SIZE;
		return true;
	}

	/** The tracking columns of the row as it is now (read again: the user may not change it, but the row is fresh). */
	private void mark(Long id, SalesCopyStatus status, String error, LocalDateTime at) {
		deliveries.findById(id).ifPresent(row -> {
			row.setPushStatus(status);
			row.setAttempts(row.getAttempts() + 1);
			row.setLastError(error == null ? null : DeliveryReceptionService.cut(error, ReceivedDelivery.LAST_ERROR_LENGTH));
			row.setLastPushDate(at);
			deliveries.save(row);
		});
	}

	static DeliveryConfirmationDTO confirmationOf(ReceivedDelivery delivery) {
		DeliveryConfirmationDTO confirmation = new DeliveryConfirmationDTO();
		confirmation.setNumber(delivery.getNumber());
		confirmation.setReceivedAt(delivery.getReceivedAt() == null ? null
				: delivery.getReceivedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
		confirmation.setReceivedBy(delivery.getReceivedBy());
		confirmation.setNote(delivery.getStoreNote());
		for (ReceivedDeliveryLine line : delivery.getLines()) {
			confirmation.getLines()
					.add(new DeliveryConfirmationDTO.Line(line.getLineNo(), line.getItemCode(), line.getQuantityReceived()));
		}
		return confirmation;
	}

	/** Outcome of one cycle. */
	@Getter
	@ToString
	public static final class Cycle {

		private int confirmationsSent;
		private int confirmationsRejected;

		/** State of the last request; null when nothing was sent. */
		private HeadOfficeLinkState state;
		private String message;

		/** More waits: the next cycle comes soon. */
		private boolean more;

		@ToString.Exclude
		private final List<Long> tried = new ArrayList<>();

		boolean delivering() {
			return state == null || state == HeadOfficeLinkState.ONLINE;
		}

		void notDelivered(HeadOfficeLinkState state, String message) {
			this.state = state;
			this.message = message;
			this.more = false;
		}

		public boolean isDelivered() {
			return delivering();
		}

		public boolean isIdle() {
			return confirmationsSent + confirmationsRejected == 0 && delivering();
		}
	}
}
