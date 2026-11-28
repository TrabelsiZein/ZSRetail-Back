package com.digithink.zsretail.core.convention.service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.convention.enumeration.ConventionRecognition;
import com.digithink.zsretail.core.convention.model.Convention;
import com.digithink.zsretail.core.convention.model.ConventionCard;
import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.eventlog.service.AdminEvents;
import com.digithink.zsretail.core.eventlog.service.EventLog;
import com.digithink.zsretail.core.eventlog.service.NewEvent;

import lombok.extern.log4j.Log4j2;

/**
 * 3.6.0, conventions step 1, part B3: the events of the conventions (subject CONVENTION, Notable), through
 * {@link EventLog#write}: after the caller's commit, none when it rolls back. The event log is a store's only (decision
 * 3): on a head office EventLog is off and nothing is made. Like AdminEvents it never throws, and the French sentence
 * names everything as text (the code, the name, the organisation), so a row stays readable after a delete. A card's
 * holder phone is never in an event (decision 15; the cards' events come with part B4).
 */
@Component
@ConditionalOnInstallation(Installation.CONVENTION)
@Log4j2
public class ConventionEvents {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private final EventLog eventLog;

	public ConventionEvents(EventLog eventLog) {
		this.eventLog = eventLog;
	}

	/** The values an event compares, with the screen's French names (the status has its own events). */
	public static Map<String, String> describe(Convention c) {
		Map<String, String> m = new LinkedHashMap<>();
		if (c == null) {
			return m;
		}
		m.put("Nom", c.getName());
		m.put("Organisation", c.getOrganisation());
		m.put("Du", c.getStartDate() == null ? null : c.getStartDate().format(DAY));
		m.put("Au", c.getEndDate() == null ? null : c.getEndDate().format(DAY));
		m.put("Reconnaissance", c.getRecognition() == ConventionRecognition.NO_CARD ? "sans carte" : "cartes");
		m.put("Utilisations max par jour", c.getMaxUsesPerDay() == null ? null : String.valueOf(c.getMaxUsesPerDay()));
		m.put("Utilisations max par mois", c.getMaxUsesPerMonth() == null ? null : String.valueOf(c.getMaxUsesPerMonth()));
		m.put("Montant max par mois", AdminEvents.money(c.getMaxAmountPerMonth()));
		m.put("Préfixe des cartes", c.getCardPrefix());
		m.put("Chiffres des cartes", c.getCardDigits() == null ? null : String.valueOf(c.getCardDigits()));
		return m;
	}

	public void created(Convention c) {
		run(EventCode.CONVENTION_CREATED, () -> eventLog.write(NewEvent.of(EventCode.CONVENTION_CREATED)
				.subject(c.getId(), c.getCode()).details(label(c) + " créée : " + String.join(" ; ", values(describe(c)))
						+ ".")));
	}

	/** Each field changed, before → after; nothing when no value changed. */
	public void updated(Map<String, String> before, Convention c) {
		run(EventCode.CONVENTION_UPDATED, () -> {
			List<String> changes = AdminEvents.changes(before, describe(c));
			if (!changes.isEmpty()) {
				eventLog.write(NewEvent.of(EventCode.CONVENTION_UPDATED).subject(c.getId(), c.getCode())
						.details(label(c) + " modifiée : " + String.join(" ; ", changes) + "."));
			}
		});
	}

	/** Suspended or reactivated (only when the status really changed: the caller checks). */
	public void statusChanged(Convention c, boolean suspended) {
		EventCode code = suspended ? EventCode.CONVENTION_SUSPENDED : EventCode.CONVENTION_REACTIVATED;
		run(code, () -> eventLog.write(NewEvent.of(code).subject(c.getId(), c.getCode())
				.change(suspended ? "ACTIVE" : "SUSPENDED", suspended ? "SUSPENDED" : "ACTIVE")
				.details(label(c) + (suspended ? " suspendue." : " réactivée."))));
	}

	public void deleted(Convention c) {
		run(EventCode.CONVENTION_DELETED, () -> eventLog.write(NewEvent.of(EventCode.CONVENTION_DELETED)
				.subject(c.getId(), c.getCode()).details(label(c) + " supprimée : " + String.join(" ; ",
						values(describe(c))) + ".")));
	}

	// ─── The cards (part B4) ─────────────────────────────────────────

	/** One generation run: the count as the amount, the first and last numbers, the numbers skipped. */
	public void cardsGenerated(Convention c, int count, String first, String last, int skipped) {
		run(EventCode.CONVENTION_CARDS_GENERATED, () -> eventLog.write(NewEvent.of(EventCode.CONVENTION_CARDS_GENERATED)
				.subject(c.getId(), c.getCode()).amount((double) count)
				.details(count + (count > 1 ? " cartes générées" : " carte générée") + " pour la convention " + c.getCode()
						+ " : " + first + (count > 1 ? " à " + last : "") + (skipped == 0 ? "" : " (" + skipped
								+ (skipped > 1 ? " numéros sautés, déjà pris" : " numéro sauté, déjà pris") + ")")
						+ ".")));
	}

