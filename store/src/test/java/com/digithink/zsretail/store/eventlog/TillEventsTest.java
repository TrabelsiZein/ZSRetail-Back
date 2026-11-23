package com.digithink.zsretail.store.eventlog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.eventlog.EventImportance;
import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.GrantedBy;
import com.digithink.zsretail.core.model.enumeration.ReturnType;
import com.digithink.zsretail.core.support.InMemoryEventLog;
import com.digithink.zsretail.store.cashmovement.model.CashMovement;
import com.digithink.zsretail.store.cashmovement.model.CashMovementType;
import com.digithink.zsretail.store.cashmovement.model.ClosingWithdrawal;
import com.digithink.zsretail.store.cashmovement.model.OpeningFloat;

/**
 * 3.4.0, event log step 2: each server event of the till, its code, importance, subject, values, amount and the French
 * text a store manager reads; the logged user by itself; nothing when the module is off; a mistake in the making of an
 * event never reaches the gesture.
 */
class TillEventsTest {

	final InMemoryEventLog log = new InMemoryEventLog().logged(user(7L, "amira", "Amira Caissière", "POS_USER"));
	final TillEvents events = new TillEvents(log.eventLog());
	final CashierSession session = session();
	final PaymentMethod cash = method("ESP", "Espèces");
	final PaymentMethod card = method("CB", "Carte bancaire");

	@Test
	@DisplayName("A sale: SALE_VALIDATED (Step), the ticket, its session, the cart, the total, lines, payments and change")
	void sale() {
		SalesHeader ticket = ticket(12L, "LOC001261123004", 45.5);
		ticket.setChangeAmount(4.5);
		events.saleValidated(ticket, 3, Arrays.asList(payment(cash, 50.0)), "c0ffee00-0000-4000-8000-000000000001", false,
				null);
		EventLogEntry row = log.only("SALE_VALIDATED");
		assertEquals(EventImportance.STEP, row.getImportance());
		assertEquals("TICKET", row.getSubjectType());
		assertEquals("12", row.getSubjectId());
		assertEquals("LOC001261123004", row.getSubjectLabel());
		assertEquals(Long.valueOf(3), row.getSessionId());
		assertEquals("SESSION261123001", row.getSessionNumber());
		assertEquals("c0ffee00-0000-4000-8000-000000000001", row.getCartId());
		assertEquals(45.5, row.getAmount());
		assertEquals("Vente validée : ticket LOC001261123004, 3 lignes, total 45,500. Paiement : Espèces 50,000, rendu"
				+ " 4,500.", row.getDetails());
		assertEquals("amira", row.getUserLogin());
		assertEquals("Amira Caissière", row.getUserName());
		assertEquals("POS_USER", row.getUserRole());
	}

	@Test
	@DisplayName("A pending ticket completed, a table ticket's part paid apart: SALE_VALIDATED saying so; no cart: none")
	void saleFromPendingAndSplit() {
		events.saleValidated(ticket(13L, "LOC001261123005", 10.0), 1, null, null, true, null);
		events.saleValidated(ticket(14L, "LOC001261123006", 20.0), 2, null, null, false, "LOC001261123002");
		assertEquals("Vente validée : ticket LOC001261123005 (repris d'un ticket en attente), 1 ligne, total 10,000.",
				log.rows.get(0).getDetails());
		assertEquals("Vente validée : ticket LOC001261123006 (partie payée du ticket LOC001261123002), 2 lignes, total"
				+ " 20,000.", log.rows.get(1).getDetails());
		assertNull(log.rows.get(0).getCartId());
	}

	@Test
	@DisplayName("Pending: saved and saved again (PENDING_SAVED, Step), deleted (PENDING_DELETED, Notable) with lines and total")
	void pending() {
		SalesHeader held = ticket(20L, "LOC001261123007", 30.0);
		held.setTableNumber(4);
		events.pendingSaved(held, 2, "c0ffee00-0000-4000-8000-000000000002", false);
		events.pendingSaved(held, 3, null, true);
		events.pendingDeleted(held, 3);
		assertEquals(Arrays.asList("PENDING_SAVED", "PENDING_SAVED", "PENDING_DELETED"), log.codes());
		assertEquals("Ticket mis en attente : LOC001261123007 (table 4), 2 lignes, total 30,000.",
				log.rows.get(0).getDetails());
		assertEquals("Ticket en attente mis à jour : LOC001261123007 (table 4), 3 lignes, total 30,000.",
				log.rows.get(1).getDetails());
		EventLogEntry deleted = log.only("PENDING_DELETED");
		assertEquals(EventImportance.NOTABLE, deleted.getImportance());
		assertEquals(30.0, deleted.getAmount());
		assertEquals("Ticket en attente supprimé : LOC001261123007 (table 4), 3 lignes, total 30,000. Rien n'a été encaissé.",
				deleted.getDetails());
		assertEquals(Long.valueOf(1), log.rows.get(0).getSequenceNo());
		assertEquals(Long.valueOf(2), log.rows.get(1).getSequenceNo()); // Step: 1, 2; Notable: its own 1
		assertEquals(Long.valueOf(1), deleted.getSequenceNo());
	}

