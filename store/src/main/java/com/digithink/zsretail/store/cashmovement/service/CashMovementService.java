package com.digithink.zsretail.store.cashmovement.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.store.cashmovement.CashMovementException;
import com.digithink.zsretail.store.cashmovement.dto.CashMovementDTO;
import com.digithink.zsretail.store.cashmovement.dto.CashMovementRequest;
import com.digithink.zsretail.store.cashmovement.model.CashMovement;
import com.digithink.zsretail.store.cashmovement.model.CashMovementType;
import com.digithink.zsretail.store.cashmovement.repository.CashMovementRepository;
import com.digithink.zsretail.store.posmachine.service.SessionPosMachineService;
import com.digithink.zsretail.store.service.TillRightsGuard;

/**
 * 3.2.0, POS machines step 5: the cash movements (docs/modules/cash-sessions.md, "Cash movements"). A movement is
 * recorded on the logged user's own OPENED session only (a sessionId sent must be it), with the right
 * till:cash-movement or a badge grant (TillRightsGuard), the session's row locked as the closing locks it (a movement
 * never slips past the expected cash computed at the closing). Never edited, never deleted. With the drawer of the POS
 * machine, the endpoint is refused from another PC than the session's (PosMachineSellingGuard, 409), as a sale.
 */
@Service
public class CashMovementService {

	/** The longest note. */
	public static final int MAX_NOTE_LENGTH = 255;

	private final CashMovementRepository movements;
	private final CashierSessionRepository sessions;
	private final TillRightsGuard tillRightsGuard;
	private final SessionPosMachineService sessionMachines;

	/** The time of a movement; a test sets its own. */
	Clock clock = Clock.systemDefaultZone();

	public CashMovementService(CashMovementRepository movements, CashierSessionRepository sessions,
			TillRightsGuard tillRightsGuard, SessionPosMachineService sessionMachines) {
		this.movements = movements;
		this.sessions = sessions;
		this.tillRightsGuard = tillRightsGuard;
		this.sessionMachines = sessionMachines;
	}

	/**
	 * Records a movement and returns its slip. Refused (CashMovementException, 400): a type, an amount or a note not
	 * valid, no open session of the user, a sessionId that is not the user's open session. Refused 403
	 * (TillRightRequiredException): neither the right nor a valid grant.
	 */
	@Transactional(rollbackFor = Exception.class)
	public CashMovementDTO record(UserAccount user, CashMovementRequest request) {
		CashMovementType type = type(request.getType());
		BigDecimal amount = amount(request.getAmount());
		String note = note(request.getNote(), type);

		CashierSession open = user == null ? null
				: sessions.findByCashierAndStatus(user, SessionStatus.OPENED).orElse(null);
		if (open == null) {
			throw new CashMovementException("CASH_MOVEMENT_NO_OPEN_SESSION",
					"You have no open session: a cash movement is recorded in your own open session only.");
		}
		if (request.getSessionId() != null && !request.getSessionId().equals(open.getId())) {
			throw new CashMovementException("CASH_MOVEMENT_NOT_YOUR_SESSION", "The session " + request.getSessionId()
					+ " is not your open session (" + open.getSessionNumber() + "): a cash movement is recorded in your own"
					+ " open session only.", "session", open.getSessionNumber());
		}
		// as the closing does: a movement and the closing of the same session one after the other
		CashierSession session = sessions.findByIdForUpdate(open.getId()).orElse(open);
		if (session.getStatus() != SessionStatus.OPENED) {
			throw new CashMovementException("CASH_MOVEMENT_NO_OPEN_SESSION",
					"You have no open session: a cash movement is recorded in your own open session only.");
		}

		TillRightsGuard.Pass pass = tillRightsGuard.checkCashMovement(request.getTillGrant(), user, session,
				"CASH " + session.getSessionNumber());

		CashMovement movement = new CashMovement();
		movement.setNumber(String.format("%s-M%02d", session.getSessionNumber(), movements.countByCashierSession(session) + 1));
		movement.setCashierSession(session);
		movement.setType(type);
		movement.setAmount(amount.doubleValue());
		movement.setNote(note);
		movement.setDoneBy(user);
		movement.setAuthorisedBy(pass.getAuthorisedBy() != null ? pass.getAuthorisedBy() : user);
		movement.setGrantedBy(pass.getGrantedBy());
		movement.setMovedAt(LocalDateTime.now(clock));
		movement.setCreatedBy(user.getUsername());
		movement.setUpdatedBy(user.getUsername());
		CashMovement saved = movements.save(movement);
		return dto(saved, sessionMachines.nameOf(session.getId()));
	}

