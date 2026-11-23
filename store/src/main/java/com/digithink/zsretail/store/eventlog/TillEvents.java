package com.digithink.zsretail.store.eventlog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.eventlog.service.AccessEvents;
import com.digithink.zsretail.core.eventlog.service.EventLog;
import com.digithink.zsretail.core.eventlog.service.NewEvent;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.model.enumeration.GrantedBy;
import com.digithink.zsretail.core.model.enumeration.ReturnType;
import com.digithink.zsretail.store.cashmovement.model.CashMovement;
import com.digithink.zsretail.store.cashmovement.model.ClosingWithdrawal;
import com.digithink.zsretail.store.cashmovement.model.OpeningFloat;
import com.digithink.zsretail.store.exception.TillRightRequiredException;

import lombok.extern.log4j.Log4j2;

/**
 * 3.4.0, event log step 2: the server's events of the till, one call at the end of each gesture's service method
 * (docs/modules/event-log.md, "Step 2"). Each method says what happened in French, for a store manager, and never throws:
 * a mistake here costs the event, never the gesture. The user, the badge's owner, the session and the POS machine come
 * by themselves (EventLog, StoreEventContext) when not given. Written after the gesture's transaction commits.
 */
@Component
@Log4j2
public class TillEvents {

	/** The cash difference that counts, as the closing check (CashierSessionService.checkCashDiscrepancy). */
	static final double DIFFERENCE = 0.01;

	private final EventLog eventLog;

	@Autowired
	public TillEvents(EventLog eventLog) {
		this.eventLog = eventLog;
	}

	/** The module is on: a caller asks before reading something only the event needs (a count of lines). */
	public boolean isOn() {
		return eventLog.isOn();
	}

	// ─── Tickets ─────────────────────────────────────────────────────

	/**
	 * A ticket validated: a sale (process-sale), a pending ticket completed (complete-pending), the part of a table
	 * ticket paid apart (split-and-pay, {@code splitFrom} the table ticket's number).
	 */
	public void saleValidated(SalesHeader ticket, int lineCount, List<Payment> payments, String cartId, boolean fromPending,
			String splitFrom) {
		run("SALE_VALIDATED", () -> {
			StringBuilder text = new StringBuilder("Vente validée : ticket ").append(ticket.getSalesNumber());
			if (fromPending) {
				text.append(" (repris d'un ticket en attente)");
			}
			if (splitFrom != null) {
				text.append(" (partie payée du ticket ").append(splitFrom).append(")");
			}
			text.append(", ").append(lines(lineCount)).append(", total ").append(money(ticket.getTotalAmount()));
			String paid = paymentsText(payments);
			if (paid != null) {
				text.append(". Paiement : ").append(paid);
			}
			if (positive(ticket.getChangeAmount())) {
				text.append(", rendu ").append(money(ticket.getChangeAmount()));
			}
			text.append('.');
			eventLog.write(NewEvent.of(EventCode.SALE_VALIDATED).subject(ticket.getId(), ticket.getSalesNumber())
					.session(sessionId(ticket), sessionNumber(ticket)).cart(cartId).amount(ticket.getTotalAmount())
					.details(text.toString()));
		});
	}

	/** A ticket put on hold (save-pending), or a pending ticket saved again (update-pending, the tables screen). */
	public void pendingSaved(SalesHeader ticket, int lineCount, String cartId, boolean again) {
		run("PENDING_SAVED", () -> {
			String text = (again ? "Ticket en attente mis à jour : " : "Ticket mis en attente : ") + ticket.getSalesNumber()
					+ table(ticket) + ", " + lines(lineCount) + ", total " + money(ticket.getTotalAmount()) + ".";
			eventLog.write(NewEvent.of(EventCode.PENDING_SAVED).subject(ticket.getId(), ticket.getSalesNumber())
					.session(sessionId(ticket), sessionNumber(ticket)).cart(cartId).amount(ticket.getTotalAmount())
					.details(text));
		});
	}

