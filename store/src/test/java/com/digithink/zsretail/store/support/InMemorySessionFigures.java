package com.digithink.zsretail.store.support;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.digithink.zsretail.core.model.BadgeScanLog;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.store.cashdifference.CashWatchRepository;
import com.digithink.zsretail.store.cashmovement.model.CashMovement;
import com.digithink.zsretail.store.cashmovement.model.OpeningFloat;
import com.digithink.zsretail.store.cashmovement.model.OpeningUnverified;
import com.digithink.zsretail.store.cashmovement.repository.OpeningFloatRepository;
import com.digithink.zsretail.store.cashmovement.repository.OpeningUnverifiedRepository;
import com.digithink.zsretail.store.cashmovement.service.DrawerHandoverService;
import com.digithink.zsretail.store.model.ScannedTicketPayment;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.posmachine.model.SessionPosMachine;
import com.digithink.zsretail.store.posmachine.repository.SessionPosMachineRepository;
import com.digithink.zsretail.store.posmachine.service.SessionPosMachineService;
import com.digithink.zsretail.store.repository.SessionFiguresRepository;
import com.digithink.zsretail.store.service.SessionFiguresService;

/**
 * 3.2.0, the differences, step B: SessionFiguresService over in-memory tables. Its repository answers the five queries
 * of SessionFiguresRepository in Java, as their JPQL reads (sales without PENDING / CANCELLED, by id; the period's
 * sessions newest first, of a cashier and a POS machine), and counts its calls (one per kind and chunk, never one per
 * session). The POS machine of a session ({@link #machineOf}), the floats proposed and the openings without the
 * previous session finally closed are lists too.
 */
public final class InMemorySessionFigures {

	public final List<CashierSession> sessions = new ArrayList<>();
	public final List<SalesHeader> sales = new ArrayList<>();
	public final List<ReturnHeader> returns = new ArrayList<>();
	public final List<ScannedTicketPayment> tickets = new ArrayList<>();
	public final List<CashMovement> movements = new ArrayList<>();
	public final Map<Long, PosMachine> machineOf = new HashMap<>();
	public final List<OpeningFloat> openings = new ArrayList<>();
	public final List<OpeningUnverified> unverified = new ArrayList<>();
	/** Step C: the sales lines (their discounts) and the till authorisations' log. */
	public final List<SalesLine> lines = new ArrayList<>();
	public final List<BadgeScanLog> badgeLogs = new ArrayList<>();

	/** The repository's calls, by method name. */
	public final Map<String, Integer> calls = new HashMap<>();

	public final SessionFiguresRepository repository = proxy(SessionFiguresRepository.class, (m, a) -> {
		calls.merge(m, 1, Integer::sum);
		switch (m) {
			case "saleAmounts": {
				Collection<?> ids = (Collection<?>) a[0];
				Collection<?> excluded = (Collection<?>) a[1];
				return sales.stream()
						.filter(s -> ids.contains(s.getCashierSession().getId())
								&& (s.getStatus() == null || !excluded.contains(s.getStatus())))
						.sorted(Comparator.comparing(SalesHeader::getId))
						.map(s -> new Object[] { s.getCashierSession().getId(), s.getTotalAmount() })
						.collect(Collectors.toList());
			}
			case "returnAmounts": {
				Collection<?> ids = (Collection<?>) a[0];
				return returns.stream().filter(r -> ids.contains(r.getCashierSession().getId()))
						.sorted(Comparator.comparing(ReturnHeader::getId))
						.map(r -> new Object[] { r.getCashierSession().getId(), r.getReturnType(), r.getTotalReturnAmount() })
						.collect(Collectors.toList());
			}
			case "scannedTickets": {
				Collection<?> ids = (Collection<?>) a[0];
				Collection<?> excluded = (Collection<?>) a[1];
				return tickets.stream().filter(t -> {
					SalesHeader sale = t.getPayment().getSalesHeader();
					return ids.contains(sale.getCashierSession().getId()) && !excluded.contains(sale.getStatus());
				}).collect(Collectors.toList());
			}
			case "cashMovements": {
				Collection<?> ids = (Collection<?>) a[0];
				return movements.stream().filter(mv -> ids.contains(mv.getCashierSession().getId()))
						.collect(Collectors.toList());
			}
			case "openedBetween": {
				LocalDateTime from = (LocalDateTime) a[0];
				LocalDateTime to = (LocalDateTime) a[1];
				Long cashierId = (Long) a[2];
				Long machineId = (Long) a[3];
				return sessions.stream()
						.filter(s -> !s.getOpenedAt().isBefore(from) && !s.getOpenedAt().isAfter(to))
						.filter(s -> cashierId == null || (s.getCashier() != null && cashierId.equals(s.getCashier().getId())))
						.filter(s -> machineId == null
								|| (machineOf.get(s.getId()) != null && machineId.equals(machineOf.get(s.getId()).getId())))
						.sorted(Comparator.comparing(CashierSession::getOpenedAt).thenComparing(CashierSession::getId)
								.reversed())
						.collect(Collectors.toList());
			}
			default:
				return UNHANDLED;
		}
	});

