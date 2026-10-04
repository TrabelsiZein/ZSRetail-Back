package com.digithink.zsretail.support;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.model.DownCursor;
import com.digithink.zsretail.holink.model.DownRecord;
import com.digithink.zsretail.holink.model.LinkExchange;
import com.digithink.zsretail.holink.model.LoyaltyMemberCopy;
import com.digithink.zsretail.holink.model.LoyaltyMovementCopy;
import com.digithink.zsretail.holink.repository.DownCursorRepository;
import com.digithink.zsretail.holink.repository.DownRecordRepository;
import com.digithink.zsretail.holink.repository.LinkExchangeRepository;
import com.digithink.zsretail.holink.repository.LoyaltyMemberCopyRepository;
import com.digithink.zsretail.holink.repository.LoyaltyMovementCopyRepository;
import com.digithink.zsretail.holink.service.DownRecordLog;
import com.digithink.zsretail.holink.service.LinkExchangeLog;
import com.digithink.zsretail.model.LoyaltyTransaction;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.RecordOrigin;

/**
 * Test support (step 4): the head office link tables of one store in memory (hol_loyalty_member_copy,
 * hol_loyalty_movement_copy, hol_down_record, hol_down_cursor, hol_exchange_log), over the store's
 * {@link InMemoryLoyalty}. The stubs apply the rules of the JPQL queries (checked against Hibernate by
 * QueryParameterBindingTest; against SQL Server at L2).
 */
public final class InMemoryStoreLink {

	public final InMemoryLoyalty db;
	public final List<LoyaltyMemberCopy> memberCopies = new ArrayList<>();
	public final List<LoyaltyMovementCopy> movementCopies = new ArrayList<>();
	public final Map<String, DownRecord> downRecords = new LinkedHashMap<>();
	public final Map<DataDomain, DownCursor> cursors = new EnumMap<>(DataDomain.class);
	public final List<LinkExchange> exchanges = new ArrayList<>();

	public InMemoryStoreLink(InMemoryLoyalty db) {
		this.db = db;
	}

	public LoyaltyMemberCopyRepository memberCopyRepository() {
		return proxy(LoyaltyMemberCopyRepository.class, (method, a) -> {
			switch (method) {
				case "findByCardNumber":
					return memberCopies.stream().filter(c -> c.getCardNumber().equals(a[0])).findFirst();
				case "findBySurvivingCardNumber":
					return memberCopies.stream().filter(c -> a[0].equals(c.getSurvivingCardNumber()))
							.collect(Collectors.toList());
				case "findQueue": {
					Collection<?> statuses = (Collection<?>) a[0];
					return memberCopies.stream().filter(c -> statuses.contains(c.getStatus()))
							.sorted(Comparator.comparing(LoyaltyMemberCopy::getAttempts)
									.thenComparing(LoyaltyMemberCopy::getId))
							.limit(((Pageable) a[1]).getPageSize()).collect(Collectors.toList());
				}
				case "countByStatus":
					return countByStatus(memberCopies.stream().map(LoyaltyMemberCopy::getStatus).collect(Collectors.toList()));
				case "findPage": {
					Collection<?> statuses = (Collection<?>) a[0];
					List<LoyaltyMemberCopy> rows = memberCopies.stream().filter(c -> statuses.contains(c.getStatus()))
							.sorted(Comparator.comparing((LoyaltyMemberCopy c) -> c.getStatus().name())
									.thenComparing(LoyaltyMemberCopy::getId, Comparator.reverseOrder()))
							.collect(Collectors.toList());
					return page(rows, (Pageable) a[1]);
				}
				case "save": {
					LoyaltyMemberCopy row = (LoyaltyMemberCopy) a[0];
					if (row.getId() == null) {
						if (memberCopies.stream().anyMatch(c -> c.getCardNumber().equals(row.getCardNumber()))) {
							throw new IllegalStateException("uk_hol_loyalty_member_copy_card");
						}
						row.setId(db.nextId());
						memberCopies.add(row);
					}
					return row;
				}
				default:
					return UNHANDLED;
			}
		});
	}