	/** A pending ticket deleted (cancel-pending): nothing sold, its lines given up. */
	public void pendingDeleted(SalesHeader ticket, int lineCount) {
		run("PENDING_DELETED", () -> {
			String text = "Ticket en attente supprimé : " + ticket.getSalesNumber() + table(ticket) + ", " + lines(lineCount)
					+ ", total " + money(ticket.getTotalAmount()) + ". Rien n'a été encaissé.";
			eventLog.write(NewEvent.of(EventCode.PENDING_DELETED).subject(ticket.getId(), ticket.getSalesNumber())
					.session(sessionId(ticket), sessionNumber(ticket)).amount(ticket.getTotalAmount()).details(text));
		});
	}

	/** The payment method of a validated ticket changed (Tickets page): old → new, its amount, the reason typed. */
	public void paymentChanged(SalesHeader ticket, Payment payment, PaymentMethod before, PaymentMethod after, String reason,
			CashierSession session) {
		run("PAYMENT_CHANGED", () -> {
			String text = "Mode de paiement changé sur le ticket " + ticket.getSalesNumber() + " : " + name(before) + " → "
					+ name(after) + ", " + money(payment.getTotalAmount())
					+ (blank(reason) ? "." : ". Motif : " + reason.trim());
			eventLog.write(NewEvent.of(EventCode.PAYMENT_CHANGED).subject(ticket.getId(), ticket.getSalesNumber())
					.session(session == null ? null : session.getId(), session == null ? null : session.getSessionNumber())
					.change(name(before), name(after)).amount(payment.getTotalAmount()).details(text));
		});
	}

	// ─── Returns ─────────────────────────────────────────────────────

	/** A return done (process-return): the badge's owner when one allowed it comes by itself. */
	public void returnDone(ReturnHeader done, int lineCount) {
		run("RETURN_DONE", () -> {
			SalesHeader original = done.getOriginalSalesHeader();
			String refund = done.getReturnType() == ReturnType.RETURN_VOUCHER ? "en bon d'achat" : "en espèces";
			String text = "Retour " + done.getReturnNumber()
					+ (original != null ? " du ticket " + original.getSalesNumber() : "") + " : " + lines(lineCount) + ", "
					+ money(done.getTotalReturnAmount()) + " remboursés " + refund
					+ (blank(done.getNotes()) ? "." : ". Note : " + done.getNotes().trim());
			CashierSession session = done.getCashierSession();
			eventLog.write(NewEvent.of(EventCode.RETURN_DONE).subject(done.getId(), done.getReturnNumber())
					.session(session == null ? null : session.getId(), session == null ? null : session.getSessionNumber())
					.amount(done.getTotalReturnAmount()).details(text));
		});
	}

	// ─── Cash ────────────────────────────────────────────────────────

	/** A cash movement recorded: its type, amount, note; who allowed it when a badge did. */
	public void cashMovement(CashMovement movement) {
		run("CASH_MOVEMENT", () -> {
			String kind = movement.getType() == null ? "Mouvement" : label(movement.getType().name());
			String text = kind + " de " + money(movement.getAmount()) + " (" + movement.getNumber() + ")"
					+ (blank(movement.getNote()) ? "." : ". Note : " + movement.getNote().trim());
			CashierSession session = movement.getCashierSession();
			NewEvent event = NewEvent.of(EventCode.CASH_MOVEMENT).subject(movement.getId(), movement.getNumber())
					.session(session == null ? null : session.getId(), session == null ? null : session.getSessionNumber())
					.change(null, movement.getType() == null ? null : movement.getType().name())
					.amount(movement.getAmount()).details(text);
			if (movement.getGrantedBy() == GrantedBy.BADGE && movement.getAuthorisedBy() != null) {
				event.authorisedBy(movement.getAuthorisedBy());
			}
			eventLog.write(event);
		});
	}

	// ─── Sessions ────────────────────────────────────────────────────

