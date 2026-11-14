package com.digithink.zsretail.store.cashmovement.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.store.cashmovement.CashMovementException;
import com.digithink.zsretail.store.cashmovement.dto.CashMovementDTO;
import com.digithink.zsretail.store.cashmovement.dto.ClosingWithdrawalDTO;
import com.digithink.zsretail.store.cashmovement.dto.OpeningFloatDTO;
import com.digithink.zsretail.store.cashmovement.model.ClosingChoice;
import com.digithink.zsretail.store.cashmovement.model.ClosingWithdrawal;
import com.digithink.zsretail.store.cashmovement.model.OpeningFloat;
import com.digithink.zsretail.store.cashmovement.repository.ClosingWithdrawalRepository;
import com.digithink.zsretail.store.cashmovement.repository.OpeningFloatRepository;
import com.digithink.zsretail.store.posmachine.DrawerOwner;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.posmachine.service.SessionPosMachineService;

/**
 * 3.2.0, the opening amount (docs/modules/cash-sessions.md, "The drawer from one session to the next").
 * <ul>
 * <li>At the responsible's verification of a CLOSED session, his required answer "Retrait du tiroir": ALL (taken = the
 * cash he counted), NONE (left = the cash he counted) or AMOUNT (above 0, not above the cash he counted); what is left is
 * the float of the next session on the same drawer. Outside the session's expected cash.</li>
 * <li>At the next opening on the same drawer (the POS machine when the drawer belongs to it, else the cashier): only the
 * most recent closed session of that drawer counts; verified with an answer and something left: its amount left is the
 * float proposed. The opening keeps the float proposed, its source and the écart (opening cash - proposed); nothing when
 * nothing was proposed.</li>
 * </ul>
 * A session verified before this step has no answer: nothing known (no proposal from it).
 */
@Service
public class DrawerHandoverService {

	static final List<SessionStatus> CLOSED = Arrays.asList(SessionStatus.CLOSED, SessionStatus.TERMINATED);

	private final ClosingWithdrawalRepository withdrawals;
	private final OpeningFloatRepository openings;
	private final SessionPosMachineService sessionMachines;

	/** The time of a withdrawal; a test sets its own. */
	Clock clock = Clock.systemDefaultZone();

	public DrawerHandoverService(ClosingWithdrawalRepository withdrawals, OpeningFloatRepository openings,
			SessionPosMachineService sessionMachines) {
		this.withdrawals = withdrawals;
		this.openings = openings;
		this.sessionMachines = sessionMachines;
	}

	/** The answer checked: the choice and the amounts taken and left, to the millime. */
	public static final class Answer {
		public final ClosingChoice choice;
		public final BigDecimal counted;
		public final BigDecimal taken;
		public final BigDecimal left;

		Answer(ClosingChoice choice, BigDecimal counted, BigDecimal taken) {
			this.choice = choice;
			this.counted = counted;
			this.taken = taken;
			this.left = counted.subtract(taken);
		}
	}

