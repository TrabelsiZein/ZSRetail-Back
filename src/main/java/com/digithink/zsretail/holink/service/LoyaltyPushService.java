package com.digithink.zsretail.holink.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeOwned;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberCopyDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberResultDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMovementCopyDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyResultDTO;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.LoyaltyMemberPushAnswer;
import com.digithink.zsretail.holink.dto.SalesPushAnswer;
import com.digithink.zsretail.holink.enumeration.ExchangeDirection;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.model.LoyaltyMemberCopy;
import com.digithink.zsretail.holink.model.LoyaltyMovementCopy;
import com.digithink.zsretail.holink.repository.LoyaltyMemberCopyRepository;
import com.digithink.zsretail.holink.repository.LoyaltyMovementCopyRepository;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.LoyaltyTransactionType;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.service.LoyaltyLedger;

import lombok.Getter;
import lombok.ToString;

/**
 * Head office plan, step 4: sends up what this store adds to the shared loyalty register, run by the LOYALTY_PUSH job
 * on the ho-link thread.
 * <ol>
 * <li>Search: every loyalty_transaction row of a member of the register (origin HEAD_OFFICE) without a
 * hol_loyalty_movement_copy row gets one, PENDING. A query, no hook in the selling services: earning, spending and
 * returns write their rows as before. Those rows are never changed, so no row is missed or sent twice.</li>
 * <li>Members first: the members enrolled here (hol_loyalty_member_copy, written with the member), in batches. A merge
 * answer deactivates the local card and saves the surviving member ({@link LoyaltyCopyWriter#merge}).</li>
 * <li>Then the movements, oldest first, in batches; a movement waits while the member of its card is not accepted.</li>
 * </ol>
 * Accepted: SENT. Rejected: ERROR with the reason, retried at later cycles. Not delivered (unreachable, key refused,
 * no license, unreadable answer): nothing changes, the cycle stops. One exchange log row per batch, and for a failed
 * search.
 */
@Service
@ConditionalOnHeadOfficeOwned(DataDomain.LOYALTY)
public class LoyaltyPushService {

	/** 2.2.2: the default frequency (loyalty to the head office every 10 min) when none is saved from the Jobs page; a property overrides it. */
	public static final long DEFAULT_INTERVAL_SECONDS = 600;

	public static final String JOB_CODE = "LOYALTY_PUSH";

	static final int BATCH_SIZE = 50;
	static final int SEARCH_PAGE = 500;
	static final int MEMBER_BATCHES = 2;
	static final int MOVEMENT_BATCHES = 4;
	static final Duration CYCLE_BOUND = Duration.ofSeconds(20);
	static final int TIMEOUT_SECONDS = 15;
	static final int LIST_MAX_SIZE = 200;

	static final List<SalesCopyStatus> TO_SEND = Arrays.asList(SalesCopyStatus.PENDING, SalesCopyStatus.ERROR);

	private final HeadOfficeClient client;
	private final LoyaltyMemberCopyRepository memberCopies;
	private final LoyaltyMovementCopyRepository movementCopies;
	private final LoyaltyMemberRepository members;
	private final LoyaltyCopyWriter writer;
	private final LinkExchangeLog exchangeLog;
	private final TransactionOperations transactions;
	private final Supplier<LocalDateTime> clock;
	private final long defaultIntervalSeconds;

	@Autowired
	public LoyaltyPushService(HeadOfficeClient client, LoyaltyMemberCopyRepository memberCopies,
			LoyaltyMovementCopyRepository movementCopies, LoyaltyMemberRepository members, LoyaltyCopyWriter writer,
			LinkExchangeLog exchangeLog, PlatformTransactionManager transactionManager,
			@Value("${headoffice.loyalty-push.interval-seconds:" + LoyaltyPushService.DEFAULT_INTERVAL_SECONDS + "}") long intervalSeconds) {
		this(client, memberCopies, movementCopies, members, writer, exchangeLog, timed(transactionManager),
				LocalDateTime::now, intervalSeconds);
	}