	@Test
	@DisplayName("A payment method changed: PAYMENT_CHANGED (Alert), old → new as names, the amount, the reason")
	void paymentChanged() {
		SalesHeader ticket = ticket(12L, "LOC001261123004", 45.5);
		events.paymentChanged(ticket, payment(cash, 20.0), cash, card, "  Erreur de saisie ", session);
		EventLogEntry row = log.only("PAYMENT_CHANGED");
		assertEquals(EventImportance.ALERT, row.getImportance());
		assertEquals("Espèces", row.getOldValue());
		assertEquals("Carte bancaire", row.getNewValue());
		assertEquals(20.0, row.getAmount());
		assertEquals("Mode de paiement changé sur le ticket LOC001261123004 : Espèces → Carte bancaire, 20,000. Motif :"
				+ " Erreur de saisie", row.getDetails());
		assertEquals(Long.valueOf(3), row.getSessionId());
	}

	@Test
	@DisplayName("A return: RETURN_DONE (Notable), the ticket returned, the lines, the amount and how it was refunded")
	void returnDone() {
		ReturnHeader done = new ReturnHeader();
		done.setId(40L);
		done.setReturnNumber("RET-202611-0012");
		done.setOriginalSalesHeader(ticket(12L, "LOC001261123004", 45.5));
		done.setReturnType(ReturnType.RETURN_VOUCHER);
		done.setTotalReturnAmount(15.0);
		done.setCashierSession(session);
		events.returnDone(done, 1);
		EventLogEntry row = log.only("RETURN_DONE");
		assertEquals(EventImportance.NOTABLE, row.getImportance());
		assertEquals("RETURN", row.getSubjectType());
		assertEquals("RET-202611-0012", row.getSubjectLabel());
		assertEquals(15.0, row.getAmount());
		assertEquals("Retour RET-202611-0012 du ticket LOC001261123004 : 1 ligne, 15,000 remboursés en bon d'achat.",
				row.getDetails());
	}

	@Test
	@DisplayName("A cash movement: CASH_MOVEMENT (Notable), its type, amount, note; the badge's owner when a badge allowed it")
	void cashMovement() {
		UserAccount karim = user(1L, "karim", "Karim Responsable", "RESPONSIBLE");
		CashMovement byBadge = movement(CashMovementType.DEPENSE, 12.0, "Café", GrantedBy.BADGE, karim);
		CashMovement byRole = movement(CashMovementType.PRELEVEMENT, 100.0, null, GrantedBy.ROLE, karim);
		events.cashMovement(byBadge);
		events.cashMovement(byRole);
		EventLogEntry badge = log.rows.get(0);
		assertEquals("CASH_MOVEMENT", badge.getCode());
		assertEquals(EventImportance.NOTABLE, badge.getImportance());
		assertEquals("DEPENSE", badge.getNewValue());
		assertEquals(12.0, badge.getAmount());
		assertEquals("Dépense de 12,000 (SESSION261123001-M01). Note : Café", badge.getDetails());
		assertEquals(Long.valueOf(1), badge.getAuthorisedById());
		assertEquals("Karim Responsable", badge.getAuthorisedByName());
		assertEquals("Prélèvement de 100,000 (SESSION261123001-M01).", log.rows.get(1).getDetails());
		assertNull(log.rows.get(1).getAuthorisedById()); // the user's own role: nobody's badge
	}

	@Test
	@DisplayName("A session opened: SESSION_OPENED with the float; SESSION_OPENING_GAP (Notable) only with a gap")
	void sessionOpened() {
		session.setOpeningCash(80.0);
		OpeningFloat opening = new OpeningFloat();
		opening.setProposedFloat(100.0);
		opening.setOpeningGap(-20.0);
		events.sessionOpened(session, opening);
		events.sessionOpened(session, null);
		assertEquals(Arrays.asList("SESSION_OPENED", "SESSION_OPENING_GAP", "SESSION_OPENED"), log.codes());
		assertEquals("Session ouverte avec un fond de caisse de 80,000.", log.rows.get(0).getDetails());
		assertEquals(80.0, log.rows.get(0).getAmount());
		EventLogEntry gap = log.only("SESSION_OPENING_GAP");
		assertEquals(EventImportance.NOTABLE, gap.getImportance());
		assertEquals("100,000", gap.getOldValue());
		assertEquals("80,000", gap.getNewValue());
		assertEquals(-20.0, gap.getAmount());
		assertEquals("Écart à l'ouverture : fond laissé dans le tiroir 100,000, fond saisi 80,000, écart -20,000.",
				gap.getDetails());
		assertEquals("SESSION", gap.getSubjectType());
		assertEquals("SESSION261123001", gap.getSubjectLabel());
	}