	/**
	 * The responsible's answer, checked before the verification changes anything (CashMovementException, 400):
	 * CLOSING_WITHDRAWAL_REQUIRED (no choice), CLOSING_WITHDRAWAL_CHOICE_INVALID, CLOSING_WITHDRAWAL_AMOUNT_INVALID (an
	 * amount not above 0 or above the cash counted, {max}).
	 */
	public static Answer check(String rawChoice, Double rawAmount, double cashCounted) {
		if (rawChoice == null || rawChoice.trim().isEmpty()) {
			throw new CashMovementException("CLOSING_WITHDRAWAL_REQUIRED", "Say what is taken out of the drawer at the"
					+ " verification: everything, nothing or an amount.");
		}
		ClosingChoice choice;
		try {
			choice = ClosingChoice.valueOf(rawChoice.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new CashMovementException("CLOSING_WITHDRAWAL_CHOICE_INVALID",
					"The drawer's withdrawal is ALL, NONE or AMOUNT, not '" + rawChoice + "'.", "choice", rawChoice);
		}
		BigDecimal counted = millimes(Math.max(0.0, cashCounted));
		switch (choice) {
			case ALL:
				return new Answer(choice, counted, counted);
			case NONE:
				return new Answer(choice, counted, BigDecimal.ZERO.setScale(3));
			default:
				BigDecimal amount = rawAmount == null || rawAmount.isNaN() || rawAmount.isInfinite() ? null : millimes(rawAmount);
				if (amount == null || amount.signum() <= 0 || amount.compareTo(counted) > 0) {
					throw new CashMovementException("CLOSING_WITHDRAWAL_AMOUNT_INVALID", "The amount taken out of the"
							+ " drawer is above 0 and not above the cash counted (" + counted.toPlainString() + ").", "max",
							counted.toPlainString());
				}
				return new Answer(choice, counted, amount);
		}
	}

	/** Records the answer of the session just verified (TERMINATED); the slip's number when something was taken. */
	public ClosingWithdrawal record(CashierSession session, UserAccount responsible, Answer answer) {
		ClosingWithdrawal row = new ClosingWithdrawal();
		row.setSession(session);
		row.setChoice(answer.choice);
		row.setCashCounted(answer.counted.doubleValue());
		row.setAmountTaken(answer.taken.doubleValue());
		row.setAmountLeft(answer.left.doubleValue());
		row.setDoneBy(responsible);
		row.setDoneAt(LocalDateTime.now(clock));
		row.setSlipNumber(answer.taken.signum() > 0 ? session.getSessionNumber() + "-PC" : null);
		return withdrawals.save(row);
	}

	/**
	 * The float proposed to an opening on this drawer: the drawer of the POS machine (machine, when the drawer belongs to
	 * it) or of the cashier. Only the most recent closed session of that drawer; verified with an answer that left
	 * something: its amount left. Empty otherwise (not verified, nothing left, verified before this step, no machine).
	 */
	public Optional<OpeningFloatDTO> proposalFor(UserAccount cashier, PosMachine machine) {
		List<CashierSession> last;
		if (sessionMachines.drawerOwner() == DrawerOwner.POS_MACHINE) {
			if (machine == null || machine.getId() == null) {
				return Optional.empty();
			}
			last = withdrawals.lastClosedOfMachine(machine.getId(), CLOSED, PageRequest.of(0, 1));
		} else {
			if (cashier == null) {
				return Optional.empty();
			}
			last = withdrawals.lastClosedOfCashier(cashier, CLOSED, PageRequest.of(0, 1));
		}
		if (last.isEmpty() || last.get(0).getStatus() != SessionStatus.TERMINATED) {
			return Optional.empty();
		}
		CashierSession source = last.get(0);
		Optional<ClosingWithdrawal> answer = withdrawals.findById(source.getId());
		if (!answer.isPresent() || answer.get().getAmountLeft() == null || answer.get().getAmountLeft() <= 0) {
			return Optional.empty();
		}
		OpeningFloatDTO proposal = new OpeningFloatDTO();
		proposal.setProposedFloat(answer.get().getAmountLeft());
		proposal.setSourceSessionId(source.getId());
		proposal.setSourceSessionNumber(source.getSessionNumber());
		proposal.setSourceVerifiedAt(answer.get().getDoneAt());
		return Optional.of(proposal);
	}

	/** At the opening of a session: its float proposed and the écart, when a float was proposed; nothing otherwise. */
	public Optional<OpeningFloat> recordOpening(CashierSession session, UserAccount cashier, PosMachine machine,
			java.util.function.Function<Long, CashierSession> sessionById) {
		Optional<OpeningFloatDTO> proposal = proposalFor(cashier, machine);
		if (!proposal.isPresent()) {
			return Optional.empty();
		}
		OpeningFloat row = new OpeningFloat();
		row.setSession(session);
		row.setProposedFloat(proposal.get().getProposedFloat());
		row.setSourceSession(sessionById.apply(proposal.get().getSourceSessionId()));
		row.setOpeningGap(millimes(session.getOpeningCash() == null ? 0.0 : session.getOpeningCash())
				.subtract(millimes(proposal.get().getProposedFloat())).doubleValue());
		return Optional.of(openings.save(row));
	}

	// ─── What the screens read ───────────────────────────────────────

	/** A session's answer; null without one (not verified, or verified before this step). */
	public ClosingWithdrawalDTO withdrawalOf(CashierSession session) {
		return session == null ? null
				: withdrawals.findById(session.getId()).map(w -> dto(w, session, sessionMachines.nameOf(session.getId())))
						.orElse(null);
	}

	/** A session's float proposed at its opening; null when none was proposed. */
	public OpeningFloatDTO openingOf(CashierSession session) {
		return session == null ? null
				: openings.findById(session.getId()).map(o -> dto(o, session, sessionMachines.nameOf(session.getId())))
						.orElse(null);
	}

	/** The écarts d'ouverture of these sessions (one query), by session id; a session without a proposal is not in it. */
	public Map<Long, Double> openingGapsBySession(Collection<Long> sessionIds) {
		if (sessionIds == null || sessionIds.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<Long, Double> gaps = new HashMap<>();
		openings.findBySessionIdIn(sessionIds).forEach(o -> gaps.put(o.getSessionId(), o.getOpeningGap()));
		return gaps;
	}

	/**
	 * The Journal's day: the withdrawals done that day that took something, as "Mouvements de caisse" lines of their own
	 * type PRELEVEMENT_CLOTURE (never in the drawer's in / out totals).
	 */
	public List<CashMovementDTO> closingLinesOfDay(LocalDate date) {
		List<ClosingWithdrawal> day = withdrawals.findByDoneAtGreaterThanEqualAndDoneAtLessThanOrderByDoneAtAsc(
				date.atStartOfDay(), date.plusDays(1).atStartOfDay()).stream()
				.filter(w -> w.getAmountTaken() != null && w.getAmountTaken() > 0).collect(Collectors.toList());
		Map<Long, String> names = sessionMachines.namesBySession(
				day.stream().map(ClosingWithdrawal::getSessionId).collect(Collectors.toList()));
		return day.stream().map(w -> {
			CashMovementDTO line = new CashMovementDTO();
			line.setNumber(w.getSlipNumber());
			line.setMovedAt(w.getDoneAt());
			line.setType(CLOSING_TYPE);
			line.setDirection("OUT");
			line.setAmount(w.getAmountTaken());
			CashierSession session = w.getSession();
			if (session != null) {
				line.setSessionId(session.getId());
				line.setSessionNumber(session.getSessionNumber());
				line.setCashierName(name(session.getCashier()));
			}
			line.setPosMachineName(names.get(w.getSessionId()));
			line.setAuthorisedByName(name(w.getDoneBy()));
			return line;
		}).collect(Collectors.toList());
	}

	/** The type of a closing withdrawal's line in the Journal's "Mouvements de caisse". */
	public static final String CLOSING_TYPE = "PRELEVEMENT_CLOTURE";

	/** The Journal's day: the sessions opened that day with a float proposed, and their écart. */
	public List<OpeningFloatDTO> openingsOfDay(LocalDate date) {
		List<OpeningFloat> day = openings.findBySession_OpenedAtGreaterThanEqualAndSession_OpenedAtLessThanOrderBySession_OpenedAtAsc(
				date.atStartOfDay(), date.plusDays(1).atStartOfDay());
		Map<Long, String> names = sessionMachines.namesBySession(
				day.stream().map(OpeningFloat::getSessionId).collect(Collectors.toList()));
		return day.stream().map(o -> dto(o, o.getSession(), names.get(o.getSessionId()))).collect(Collectors.toList());
	}

	static ClosingWithdrawalDTO dto(ClosingWithdrawal w, CashierSession session, String machine) {
		ClosingWithdrawalDTO dto = new ClosingWithdrawalDTO();
		dto.setSessionId(session.getId());
		dto.setSessionNumber(session.getSessionNumber());
		dto.setCashierName(name(session.getCashier()));
		dto.setPosMachineName(machine);
		dto.setChoice(w.getChoice() != null ? w.getChoice().name() : null);
		dto.setCashCounted(w.getCashCounted());
		dto.setAmountTaken(w.getAmountTaken());
		dto.setAmountLeft(w.getAmountLeft());
		dto.setDoneByName(name(w.getDoneBy()));
		dto.setDoneAt(w.getDoneAt());
		dto.setSlipNumber(w.getSlipNumber());
		return dto;
	}

	static OpeningFloatDTO dto(OpeningFloat o, CashierSession session, String machine) {
		OpeningFloatDTO dto = new OpeningFloatDTO();
		dto.setProposedFloat(o.getProposedFloat());
		CashierSession source = o.getSourceSession();
		if (source != null) {
			dto.setSourceSessionId(source.getId());
			dto.setSourceSessionNumber(source.getSessionNumber());
			dto.setSourceVerifiedAt(source.getVerifiedAt());
		}
		if (session != null) {
			dto.setSessionId(session.getId());
			dto.setSessionNumber(session.getSessionNumber());
			dto.setCashierName(name(session.getCashier()));
			dto.setOpenedAt(session.getOpenedAt());
			dto.setOpeningCash(session.getOpeningCash());
		}
		dto.setPosMachineName(machine);
		dto.setOpeningGap(o.getOpeningGap());
		return dto;
	}

	private static String name(UserAccount user) {
		if (user == null) {
			return null;
		}
		String full = user.getFullName();
		return full != null && !full.trim().isEmpty() ? full.trim() : user.getUsername();
	}

	static BigDecimal millimes(double value) {
		return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
	}
}