	/** With given transactions and clock: used by the tests. */
	public LoyaltyPushService(HeadOfficeClient client, LoyaltyMemberCopyRepository memberCopies,
			LoyaltyMovementCopyRepository movementCopies, LoyaltyMemberRepository members, LoyaltyCopyWriter writer,
			LinkExchangeLog exchangeLog, TransactionOperations transactions, Supplier<LocalDateTime> clock,
			long intervalSeconds) {
		this.client = client;
		this.memberCopies = memberCopies;
		this.movementCopies = movementCopies;
		this.members = members;
		this.writer = writer;
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
		discover(cycle);
		for (int batch = 0; batch < MEMBER_BATCHES && cycle.delivering(); batch++) {
			if (batch > 0 && System.nanoTime() - started > CYCLE_BOUND.toNanos()) {
				cycle.more = true;
				break;
			}
			// The first batch retries the rejected members too; later ones take the members never sent
			if (!pushMembers(cycle, batch == 0 ? TO_SEND : Collections.singletonList(SalesCopyStatus.PENDING))) {
				break;
			}
		}
		for (int batch = 0; batch < MOVEMENT_BATCHES && cycle.delivering(); batch++) {
			if (System.nanoTime() - started > CYCLE_BOUND.toNanos()) {
				cycle.more = true;
				break;
			}
			if (!pushMovements(cycle, batch == 0 ? TO_SEND : Collections.singletonList(SalesCopyStatus.PENDING))) {
				break;
			}
		}
		return cycle;
	}

	// ─── Search ──────────────────────────────────────────────────

	private void discover(Cycle cycle) {
		LocalDateTime at = clock.get();
		long started = System.nanoTime();
		try {
			Integer found = transactions.execute(status -> {
				List<Object[]> rows = movementCopies.findUntracked(RecordOrigin.HEAD_OFFICE, PageRequest.of(0, SEARCH_PAGE));
				for (Object[] row : rows) {
					LoyaltyTransactionType type = (LoyaltyTransactionType) row[2];
					int points = row[3] == null ? 0 : ((Number) row[3]).intValue();
					LoyaltyMovementCopy copy = new LoyaltyMovementCopy();
					copy.setLocalId(((Number) row[0]).longValue());
					copy.setCardNumber((String) row[1]);
					copy.setType(type);
					copy.setPoints(points);
					copy.setDelta(LoyaltyLedger.deltaOf(type, points, (Integer) row[4], (Integer) row[5]));
					copy.setMovementDate((LocalDateTime) row[6]);
					movementCopies.save(copy);
				}
				return rows.size();
			});
			cycle.found = found == null ? 0 : found;
			cycle.more |= cycle.found >= SEARCH_PAGE;
		} catch (RuntimeException e) {
			cycle.searchFailure = SalesCopyFinder.cause(e);
			logFailure("search failed (" + cycle.searchFailure + ")", at, started);
		}
	}

	// ─── Members ─────────────────────────────────────────────────