	/** A session's movements, oldest first. */
	@Transactional(readOnly = true)
	public List<CashMovement> ofSession(CashierSession session) {
		return session == null ? new ArrayList<>() : movements.findByCashierSessionOrderByMovedAtAsc(session);
	}

	/** A session's movements as lines (its details). */
	@Transactional(readOnly = true)
	public List<CashMovementDTO> linesOfSession(CashierSession session) {
		String machine = session == null ? null : sessionMachines.nameOf(session.getId());
		return ofSession(session).stream().map(m -> dto(m, machine)).collect(Collectors.toList());
	}

	/** The movements of a day by their own time, as lines (the Journal's day), each with its session's POS machine. */
	@Transactional(readOnly = true)
	public List<CashMovementDTO> linesOfDay(LocalDate date) {
		List<CashMovement> day = movements.findByMovedAtGreaterThanEqualAndMovedAtLessThanOrderByMovedAtAsc(
				date.atStartOfDay(), date.plusDays(1).atStartOfDay());
		Collection<Long> sessionIds = day.stream().map(m -> m.getCashierSession().getId()).distinct()
				.collect(Collectors.toList());
		Map<Long, String> names = sessionMachines.namesBySession(sessionIds);
		return day.stream().map(m -> dto(m, names.get(m.getCashierSession().getId()))).collect(Collectors.toList());
	}

	/** A session's totals (in, out, per type). */
	@Transactional(readOnly = true)
	public CashMovementTotals totalsOf(CashierSession session) {
		return CashMovementTotals.of(ofSession(session));
	}

	public static CashMovementDTO dto(CashMovement movement, String posMachineName) {
		CashMovementDTO dto = new CashMovementDTO();
		dto.setId(movement.getId());
		dto.setNumber(movement.getNumber());
		dto.setMovedAt(movement.getMovedAt());
		dto.setType(movement.getType() != null ? movement.getType().name() : null);
		dto.setDirection(movement.getType() != null && movement.getType().isIn() ? "IN" : "OUT");
		dto.setAmount(movement.getAmount());
		dto.setNote(movement.getNote());
		CashierSession session = movement.getCashierSession();
		if (session != null) {
			dto.setSessionId(session.getId());
			dto.setSessionNumber(session.getSessionNumber());
			dto.setCashierName(name(session.getCashier()));
		}
		dto.setPosMachineName(posMachineName);
		dto.setAuthorisedByName(name(movement.getAuthorisedBy()));
		dto.setGrantedBy(movement.getGrantedBy() != null ? movement.getGrantedBy().name() : null);
		return dto;
	}

	private static String name(UserAccount user) {
		if (user == null) {
			return null;
		}
		String full = user.getFullName();
		return full != null && !full.trim().isEmpty() ? full.trim() : user.getUsername();
	}

	// ─── The request's checks ────────────────────────────────────────

	static CashMovementType type(String raw) {
		if (raw != null) {
			for (CashMovementType type : CashMovementType.values()) {
				if (type.name().equalsIgnoreCase(raw.trim())) {
					return type;
				}
			}
		}
		throw new CashMovementException("CASH_MOVEMENT_TYPE_INVALID",
				"The type of a cash movement is PRELEVEMENT, DEPENSE or APPORT, not '" + raw + "'.", "type", raw);
	}

	/** Above 0, rounded to the millime (half up): 0.0004 is refused. */
	static BigDecimal amount(Double raw) {
		BigDecimal amount = raw == null || raw.isNaN() || raw.isInfinite() ? null
				: BigDecimal.valueOf(raw).setScale(3, RoundingMode.HALF_UP);
		if (amount == null || amount.signum() <= 0) {
			throw new CashMovementException("CASH_MOVEMENT_AMOUNT_INVALID",
					"The amount of a cash movement is above 0 (to the millime).");
		}
		return amount;
	}

	static String note(String raw, CashMovementType type) {
		String note = raw == null ? null : raw.trim();
		if (note != null && note.isEmpty()) {
			note = null;
		}
		if (note == null && type.isNoteRequired()) {
			throw new CashMovementException("CASH_MOVEMENT_NOTE_REQUIRED",
					"A cash movement of type DEPENSE needs a note: what it was paid for.");
		}
		if (note != null && note.length() > MAX_NOTE_LENGTH) {
			throw new CashMovementException("CASH_MOVEMENT_NOTE_TOO_LONG",
					"The note of a cash movement has at most " + MAX_NOTE_LENGTH + " characters.", "max", MAX_NOTE_LENGTH);
		}
		return note;
	}
}