	/** Step C: CashWatchRepository's four grouped queries, as their JPQL reads. */
	public final CashWatchRepository watchRepository = proxy(CashWatchRepository.class, (m, a) -> {
		calls.merge(m, 1, Integer::sum);
		Collection<?> ids = (Collection<?>) a[0];
		switch (m) {
			case "cancelledTickets": {
				Map<Long, Object[]> bySession = new java.util.LinkedHashMap<>();
				sales.stream().filter(s -> ids.contains(s.getCashierSession().getId()) && s.getStatus() == a[1])
						.forEach(s -> {
							Object[] row = bySession.computeIfAbsent(s.getCashierSession().getId(),
									id -> new Object[] { id, 0L, null });
							row[1] = (Long) row[1] + 1;
							row[2] = s.getTotalAmount() == null ? row[2]
									: (row[2] == null ? 0.0 : (Double) row[2]) + s.getTotalAmount();
						});
				return new ArrayList<>(bySession.values());
			}
			case "discountedLines": {
				Collection<?> excluded = (Collection<?>) a[1];
				return lines.stream().filter(l -> {
					SalesHeader sale = l.getSalesHeader();
					boolean discounted = (l.getDiscountAmount() != null && l.getDiscountAmount() != 0.0)
							|| (l.getDiscountPercentage() != null && l.getDiscountPercentage() == 100.0);
					return ids.contains(sale.getCashierSession().getId()) && !excluded.contains(sale.getStatus()) && discounted;
				}).map(l -> new Object[] { l.getSalesHeader().getId(), l.getSalesHeader().getCashierSession().getId(),
						l.getDiscountAmount(), l.getDiscountSource(), l.getLineTotalIncludingVat(), l.getDiscountPercentage(),
						l.getUnitPriceIncludingVat(), l.getQuantity(), l.getItem() != null ? l.getItem().getItemCode() : null })
						.collect(Collectors.toList());
			}
			case "discountedTickets": {
				Collection<?> excluded = (Collection<?>) a[1];
				return sales.stream().filter(s -> ids.contains(s.getCashierSession().getId())
						&& !excluded.contains(s.getStatus()) && s.getDiscountAmount() != null && s.getDiscountAmount() != 0.0)
						.map(s -> new Object[] { s.getId(), s.getCashierSession().getId(), s.getDiscountAmount(),
								s.getDiscountSource() })
						.collect(Collectors.toList());
			}
			case "refusedAuthorisations": {
				Map<Long, Long> bySession = new java.util.LinkedHashMap<>();
				badgeLogs.stream().filter(b -> b.getSessionId() != null && ids.contains(b.getSessionId())
						&& Boolean.FALSE.equals(b.getSuccess())).forEach(b -> bySession.merge(b.getSessionId(), 1L, Long::sum));
				return bySession.entrySet().stream().map(e -> new Object[] { e.getKey(), e.getValue() })
						.collect(Collectors.toList());
			}
			default:
				return UNHANDLED;
		}
	});

	public final SessionPosMachineService machines = new SessionPosMachineService(null,
			proxy(SessionPosMachineRepository.class, (m, a) -> {
				if (!"findBySessionIdIn".equals(m)) {
					return UNHANDLED;
				}
				Collection<?> ids = (Collection<?>) a[0];
				List<SessionPosMachine> rows = new ArrayList<>();
				machineOf.forEach((sessionId, machine) -> {
					if (ids.contains(sessionId)) {
						SessionPosMachine row = new SessionPosMachine();
						row.setSessionId(sessionId);
						row.setPosMachine(machine);
						rows.add(row);
					}
				});
				return rows;
			}), null, null);

	public final DrawerHandoverService handover = new DrawerHandoverService(null,
			proxy(OpeningFloatRepository.class, (m, a) -> "findBySessionIdIn".equals(m)
					? openings.stream().filter(o -> ((Collection<?>) a[0]).contains(o.getSessionId()))
							.collect(Collectors.toList())
					: UNHANDLED),
			machines, proxy(OpeningUnverifiedRepository.class, (m, a) -> "findBySessionIdIn".equals(m)
					? unverified.stream().filter(o -> ((Collection<?>) a[0]).contains(o.getSessionId()))
							.collect(Collectors.toList())
					: UNHANDLED));

	public final SessionFiguresService service = new SessionFiguresService();

	public InMemorySessionFigures() {
		set(service, "figures", repository);
		set(service, "sessionPosMachineService", machines);
		set(service, "drawerHandoverService", handover);
	}

	/** A float proposed at a session's opening and its écart (opening - proposed). */
	public void openingGap(CashierSession session, double gap) {
		OpeningFloat row = new OpeningFloat();
		row.setSessionId(session.getId());
		row.setSession(session);
		row.setOpeningGap(gap);
		openings.add(row);
	}

	/** The session opened while its drawer's previous session was closed and not finally closed. */
	public void openedWithoutFinalClosing(CashierSession session, CashierSession previous) {
		OpeningUnverified row = new OpeningUnverified();
		row.setSessionId(session.getId());
		row.setSession(session);
		row.setPreviousSession(previous);
		unverified.add(row);
	}

	public static void set(Object target, String field, Object value) {
		try {
			Field f = target.getClass().getDeclaredField(field);
			f.setAccessible(true);
			f.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