	/** A session opened, and its opening gap when the amount typed is not the float left in the drawer. */
	public void sessionOpened(CashierSession session, OpeningFloat opening) {
		run("SESSION_OPENED", () -> {
			eventLog.write(sessionEvent(EventCode.SESSION_OPENED, session).amount(session.getOpeningCash())
					.details("Session ouverte avec un fond de caisse de " + money(session.getOpeningCash()) + "."));
			if (opening != null && opening.getOpeningGap() != null && Math.abs(opening.getOpeningGap()) > DIFFERENCE) {
				eventLog.write(sessionEvent(EventCode.SESSION_OPENING_GAP, session)
						.change(money(opening.getProposedFloat()), money(session.getOpeningCash()))
						.amount(opening.getOpeningGap())
						.details("Écart à l'ouverture : fond laissé dans le tiroir " + money(opening.getProposedFloat())
								+ ", fond saisi " + money(session.getOpeningCash()) + ", écart "
								+ signed(opening.getOpeningGap()) + "."));
			}
		});
	}

	/**
	 * A session closed by its cashier: with a difference when the cash counted is not the cash expected (above 0.01,
	 * whatever ENABLE_CASH_DISCREPANCY_CHECK says), the difference (counted − expected) as the amount.
	 */
	public void sessionClosed(CashierSession session, Double expected, Double counted) {
		run("SESSION_CLOSED", () -> {
			if (expected != null && counted != null && Math.abs(counted - expected) > DIFFERENCE) {
				double difference = counted - expected;
				eventLog.write(sessionEvent(EventCode.SESSION_CLOSED_WITH_DIFFERENCE, session)
						.change(money(expected), money(counted)).amount(difference)
						.details("Session clôturée avec un écart : espèces attendues " + money(expected) + ", comptées "
								+ money(counted) + ", écart " + signed(difference)
								+ (difference < 0 ? " (manquant)." : " (excédent).")));
				return;
			}
			eventLog.write(sessionEvent(EventCode.SESSION_CLOSED, session).change(money(expected), money(counted))
					.amount(counted).details("Session clôturée sans écart : espèces attendues " + money(expected)
							+ ", comptées " + money(counted) + "."));
		});
	}

	/** A session finally closed by a responsible: his count against the cashier's, the drawer's withdrawal. */
	public void sessionVerified(CashierSession session, UserAccount responsible, ClosingWithdrawal withdrawal) {
		run("SESSION_VERIFIED", () -> {
			StringBuilder text = new StringBuilder("Clôture définitive de la session ").append(session.getSessionNumber())
					.append(" : compté par la caisse ").append(money(session.getPosUserClosureCash()))
					.append(", par le responsable ").append(money(session.getResponsibleClosureCash()));
			if (withdrawal != null) {
				text.append(". Retrait du tiroir : ").append(money(withdrawal.getAmountTaken())).append(" retiré, ")
						.append(money(withdrawal.getAmountLeft())).append(" laissé");
			}
			if (!blank(session.getVerificationNotes())) {
				text.append(". Note : ").append(session.getVerificationNotes().trim());
			}
			text.append('.');
			NewEvent event = sessionEvent(EventCode.SESSION_VERIFIED, session)
					.change(money(session.getPosUserClosureCash()), money(session.getResponsibleClosureCash()))
					.amount(session.getResponsibleClosureCash()).details(text.toString());
			if (responsible != null) {
				event.user(responsible);
			}
			eventLog.write(event);
		});
	}

	/**
	 * Step 3: a session opened while the previous session of its drawer is closed but not verified (allowed when
	 * VERIFY_PREVIOUS_SESSION_BEFORE_OPENING is off; refused, so no event, when it is on).
	 */
	public void sessionOpenedPreviousUnverified(CashierSession session, CashierSession previous) {
		run("SESSION_OPENED_PREVIOUS_UNVERIFIED", () -> {
			String cashier = previous.getCashier() != null ? AccessEvents.who(previous.getCashier()) : null;
			eventLog.write(sessionEvent(EventCode.SESSION_OPENED_PREVIOUS_UNVERIFIED, session)
					.change(previous.getSessionNumber(), session.getSessionNumber())
					.details("Session " + session.getSessionNumber() + " ouverte alors que la session précédente du tiroir, "
							+ previous.getSessionNumber() + (cashier != null ? " (" + cashier + ")" : "")
							+ ", est clôturée mais pas encore vérifiée par un responsable."));
		});
	}

	// ─── Till rights refused at the request ──────────────────────────

