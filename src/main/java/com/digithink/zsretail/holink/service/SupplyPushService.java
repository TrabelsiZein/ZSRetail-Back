package com.digithink.zsretail.holink.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import com.digithink.zsretail.headoffice.dto.StockReportDTO;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.SalesPushAnswer;
import com.digithink.zsretail.holink.enumeration.ExchangeDirection;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.model.ReceivedDelivery;
import com.digithink.zsretail.holink.model.ReceivedDeliveryLine;
import com.digithink.zsretail.holink.model.StockCopy;
import com.digithink.zsretail.holink.repository.ReceivedDeliveryRepository;
import com.digithink.zsretail.holink.repository.StockCopyRepository;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.utils.Quantities;

import lombok.Getter;
import lombok.ToString;

/**
 * Head office plan, tasks 7A.4 and 7A.5: sends up the store's BL confirmations, then its stock, run by the SUPPLY_PUSH
 * job on the ho-link thread.
 * A confirmed BL (push_status PENDING) is sent in a batch, by its number; accepted: SENT, never sent again; rejected:
 * ERROR with the reason, retried at later cycles after the BLs never tried. Not delivered (unreachable, key refused,
 * no license, unreadable answer): nothing changes, the cycle stops. The head office applies a BL number once, so a
 * confirmation sent again after a lost answer changes nothing there. One exchange log row per batch; a failure that
 * repeats is written once.
 * <p>
 * Stock (task 7A.5): after the confirmations, the items whose stock differs from what the head office accepted last
 * (hol_stock_copy) and the items gone since, in batches of {@value #STOCK_BATCH_SIZE} (POST /ho/supply/stock).
 */
@Service
@ConditionalOnHeadOfficeSupply
public class SupplyPushService {

	public static final String JOB_CODE = "SUPPLY_PUSH";

	static final int BATCH_SIZE = 20;
	static final int BATCHES = 2;
	static final Duration CYCLE_BOUND = Duration.ofSeconds(20);
	static final int TIMEOUT_SECONDS = 15;

	static final int STOCK_BATCH_SIZE = 500;
	static final int STOCK_BATCHES = 2;
	static final List<ItemType> STOCK_TYPES = Arrays.asList(ItemType.PRODUCT, ItemType.PACKAGE);

	static final List<SalesCopyStatus> TO_SEND = Arrays.asList(SalesCopyStatus.PENDING, SalesCopyStatus.ERROR);

	private final HeadOfficeClient client;
	private final ReceivedDeliveryRepository deliveries;
	private final StockCopyRepository stockCopies;
	private final LinkExchangeLog exchangeLog;
	private final TransactionOperations transactions;
	private final Supplier<LocalDateTime> clock;
	private final long defaultIntervalSeconds;

	@Autowired
	public SupplyPushService(HeadOfficeClient client, ReceivedDeliveryRepository deliveries,
			StockCopyRepository stockCopies, LinkExchangeLog exchangeLog, PlatformTransactionManager transactionManager,
			@Value("${headoffice.supply-push.interval-seconds:60}") long intervalSeconds) {
		this(client, deliveries, stockCopies, exchangeLog, timed(transactionManager), LocalDateTime::now, intervalSeconds);
	}

	/** With given transactions and clock: used by the tests. */
	public SupplyPushService(HeadOfficeClient client, ReceivedDeliveryRepository deliveries,
			StockCopyRepository stockCopies, LinkExchangeLog exchangeLog, TransactionOperations transactions,
			Supplier<LocalDateTime> clock, long intervalSeconds) {
		this.client = client;
		this.deliveries = deliveries;
		this.stockCopies = stockCopies;
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
		for (int batch = 0; batch < STOCK_BATCHES && cycle.delivering(); batch++) {
			if (System.nanoTime() - started > CYCLE_BOUND.toNanos()) {
				cycle.more = true;
				break;
			}
			if (!pushStock(cycle)) {
				break;
			}
		}
		return cycle;
	}

	// ─── Stock up (task 7A.5) ────────────────────────────────────