	@Test
	@DisplayName("A session closed: without a difference SESSION_CLOSED (Step); with one (above 0.01) SESSION_CLOSED_WITH_DIFFERENCE"
			+ " (Alert), expected → counted, the difference as the amount")
	void sessionClosed() {
		events.sessionClosed(session, 150.0, 150.004);
		events.sessionClosed(session, 150.0, 145.0);
		events.sessionClosed(session, 150.0, 152.5);
		EventLogEntry closed = log.rows.get(0);
		assertEquals("SESSION_CLOSED", closed.getCode());
		assertEquals(EventImportance.STEP, closed.getImportance());
		assertEquals("Session clôturée sans écart : espèces attendues 150,000, comptées 150,004.", closed.getDetails());
		EventLogEntry short5 = log.rows.get(1);
		assertEquals("SESSION_CLOSED_WITH_DIFFERENCE", short5.getCode());
		assertEquals(EventImportance.ALERT, short5.getImportance());
		assertEquals("150,000", short5.getOldValue());
		assertEquals("145,000", short5.getNewValue());
		assertEquals(-5.0, short5.getAmount(), 1e-9);
		assertEquals("Session clôturée avec un écart : espèces attendues 150,000, comptées 145,000, écart -5,000"
				+ " (manquant).", short5.getDetails());
		assertTrue(log.rows.get(2).getDetails().endsWith("écart +2,500 (excédent)."));
	}

	@Test
	@DisplayName("A session verified: SESSION_VERIFIED (Notable) by the responsible, his count against the cashier's, the withdrawal")
	void sessionVerified() {
		UserAccount karim = user(1L, "karim", "Karim Responsable", "RESPONSIBLE");
		session.setPosUserClosureCash(145.0);
		session.setResponsibleClosureCash(146.0);
		session.setVerificationNotes("Billet retrouvé");
		ClosingWithdrawal withdrawal = new ClosingWithdrawal();
		withdrawal.setAmountTaken(96.0);
		withdrawal.setAmountLeft(50.0);
		events.sessionVerified(session, karim, withdrawal);
		EventLogEntry row = log.only("SESSION_VERIFIED");
		assertEquals(EventImportance.NOTABLE, row.getImportance());
		assertEquals("karim", row.getUserLogin()); // the responsible, not the logged cashier
		assertEquals("145,000", row.getOldValue());
		assertEquals("146,000", row.getNewValue());
		assertEquals(146.0, row.getAmount());
		assertEquals("Clôture définitive de la session SESSION261123001 : compté par la caisse 145,000, par le responsable"
				+ " 146,000. Retrait du tiroir : 96,000 retiré, 50,000 laissé. Note : Billet retrouvé.", row.getDetails());
	}

	@Test
	@DisplayName("Off: nothing; a value missing (no ticket) costs the event, never the gesture")
	void offAndMistakes() {
		log.on = false;
		events.saleValidated(ticket(12L, "X", 1.0), 1, null, null, false, null);
		assertTrue(log.rows.isEmpty());
		assertEquals(false, events.isOn());
		log.on = true;
		assertDoesNotThrow(() -> events.saleValidated(null, 1, null, null, false, null));
		assertDoesNotThrow(() -> events.sessionClosed(null, 1.0, 2.0));
		assertDoesNotThrow(() -> events.cashMovement(null));
		assertTrue(log.rows.isEmpty());
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	static UserAccount user(Long id, String login, String name, String role) {
		UserAccount user = new UserAccount();
		user.setId(id);
		user.setUsername(login);
		user.setFullName(name);
		AppRole appRole = new AppRole();
		appRole.setName(role);
		user.setAppRole(appRole);
		return user;
	}

	private static CashierSession session() {
		CashierSession session = new CashierSession();
		session.setId(3L);
		session.setSessionNumber("SESSION261123001");
		return session;
	}

	private SalesHeader ticket(Long id, String number, Double total) {
		SalesHeader ticket = new SalesHeader();
		ticket.setId(id);
		ticket.setSalesNumber(number);
		ticket.setTotalAmount(total);
		ticket.setCashierSession(session);
		return ticket;
	}

	private static PaymentMethod method(String code, String name) {
		PaymentMethod method = new PaymentMethod();
		method.setCode(code);
		method.setName(name);
		return method;
	}

	private static Payment payment(PaymentMethod method, Double amount) {
		Payment payment = new Payment();
		payment.setPaymentMethod(method);
		payment.setTotalAmount(amount);
		return payment;
	}

	private CashMovement movement(CashMovementType type, Double amount, String note, GrantedBy grantedBy,
			UserAccount authorisedBy) {
		CashMovement movement = new CashMovement();
		movement.setId(60L);
		movement.setNumber("SESSION261123001-M01");
		movement.setCashierSession(session);
		movement.setType(type);
		movement.setAmount(amount);
		movement.setNote(note);
		movement.setGrantedBy(grantedBy);
		movement.setAuthorisedBy(authorisedBy);
		return movement;
	}
}