	public LoyaltyMovementCopyRepository movementCopyRepository() {
		return proxy(LoyaltyMovementCopyRepository.class, (method, a) -> {
			switch (method) {
				case "findUntracked":
					return db.transactions.stream()
							.filter(t -> t.getLoyaltyMember().getOrigin() == a[0]
									&& movementCopies.stream().noneMatch(c -> c.getLocalId().equals(t.getId())))
							.sorted(Comparator.comparing(LoyaltyTransaction::getId))
							.limit(((Pageable) a[1]).getPageSize())
							.map(t -> new Object[] { t.getId(), t.getLoyaltyMember().getCardNumber(), t.getType(),
									t.getPoints(), t.getBalanceBefore(), t.getBalanceAfter(), t.getCreatedAt() })
							.collect(Collectors.toList());
				case "findQueue": {
					Collection<?> statuses = (Collection<?>) a[0];
					return movementCopies.stream()
							.filter(c -> statuses.contains(c.getStatus()) && memberCopies.stream().noneMatch(
									u -> u.getCardNumber().equals(c.getCardNumber()) && u.getStatus() != a[1]))
							.sorted(Comparator.comparing(LoyaltyMovementCopy::getLocalId))
							.limit(((Pageable) a[2]).getPageSize()).collect(Collectors.toList());
				}
				case "findMovementDetails": {
					Collection<?> ids = (Collection<?>) a[0];
					return db.transactions.stream().filter(t -> ids.contains(t.getId()))
							.map(t -> new Object[] { t.getId(), t.getLoyaltyMember().getCardNumber(), t.getType(),
									t.getPoints(), t.getBalanceBefore(), t.getBalanceAfter(), t.getCreatedAt(),
									t.getSalesHeader() == null ? null : t.getSalesHeader().getSalesNumber(),
									t.getReturnHeader() == null ? null : t.getReturnHeader().getReturnNumber(),
									t.getLoyaltyProgram() == null ? null : t.getLoyaltyProgram().getProgramCode(),
									t.getExpiryDate(), t.getDescription(), t.getCreatedBy() })
							.collect(Collectors.toList());
				}
				case "findNotApplied": {
					Collection<?> memberIds = (Collection<?>) a[0];
					return db.transactions.stream()
							.filter(t -> memberIds.contains(t.getLoyaltyMember().getId())
									&& movementCopies.stream().noneMatch(
											c -> c.getLocalId().equals(t.getId()) && c.getStatus() == a[1]))
							.sorted(Comparator.comparing(LoyaltyTransaction::getId))
							.map(t -> new Object[] { t.getId(), t.getType(), t.getPoints(), t.getBalanceBefore(),
									t.getBalanceAfter(), t.getSalesHeader() == null ? null : t.getSalesHeader().getId() })
							.collect(Collectors.toList());
				}
				case "findByLocalIdIn": {
					Collection<?> ids = (Collection<?>) a[0];
					return movementCopies.stream().filter(c -> ids.contains(c.getLocalId())).collect(Collectors.toList());
				}
				case "countByStatus":
					return countByStatus(
							movementCopies.stream().map(LoyaltyMovementCopy::getStatus).collect(Collectors.toList()));
				case "findPage": {
					Collection<?> statuses = (Collection<?>) a[0];
					List<LoyaltyMovementCopy> rows = movementCopies.stream()
							.filter(c -> statuses.contains(c.getStatus()))
							.sorted(Comparator.comparing((LoyaltyMovementCopy c) -> c.getStatus().name())
									.thenComparing(LoyaltyMovementCopy::getLocalId, Comparator.reverseOrder()))
							.collect(Collectors.toList());
					return page(rows, (Pageable) a[1]);
				}
				case "save": {
					LoyaltyMovementCopy row = (LoyaltyMovementCopy) a[0];
					if (row.getId() == null) {
						if (movementCopies.stream().anyMatch(c -> c.getLocalId().equals(row.getLocalId()))) {
							throw new IllegalStateException("uk_hol_loyalty_movement_copy_local");
						}
						row.setId(db.nextId());
						movementCopies.add(row);
					}
					return row;
				}
				default:
					return UNHANDLED;
			}
		});
	}

