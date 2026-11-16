package com.digithink.zsretail.store.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.enumeration.ReturnType;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.store.cashmovement.model.CashMovement;
import com.digithink.zsretail.store.cashmovement.service.CashMovementTotals;
import com.digithink.zsretail.store.cashmovement.service.DrawerHandoverService;
import com.digithink.zsretail.store.dto.SessionDashboardDTO;
import com.digithink.zsretail.store.model.ScannedTicketPayment;
import com.digithink.zsretail.store.posmachine.service.SessionPosMachineService;
import com.digithink.zsretail.store.repository.SessionFiguresRepository;

/**
 * 3.2.0, the differences, step B: the Sessions list's line of many sessions at once (SessionDashboardDTO, its
 * differences by SessionDashboardDTO.fromEntity): the sales, returns, scanned tickets and cash movements of a group of
 * sessions in one query each (by chunks of {@link #CHUNK} ids, under SQL Server's 2,100 parameters), then the same sums
 * as the session's details (CashierSessionService.getSessionDetails, kept equal by CashDifferencesTest). Used by the
 * Sessions list (getSessionHistory) and the page "Écarts de caisse" (CashDifferencesService): one computation.
 */
@Service
public class SessionFiguresService {

	static final int CHUNK = 1000;

	/** The sales that count, as the Sessions list and the details: neither PENDING nor CANCELLED. */
	static final List<TransactionStatus> NOT_COUNTED = Arrays.asList(TransactionStatus.PENDING,
			TransactionStatus.CANCELLED);

	@Autowired
	private SessionFiguresRepository figures;

	@Autowired
	private SessionPosMachineService sessionPosMachineService;

	@Autowired(required = false)
	private DrawerHandoverService drawerHandoverService;

	/** A session's sums, as getSessionDetails adds them up. */
	public static final class Figures {
		private final List<Double> sales = new ArrayList<>();
		private final List<Object[]> returns = new ArrayList<>();
		private final List<ScannedTicketPayment> scannedTickets = new ArrayList<>();
		private final List<CashMovement> movements = new ArrayList<>();

		public long salesCount() {
			return sales.size();
		}

		public double totalSales() {
			return sales.stream().mapToDouble(amount -> amount != null ? amount : 0.0).sum();
		}

		public long returnsCount() {
			return returns.size();
		}

		public double totalReturns() {
			return returns.stream().mapToDouble(r -> amount(r[2])).sum();
		}

		public double returnsOf(ReturnType type) {
			return returns.stream().filter(r -> r[1] == type).mapToDouble(r -> amount(r[2])).sum();
		}

		/** Step C: the session's cash movements by type (the tickets X / Z's totals: CashMovementTotals). */
		public CashMovementTotals movementTotals() {
			return CashMovementTotals.of(movements);
		}

		/** What the system side adds to opening + sales - simple returns: the scanned face gap and the movements' net. */
		public double systemExtra() {
			return faceGap(scannedTickets) + CashMovementTotals.of(movements).getNet().doubleValue();
		}

		private static double amount(Object value) {
			return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
		}
	}

	/**
	 * The scanned tickets' face total minus their net (deduction + excess kept): the system side of a scanned-tickets
	 * method is its face total (CashierSessionService.scannedFaceGap, one session; here and there the same sum).
	 */
	public static double faceGap(List<ScannedTicketPayment> tickets) {
		BigDecimal gap = BigDecimal.ZERO;
		for (ScannedTicketsSummary.Method method : ScannedTicketsSummary.byMethodCode(tickets).values()) {
			gap = gap.add(method.getFace().subtract(method.getNet()));
		}
		return gap.doubleValue();
	}

	/** The figures of these sessions, by session id (every id in it, a session without anything has empty figures). */
	public Map<Long, Figures> of(Collection<Long> sessionIds) {
		Map<Long, Figures> out = new LinkedHashMap<>();
		sessionIds.forEach(id -> out.put(id, new Figures()));
		for (List<Long> chunk : chunks(sessionIds)) {
			for (Object[] row : figures.saleAmounts(chunk, NOT_COUNTED)) {
				out.get(((Number) row[0]).longValue()).sales.add(row[1] != null ? ((Number) row[1]).doubleValue() : null);
			}
			for (Object[] row : figures.returnAmounts(chunk)) {
				out.get(((Number) row[0]).longValue()).returns.add(row);
			}
			for (ScannedTicketPayment ticket : figures.scannedTickets(chunk, NOT_COUNTED)) {
				out.get(ticket.getPayment().getSalesHeader().getCashierSession().getId()).scannedTickets.add(ticket);
			}
			for (CashMovement movement : figures.cashMovements(chunk)) {
				out.get(movement.getCashierSession().getId()).movements.add(movement);
			}
		}
		return out;
	}

	/**
	 * The Sessions list's lines of these sessions, in their order: SessionDashboardDTO.fromEntity on their figures, with
	 * the POS machine and the écart d'ouverture (null: no float proposed).
	 */
	public List<SessionDashboardDTO> dashboards(List<CashierSession> sessions) {
		if (sessions == null || sessions.isEmpty()) {
			return new ArrayList<>();
		}
		return dashboards(sessions, of(sessions.stream().map(CashierSession::getId).collect(Collectors.toList())));
	}

	/** The same from figures already read (of): the page "Écarts de caisse" reads them once for its two blocks. */
	public List<SessionDashboardDTO> dashboards(List<CashierSession> sessions, Map<Long, Figures> all) {
		if (sessions == null || sessions.isEmpty()) {
			return new ArrayList<>();
		}
		List<Long> ids = sessions.stream().map(CashierSession::getId).collect(Collectors.toList());
		Map<Long, String> machines = new HashMap<>();
		Map<Long, Double> gaps = new HashMap<>();
		for (List<Long> chunk : chunks(ids)) {
			machines.putAll(sessionPosMachineService.namesBySession(chunk));
			if (drawerHandoverService != null) {
				gaps.putAll(drawerHandoverService.openingGapsBySession(chunk));
			}
		}
		List<SessionDashboardDTO> dtos = new ArrayList<>();
		for (CashierSession session : sessions) {
			Figures f = all.get(session.getId());
			SessionDashboardDTO dto = SessionDashboardDTO.fromEntity(session, f.salesCount(), f.totalSales(),
					f.returnsCount(), f.totalReturns(), f.returnsOf(ReturnType.SIMPLE_RETURN),
					f.returnsOf(ReturnType.RETURN_VOUCHER), null, f.systemExtra());
			dto.setPosMachineName(machines.get(session.getId()));
			dto.setOpeningGap(gaps.get(session.getId()));
			dtos.add(dto);
		}
		return dtos;
	}

	/** The ids by groups of {@link #CHUNK}. */
	public static List<List<Long>> chunks(Collection<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return Collections.emptyList();
		}
		List<Long> all = new ArrayList<>(ids);
		List<List<Long>> chunks = new ArrayList<>();
		for (int i = 0; i < all.size(); i += CHUNK) {
			chunks.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
		}
		return chunks;
	}
}