	/** Part B5: one import run: the cards accepted as the amount, the lines refused and the file's name in the details. */
	public void cardsImported(Convention c, int accepted, int refused, String fileName) {
		run(EventCode.CONVENTION_CARDS_IMPORTED, () -> eventLog.write(NewEvent.of(EventCode.CONVENTION_CARDS_IMPORTED)
				.subject(c.getId(), c.getCode()).amount((double) accepted)
				.details(accepted + (accepted > 1 ? " cartes importées" : " carte importée") + " pour la convention "
						+ c.getCode() + (fileName == null ? "" : " depuis « " + fileName + " »") + (refused == 0 ? ""
								: " (" + refused + (refused > 1 ? " lignes refusées" : " ligne refusée") + ")") + ".")));
	}

	/** Part B5: one export for the card printer: the cards written as the amount, the options in the details. */
	public void cardsExported(Convention c, int count, String statusLabel, boolean onlyNeverExported) {
		run(EventCode.CONVENTION_CARDS_EXPORTED, () -> eventLog.write(NewEvent.of(EventCode.CONVENTION_CARDS_EXPORTED)
				.subject(c.getId(), c.getCode()).amount((double) count)
				.details(count + (count > 1 ? " cartes exportées" : " carte exportée") + " pour la convention "
						+ c.getCode() + " (" + statusLabel + (onlyNeverExported ? ", jamais exportées" : "") + ").")));
	}

	/** A card added by hand: its number, its holder's name and employee number (never the phone). */
	public void cardAdded(Convention c, ConventionCard card) {
		run(EventCode.CONVENTION_CARD_ADDED, () -> eventLog.write(NewEvent.of(EventCode.CONVENTION_CARD_ADDED)
				.subject(card.getId(), card.getCardNumber()).details("Carte " + card.getCardNumber()
						+ " ajoutée à la convention " + c.getCode() + holder(card) + ".")));
	}

	/** One card blocked or unblocked (the caller writes only the cards whose status changed). */
	public void cardStatusChanged(Convention c, ConventionCard card, boolean blocked) {
		EventCode code = blocked ? EventCode.CONVENTION_CARD_BLOCKED : EventCode.CONVENTION_CARD_UNBLOCKED;
		run(code, () -> eventLog.write(NewEvent.of(code).subject(card.getId(), card.getCardNumber())
				.change(blocked ? "ACTIVE" : "BLOCKED", blocked ? "BLOCKED" : "ACTIVE")
				.details("Carte " + card.getCardNumber() + " de la convention " + c.getCode()
						+ (blocked ? " bloquée." : " débloquée."))));
	}

	/**
	 * The holder changed: the name and the employee number before → after; the phone only as "modifié", never its
	 * value (decision 15). Nothing when nothing changed.
	 */
	public void holderChanged(Convention c, ConventionCard card, String nameBefore, String employeeNoBefore,
			boolean phoneChanged) {
		run(EventCode.CONVENTION_CARD_HOLDER_CHANGED, () -> {
			Map<String, String> before = new LinkedHashMap<>();
			before.put("Titulaire", nameBefore);
			before.put("Matricule", employeeNoBefore);
			Map<String, String> after = new LinkedHashMap<>();
			after.put("Titulaire", card.getHolderName());
			after.put("Matricule", card.getHolderEmployeeNo());
			List<String> changes = new ArrayList<>(AdminEvents.changes(before, after));
			if (phoneChanged) {
				changes.add("Téléphone : modifié");
			}
			if (!changes.isEmpty()) {
				eventLog.write(NewEvent.of(EventCode.CONVENTION_CARD_HOLDER_CHANGED)
						.subject(card.getId(), card.getCardNumber()).details("Titulaire de la carte "
								+ card.getCardNumber() + " (convention " + c.getCode() + ") modifié : "
								+ String.join(" ; ", changes) + "."));
			}
		});
	}

	private static String holder(ConventionCard card) {
		String name = card.getHolderName();
		String number = card.getHolderEmployeeNo();
		return (name == null ? "" : ", titulaire " + name) + (number == null ? "" : ", matricule " + number);
	}

	/** "Convention MIN « Ministère »". */
	private static String label(Convention c) {
		return "Convention " + c.getCode() + (c.getName() == null ? "" : " « " + c.getName().trim() + " »");
	}

	private static List<String> values(Map<String, String> m) {
		List<String> out = new ArrayList<>();
		m.forEach((k, v) -> {
			if (v != null && !v.trim().isEmpty()) {
				out.add(k + " " + v.trim());
			}
		});
		return out;
	}

	/** Nothing at all when the event log is off; a mistake is one WARN, never the gesture's failure. */
	private void run(EventCode code, Runnable making) {
		if (!eventLog.stores(code)) {
			return;
		}
		try {
			making.run();
		} catch (RuntimeException e) {
			log.warn("Event log: {} not written ({})", code, e.toString());
		}
	}
}