	/**
	 * One batch of the store's stock: the items (products and packs, not the tax stamp) whose stock differs from what the
	 * head office accepted last, and the items gone since. Delivered: their quantity sent is saved (a change made since
	 * the read goes with a later batch); not delivered: nothing changes. False when there was nothing to send or it was
	 * not delivered.
	 */
	private boolean pushStock(Cycle cycle) {
		List<Object[]> changed = transactions.execute(status -> stockCopies.findToSend(STOCK_TYPES,
				CatalogueKind.TAX_STAMP_CODE, PageRequest.of(0, STOCK_BATCH_SIZE)));
		List<StockCopy> gone = transactions
				.execute(status -> stockCopies.findRemoved(PageRequest.of(0, STOCK_BATCH_SIZE)));
		changed = changed == null ? new ArrayList<>() : changed;
		gone = gone == null ? new ArrayList<>() : gone;
		if (changed.isEmpty() && gone.isEmpty()) {
			return false;
		}
		LocalDateTime at = clock.get();
		long started = System.nanoTime();
		StockReportDTO report = new StockReportDTO();
		report.setTakenAt(at.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
		Map<Long, Object[]> byId = new HashMap<>();
		for (Object[] row : changed) {
			byId.put(((Number) row[0]).longValue(), row);
			report.getItems().add(new StockReportDTO.Item((String) row[1], (String) row[2], quantityOf(row),
					row[4] != RecordOrigin.HEAD_OFFICE));
		}
		for (StockCopy row : gone) {
			report.getRemoved().add(row.getItemCode());
		}
		SalesPushAnswer answer = client.pushStock(report);
		if (!answer.isDelivered()) {
			cycle.notDelivered(answer.getState(), answer.getMessage());
			exchangeLog.recordFailure(JOB_CODE, ExchangeDirection.UP, 0, answer.getState() + ": " + answer.getMessage(),
					at, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
			return false;
		}
		cycle.state = HeadOfficeLinkState.ONLINE;
		List<StockCopy> sentGone = gone;
		transactions.executeWithoutResult(status -> {
			Map<Long, StockCopy> rows = new HashMap<>();
			if (!byId.isEmpty()) {
				for (StockCopy row : stockCopies.findByItemIdIn(byId.keySet())) {
					rows.put(row.getItemId(), row);
				}
			}
			List<StockCopy> saved = new ArrayList<>();
			for (Map.Entry<Long, Object[]> entry : byId.entrySet()) {
				StockCopy row = rows.computeIfAbsent(entry.getKey(), id -> {
					StockCopy created = new StockCopy();
					created.setItemId(id);
					return created;
				});
				row.setItemCode((String) entry.getValue()[1]);
				row.setQuantitySent(quantityOf(entry.getValue()));
				row.setSentAt(at);
				saved.add(row);
			}
			stockCopies.saveAll(saved);
			if (!sentGone.isEmpty()) {
				stockCopies.deleteAll(sentGone);
			}
		});
		cycle.stockSent += changed.size();
		cycle.stockRemoved += gone.size();
		exchangeLog.record(JOB_CODE, ExchangeDirection.UP, changed.size() + gone.size(), LinkJobResult.SUCCESS, null, at,
				TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
		cycle.more |= changed.size() >= STOCK_BATCH_SIZE || gone.size() >= STOCK_BATCH_SIZE;
		return true;
	}

	/** The stock of a row, null as 0; 2.2.1: with its decimals, without trailing zeros (9.800 is 9.8, 9.000 is 9). */
	private static BigDecimal quantityOf(Object[] row) {
		return row[3] == null ? BigDecimal.ZERO : Quantities.normalize((BigDecimal) row[3]);
	}

	/** For the link page: {"stockToSend": items, "stockSentAt": "yyyy-MM-ddTHH:mm:ss" or null}. */
	public Map<String, Object> stockCounts() {
		Map<String, Object> counts = new LinkedHashMap<>();
		counts.put("stockToSend", stockCopies.countToSend(STOCK_TYPES, CatalogueKind.TAX_STAMP_CODE));
		List<LocalDateTime> last = stockCopies.lastSentAt();
		LocalDateTime at = last == null || last.isEmpty() ? null : last.get(0);
		counts.put("stockSentAt", at == null ? null : at.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
		return counts;
	}

	private boolean pushConfirmations(Cycle cycle, Collection<SalesCopyStatus> statuses) {
		// The copies are built inside the read transaction: the lines of a BL are a lazy collection (L2 of step 7A: built
		// after it, every push failed with a LazyInitializationException)
		List<Queued> queued = transactions.execute(status -> {
			List<Queued> built = new ArrayList<>();
			for (ReceivedDelivery row : deliveries.findPushQueue(statuses, PageRequest.of(0, BATCH_SIZE))) {
				if (!cycle.tried.contains(row.getId())) {
					built.add(new Queued(row.getId(), row.getNumber(), confirmationOf(row)));
				}
			}
			return built;
		});
		List<Queued> rows = queued == null ? new ArrayList<>() : queued;
		if (rows.isEmpty()) {
			return false;
		}
		LocalDateTime at = clock.get();
		long started = System.nanoTime();
		List<DeliveryConfirmationDTO> body = new ArrayList<>();
		for (Queued row : rows) {
			cycle.tried.add(row.id);
			body.add(row.confirmation);
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
		for (Queued row : rows) {
			SalesCopyResultDTO result = results.get(row.number);
			if (result != null && result.isAccepted()) {
				transactions.executeWithoutResult(status -> mark(row.id, SalesCopyStatus.SENT, null, at));
				accepted++;
			} else {
				String reason = result == null ? "no result from the head office for this BL"
						: result.getMessage() == null ? "rejected" : result.getMessage();
				transactions.executeWithoutResult(status -> mark(row.id, SalesCopyStatus.ERROR, reason, at));
				rejected++;
				firstProblem = firstProblem == null ? row.number + ": " + reason : firstProblem;
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

	/** One confirmation of the batch, built inside the read transaction. */
	private static final class Queued {
		final Long id;
		final String number;
		final DeliveryConfirmationDTO confirmation;

		Queued(Long id, String number, DeliveryConfirmationDTO confirmation) {
			this.id = id;
			this.number = number;
			this.confirmation = confirmation;
		}
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
			if (!line.isItemLine()) {
				continue; // an OTHER line of an ERP invoice is never received: the head office expects the item lines only
			}
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
		private int stockSent;
		private int stockRemoved;

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
			return confirmationsSent + confirmationsRejected + stockSent + stockRemoved == 0 && delivering();
		}
	}
}