	public DownRecordLog downRecordLog() {
		return new DownRecordLog(proxy(DownRecordRepository.class, (method, a) -> {
			switch (method) {
				case "findByDomainAndRecordCode":
					return Optional.ofNullable(downRecords.get(a[1]));
				case "save": {
					DownRecord row = (DownRecord) a[0];
					downRecords.put(row.getRecordCode(), row);
					return row;
				}
				case "delete":
					downRecords.remove(((DownRecord) a[0]).getRecordCode());
					return null;
				case "findByDomainAndStatusIn": {
					Collection<?> statuses = (Collection<?>) a[1];
					return downRecords.values().stream().filter(r -> r.getDomain() == a[0] && statuses.contains(r.getStatus()))
							.collect(Collectors.toList());
				}
				case "countByStatus": {
					Map<Object, Long> counts = downRecords.values().stream().filter(r -> r.getDomain() == a[0])
							.collect(Collectors.groupingBy(DownRecord::getStatus, Collectors.counting()));
					return counts.entrySet().stream().map(e -> new Object[] { e.getKey(), e.getValue() })
							.collect(Collectors.toList());
				}
				default:
					return UNHANDLED;
			}
		}));
	}

	public DownCursorRepository cursorRepository() {
		return proxy(DownCursorRepository.class, (method, a) -> {
			switch (method) {
				case "findByDomain":
					return Optional.ofNullable(cursors.get(a[0]));
				case "save": {
					DownCursor cursor = (DownCursor) a[0];
					cursors.put(cursor.getDomain(), cursor);
					return cursor;
				}
				default:
					return UNHANDLED;
			}
		});
	}

	private LinkExchangeLog exchangeLog;

	/** One instance, like the bean: it remembers the failures of the jobs (step 5). */
	public LinkExchangeLog exchangeLog() {
		if (exchangeLog == null) {
			exchangeLog = new LinkExchangeLog(proxy(LinkExchangeRepository.class, (method, a) -> {
				if ("save".equals(method)) {
					exchanges.add((LinkExchange) a[0]);
					return a[0];
				}
				return UNHANDLED;
			}), TransactionOperations.withoutTransaction(), 30);
		}
		return exchangeLog;
	}

	/** The tracking row of a movement, by its loyalty_transaction. */
	public LoyaltyMovementCopy movementOf(LoyaltyTransaction tx) {
		return movementCopies.stream().filter(c -> Objects.equals(c.getLocalId(), tx.getId())).findFirst()
				.orElse(null);
	}

	public LoyaltyMemberCopy memberCopyOf(String card) {
		return memberCopies.stream().filter(c -> c.getCardNumber().equals(card)).findFirst().orElse(null);
	}

	/** Members of the register held here (origin HEAD_OFFICE). */
	public long networkMembers() {
		return db.members.values().stream().filter(m -> m.getOrigin() == RecordOrigin.HEAD_OFFICE).count();
	}

	private static List<Object[]> countByStatus(List<SalesCopyStatus> statuses) {
		Map<SalesCopyStatus, Long> counts = statuses.stream()
				.collect(Collectors.groupingBy(s -> s, () -> new EnumMap<>(SalesCopyStatus.class), Collectors.counting()));
		return counts.entrySet().stream().map(e -> new Object[] { e.getKey(), e.getValue() }).collect(Collectors.toList());
	}

	private static <T> PageImpl<T> page(List<T> rows, Pageable pageable) {
		int from = (int) Math.min(rows.size(), pageable.getOffset());
		int to = Math.min(rows.size(), from + pageable.getPageSize());
		return new PageImpl<>(new ArrayList<>(rows.subList(from, to)), pageable, rows.size());
	}
}