	/** One batch of members; false when there was nothing to send or it was not delivered. */
	private boolean pushMembers(Cycle cycle, Collection<SalesCopyStatus> statuses) {
		List<LoyaltyMemberCopy> rows = transactions
				.execute(status -> memberCopies.findQueue(statuses, PageRequest.of(0, BATCH_SIZE)));
		rows = rows == null ? new ArrayList<>() : new ArrayList<>(rows);
		rows.removeIf(row -> cycle.membersTried.contains(row.getId()));
		if (rows.isEmpty()) {
			return false;
		}
		LocalDateTime at = clock.get();
		long started = System.nanoTime();
		List<LoyaltyMemberCopyDTO> copies = new ArrayList<>();
		List<LoyaltyMemberCopy> sent = new ArrayList<>();
		int notBuilt = 0;
		String firstProblem = null;
		for (LoyaltyMemberCopy row : rows) {
			cycle.membersTried.add(row.getId());
			Optional<LoyaltyMember> member = transactions.execute(status -> members.findById(row.getMemberId()));
			if (member == null || !member.isPresent()) {
				String reason = "the member no longer exists in the store";
				transactions.executeWithoutResult(status -> markMember(row, SalesCopyStatus.ERROR, reason, at));
				notBuilt++;
				firstProblem = firstProblem == null ? row.getCardNumber() + ": " + reason : firstProblem;
				continue;
			}
			LoyaltyMemberCopyDTO copy = LoyaltyMemberCopyDTO.of(member.get());
			// Points travel as movements: the head office starts the member at 0
			copy.setLoyaltyPoints(0);
			copy.setTotalPointsEarned(0);
			copy.setTotalPointsRedeemed(0);
			copies.add(copy);
			sent.add(row);
		}
		if (sent.isEmpty()) {
			cycle.membersNotBuilt += notBuilt;
			log(notBuilt, LinkJobResult.WARNING, firstProblem, at, started);
			return true;
		}
		LoyaltyMemberPushAnswer answer = client.pushLoyaltyMembers(copies);
		if (!answer.isDelivered()) {
			cycle.notDelivered(answer.getState(), answer.getMessage());
			logFailure(answer.getState() + ": " + answer.getMessage(), at, started);
			return false;
		}
		cycle.state = HeadOfficeLinkState.ONLINE;
		Map<String, LoyaltyMemberResultDTO> results = new HashMap<>();
		for (LoyaltyMemberResultDTO result : answer.getResults()) {
			if (result != null && result.getCardNumber() != null) {
				results.putIfAbsent(result.getCardNumber(), result);
			}
		}
		int accepted = 0;
		int rejected = 0;
		for (LoyaltyMemberCopy row : sent) {
			LoyaltyMemberResultDTO result = results.get(row.getCardNumber());
			if (result != null && result.isAccepted()) {
				try {
					transactions.executeWithoutResult(status -> {
						if (LoyaltyMemberResultDTO.MERGED.equals(result.getOutcome())
								&& result.getSurvivingCardNumber() != null) {
							writer.merge(row, result);
							cycle.merged++;
						} else {
							row.setOutcome(result.getOutcome());
						}
						markMember(row, SalesCopyStatus.SENT, null, at);
					});
					accepted++;
				} catch (RuntimeException e) {
					// The head office has it; the merge is saved here at the next send (answered MERGED again)
					String reason = "answer not saved (" + SalesCopyFinder.cause(e) + ")";
					transactions.executeWithoutResult(status -> markMember(row, SalesCopyStatus.ERROR, reason, at));
					rejected++;
					firstProblem = firstProblem == null ? row.getCardNumber() + ": " + reason : firstProblem;
				}
			} else {
				String reason = result == null ? "no result from the head office for this member"
						: result.getMessage() == null ? "rejected" : result.getMessage();
				transactions.executeWithoutResult(status -> markMember(row, SalesCopyStatus.ERROR, reason, at));
				rejected++;
				firstProblem = firstProblem == null ? row.getCardNumber() + ": " + reason : firstProblem;
			}
		}
		cycle.membersSent += accepted;
		cycle.membersRejected += rejected;
		cycle.membersNotBuilt += notBuilt;
		log(sent.size() + notBuilt, accepted == 0 ? LinkJobResult.ERROR
				: rejected + notBuilt > 0 ? LinkJobResult.WARNING : LinkJobResult.SUCCESS, firstProblem, at, started);
		cycle.more |= rows.size() >= BATCH_SIZE;
		return true;
	}

	private void markMember(LoyaltyMemberCopy row, SalesCopyStatus status, String error, LocalDateTime at) {
		row.setStatus(status);
		row.setAttempts(row.getAttempts() + 1);
		row.setLastError(cut(error, LoyaltyMemberCopy.LAST_ERROR_LENGTH));
		row.setLastPushDate(at);
		memberCopies.save(row);
	}

	// ─── Movements ───────────────────────────────────────────────