	/**
	 * Step 3: a request refused 403 TILL_RIGHT_REQUIRED (the role does not hold the right and no valid grant came with
	 * it). Written at once: the request's transaction has rolled back.
	 * @param gesture what the request did, e.g. "Vente"
	 */
	public void tillRightRefused(TillRightRequiredException refusal, String gesture) {
		run("TILL_RIGHT_REFUSED", () -> {
			BadgePermission action = refusal.getAction();
			eventLog.writeNow(NewEvent.of(EventCode.TILL_RIGHT_REFUSED)
					.subject(action != null ? action.name() : null, AccessEvents.actionLabel(action))
					.change(null, refusal.getReason() != null ? refusal.getReason().name() : null)
					.details("Refusé à la requête (" + gesture + ") : droit « " + AccessEvents.actionLabel(action) + " »"
							+ (refusal.getLineIndex() != null ? " (ligne " + (refusal.getLineIndex() + 1) + ")" : "")
							+ ", " + reason(refusal.getReason()) + "."));
		});
	}

	/** Why no grant counted, in French (TillRightRequiredException.Reason). */
	static String reason(TillRightRequiredException.Reason reason) {
		if (reason == null) {
			return "refusé";
		}
		switch (reason) {
			case NO_GRANT:
				return "le rôle n'a pas ce droit et aucun badge n'accompagnait la requête";
			case GRANT_UNKNOWN:
				return "autorisation de badge inconnue";
			case GRANT_USED:
				return "autorisation de badge déjà utilisée";
			case GRANT_EXPIRED:
				return "autorisation de badge expirée";
			case GRANT_OTHER_SESSION:
				return "autorisation de badge d'une autre session";
			case GRANT_OTHER_ACTION:
				return "autorisation de badge donnée pour une autre action";
			default:
				return reason.name();
		}
	}

	// ─── Texts ───────────────────────────────────────────────────────

	private static NewEvent sessionEvent(EventCode code, CashierSession session) {
		return NewEvent.of(code).subject(session.getId(), session.getSessionNumber())
				.session(session.getId(), session.getSessionNumber());
	}

	/** Runs one event's making: nothing at all when the module is off; a mistake (a missing value) is one WARN. */
	private void run(String what, Runnable making) {
		if (!eventLog.isOn()) {
			return;
		}
		try {
			making.run();
		} catch (RuntimeException e) {
			log.warn("Event log: {} not made ({})", what, e.toString());
		}
	}

	static String money(Double amount) {
		return amount == null ? "—" : String.format(Locale.FRANCE, "%.3f", amount);
	}

	static String signed(Double amount) {
		return amount == null ? "—" : (amount > 0 ? "+" : "") + money(amount);
	}

	static String lines(int count) {
		return count + (count > 1 ? " lignes" : " ligne");
	}

	private static String table(SalesHeader ticket) {
		return ticket.getTableNumber() == null ? "" : " (table " + ticket.getTableNumber() + ")";
	}

	private static String paymentsText(List<Payment> payments) {
		if (payments == null || payments.isEmpty()) {
			return null;
		}
		List<String> parts = new ArrayList<>();
		for (Payment payment : payments) {
			parts.add(name(payment.getPaymentMethod()) + " " + money(payment.getTotalAmount()));
		}
		return String.join(", ", parts);
	}

	private static String name(PaymentMethod method) {
		if (method == null) {
			return "—";
		}
		return !blank(method.getName()) ? method.getName() : method.getCode();
	}

	/** The cash movement types in French (CashMovementType). */
	private static String label(String type) {
		switch (type) {
			case "PRELEVEMENT":
				return "Prélèvement";
			case "DEPENSE":
				return "Dépense";
			case "APPORT":
				return "Apport";
			default:
				return type;
		}
	}

	private static Long sessionId(SalesHeader ticket) {
		return ticket.getCashierSession() != null ? ticket.getCashierSession().getId() : ticket.getCashierSessionId();
	}

	private static String sessionNumber(SalesHeader ticket) {
		return ticket.getCashierSession() != null ? ticket.getCashierSession().getSessionNumber() : null;
	}

	private static boolean positive(Double value) {
		return value != null && value > 0.0005;
	}

	private static boolean blank(String text) {
		return text == null || text.trim().isEmpty();
	}
}