	/** One batch of movements; false when there was nothing to send or it was not delivered. */
	private boolean pushMovements(Cycle cycle, Collection<SalesCopyStatus> statuses) {
		List<LoyaltyMovementCopy> rows = transactions.execute(
				status -> movementCopies.findQueue(statuses, SalesCopyStatus.SENT, PageRequest.of(0, BATCH_SIZE)));
		rows = rows == null ? new ArrayList<>() : new ArrayList<>(rows);
		rows.removeIf(row -> cycle.movementsTried.contains(row.getId()));
		if (rows.isEmpty()) {
			return false;
		}
		LocalDateTime at = clock.get();
		long started = System.nanoTime();
		Map<Long, LoyaltyMovementCopyDTO> copies = build(rows);
		List<LoyaltyMovementCopyDTO> body = new ArrayList<>();
		List<LoyaltyMovementCopy> sent = new ArrayList<>();
		int notBuilt = 0;
		String firstProblem = null;
		for (LoyaltyMovementCopy row : rows) {
			cycle.movementsTried.add(row.getId());
			LoyaltyMovementCopyDTO copy = copies.get(row.getLocalId());
			if (copy == null) {
				String reason = "the movement no longer exists in the store";
				transactions.executeWithoutResult(status -> markMovement(row, SalesCopyStatus.ERROR, reason, at));
				notBuilt++;
				firstProblem = firstProblem == null ? row.getLocalId() + ": " + reason : firstProblem;
				continue;
			}
			body.add(copy);
			sent.add(row);
		}
		if (sent.isEmpty()) {
			cycle.movementsNotBuilt += notBuilt;
			log(notBuilt, LinkJobResult.WARNING, firstProblem, at, started);
			return true;
		}
		SalesPushAnswer answer = client.pushLoyaltyMovements(body);
		if (!answer.isDelivered()) {
			cycle.notDelivered(answer.getState(), answer.getMessage());
			logFailure(answer.getState() + ": " + answer.getMessage(), at, started);
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
		for (LoyaltyMovementCopy row : sent) {
			SalesCopyResultDTO result = results.get(String.valueOf(row.getLocalId()));
			if (result != null && result.isAccepted()) {
				transactions.executeWithoutResult(status -> markMovement(row, SalesCopyStatus.SENT, null, at));
				accepted++;
			} else {
				String reason = result == null ? "no result from the head office for this movement"
						: result.getMessage() == null ? "rejected" : result.getMessage();
				transactions.executeWithoutResult(status -> markMovement(row, SalesCopyStatus.ERROR, reason, at));
				rejected++;
				firstProblem = firstProblem == null ? row.getLocalId() + ": " + reason : firstProblem;
			}
		}
		cycle.movementsSent += accepted;
		cycle.movementsRejected += rejected;
		cycle.movementsNotBuilt += notBuilt;
		log(sent.size() + notBuilt, accepted == 0 ? LinkJobResult.ERROR
				: rejected + notBuilt > 0 ? LinkJobResult.WARNING : LinkJobResult.SUCCESS, firstProblem, at, started);
		cycle.more |= rows.size() >= BATCH_SIZE;
		return true;
	}

	/** The copies of these movements, by local id, read in one query. */
	private Map<Long, LoyaltyMovementCopyDTO> build(List<LoyaltyMovementCopy> rows) {
		List<Long> ids = new ArrayList<>();
		for (LoyaltyMovementCopy row : rows) {
			ids.add(row.getLocalId());
		}
		Map<Long, LoyaltyMovementCopyDTO> copies = new HashMap<>();
		List<Object[]> details = transactions.execute(status -> movementCopies.findMovementDetails(ids));
		Map<Long, LoyaltyMovementCopy> byId = new HashMap<>();
		for (LoyaltyMovementCopy row : rows) {
			byId.put(row.getLocalId(), row);
		}
		for (Object[] d : details == null ? Collections.<Object[]>emptyList() : details) {
			long id = ((Number) d[0]).longValue();
			LoyaltyTransactionType type = (LoyaltyTransactionType) d[2];
			int points = d[3] == null ? 0 : ((Number) d[3]).intValue();
			LoyaltyMovementCopyDTO copy = new LoyaltyMovementCopyDTO();
			copy.setKey(String.valueOf(id));
			// The card as tracked: the member's card when the movement was made
			copy.setCardNumber(byId.containsKey(id) ? byId.get(id).getCardNumber() : (String) d[1]);
			copy.setType(type.name());
			copy.setPoints(points);
			copy.setDelta(LoyaltyLedger.deltaOf(type, points, (Integer) d[4], (Integer) d[5]));
			copy.setDate((LocalDateTime) d[6]);
			copy.setSalesNumber((String) d[7]);
			copy.setReturnNumber((String) d[8]);
			copy.setProgramCode((String) d[9]);
			copy.setExpiryDate((LocalDate) d[10]);
			copy.setDescription((String) d[11]);
			copy.setCreatedBy((String) d[12]);
			copies.put(id, copy);
		}
		return copies;
	}

	private void markMovement(LoyaltyMovementCopy row, SalesCopyStatus status, String error, LocalDateTime at) {
		row.setStatus(status);
		row.setAttempts(row.getAttempts() + 1);
		row.setLastError(cut(error, LoyaltyMovementCopy.LAST_ERROR_LENGTH));
		row.setLastPushDate(at);
		movementCopies.save(row);
	}

	// ─── Link page ───────────────────────────────────────────────

	/** {members: {PENDING, SENT, ERROR}, movements: {PENDING, SENT, ERROR}}, every status present. */
	public Map<String, Map<String, Long>> counts() {
		Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
		counts.put("members", counts(memberCopies.countByStatus()));
		counts.put("movements", counts(movementCopies.countByStatus()));
		return counts;
	}

	private static Map<String, Long> counts(List<Object[]> rows) {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (SalesCopyStatus status : SalesCopyStatus.values()) {
			counts.put(status.name(), 0L);
		}
		for (Object[] row : rows) {
			counts.put(((SalesCopyStatus) row[0]).name(), ((Number) row[1]).longValue());
		}
		return counts;
	}

	/**
	 * The link page list of "members" or "movements": {kind, counts, records, totalElements, page, size}, ERROR first,
	 * then PENDING, then SENT, newest first in each. status: one status (any case), blank or "all" = every status.
	 * IllegalArgumentException for an unknown kind or status.
	 */
	public Map<String, Object> list(String kind, String status, Integer page, Integer size) {
		Collection<SalesCopyStatus> statuses = parseStatuses(status);
		int number = page == null || page < 0 ? 0 : page;
		int pageSize = size == null || size < 1 ? 20 : Math.min(size, LIST_MAX_SIZE);
		PageRequest request = PageRequest.of(number, pageSize);
		String name = kind == null ? "" : kind.trim().toLowerCase();
		List<Map<String, Object>> records = new ArrayList<>();
		long total;
		Map<String, Long> counts;
		if ("members".equals(name)) {
			Page<LoyaltyMemberCopy> rows = memberCopies.findPage(statuses, request);
			for (LoyaltyMemberCopy row : rows.getContent()) {
				Map<String, Object> item = new LinkedHashMap<>();
				Optional<LoyaltyMember> member = members.findById(row.getMemberId());
				item.put("cardNumber", row.getCardNumber());
				item.put("name", member.map(m -> (m.getFirstName() + " " + m.getLastName()).trim()).orElse(null));
				item.put("phone", member.map(LoyaltyMember::getPhone).orElse(null));
				item.put("status", row.getStatus());
				item.put("attempts", row.getAttempts());
				item.put("lastError", row.getLastError());
				item.put("lastPushDate", row.getLastPushDate());
				item.put("outcome", row.getOutcome());
				item.put("survivingCardNumber", row.getSurvivingCardNumber());
				records.add(item);
			}
			total = rows.getTotalElements();
			counts = counts(memberCopies.countByStatus());
		} else if ("movements".equals(name)) {
			Page<LoyaltyMovementCopy> rows = movementCopies.findPage(statuses, request);
			for (LoyaltyMovementCopy row : rows.getContent()) {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("key", row.getLocalId());
				item.put("cardNumber", row.getCardNumber());
				item.put("type", row.getType());
				item.put("points", row.getPoints());
				item.put("delta", row.getDelta());
				item.put("date", row.getMovementDate());
				item.put("status", row.getStatus());
				item.put("attempts", row.getAttempts());
				item.put("lastError", row.getLastError());
				item.put("lastPushDate", row.getLastPushDate());
				records.add(item);
			}
			total = rows.getTotalElements();
			counts = counts(movementCopies.countByStatus());
		} else {
			throw new IllegalArgumentException("Invalid kind '" + kind + "': members or movements");
		}
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("kind", name);
		answer.put("counts", counts);
		answer.put("records", records);
		answer.put("totalElements", total);
		answer.put("page", number);
		answer.put("size", pageSize);
		return answer;
	}

	private static Collection<SalesCopyStatus> parseStatuses(String status) {
		if (status == null || status.trim().isEmpty() || "all".equalsIgnoreCase(status.trim())) {
			return EnumSet.allOf(SalesCopyStatus.class);
		}
		try {
			return Collections.singleton(SalesCopyStatus.valueOf(status.trim().toUpperCase()));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(
					"Invalid status '" + status + "': allowed values are " + Arrays.toString(SalesCopyStatus.values()));
		}
	}

	/** Step 5: not delivered or search failed: one row when the failure starts, not one per cycle. */
	private void logFailure(String error, LocalDateTime at, long started) {
		exchangeLog.recordFailure(JOB_CODE, ExchangeDirection.UP, 0, error, at,
				TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
	}

	private void log(int records, LinkJobResult result, String error, LocalDateTime at, long started) {
		exchangeLog.record(JOB_CODE, ExchangeDirection.UP, records, result, error, at,
				TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
	}

	private static String cut(String text, int length) {
		return text == null || text.length() <= length ? text : text.substring(0, length);
	}

	/** Outcome of one cycle. */
	@Getter
	@ToString
	public static final class Cycle {

		private int found;
		private int membersSent;
		private int membersRejected;
		private int membersNotBuilt;
		private int merged;
		private int movementsSent;
		private int movementsRejected;
		private int movementsNotBuilt;

		/** The search failure; null when the search ran. */
		private String searchFailure;

		/** State of the last request; null when nothing was sent. */
		private HeadOfficeLinkState state;
		private String message;

		/** More waits: the next cycle comes soon. */
		private boolean more;

		@ToString.Exclude
		private final List<Long> membersTried = new ArrayList<>();
		@ToString.Exclude
		private final List<Long> movementsTried = new ArrayList<>();

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
			return found + membersSent + membersRejected + membersNotBuilt + movementsSent + movementsRejected
					+ movementsNotBuilt == 0 && searchFailure == null && delivering();
		}
	}
}
