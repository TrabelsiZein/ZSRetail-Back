package com.digithink.zsretail.core.eventlog.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.repository.UserAccountRepository;

import lombok.extern.log4j.Log4j2;

/**
 * 3.4.0, event log step 3: the logins, the logout and the badge (docs/modules/event-log.md, "Step 3"). One call beside
 * what already happens (the login filter, POST /logout, BadgeService's badge_scan_log rows, the store's 403
 * TILL_RIGHT_REQUIRED). Each method says what happened in French, for a store manager, and never throws: a mistake here
 * costs the event, never the login or the scan. Off, nothing is built and nothing is read.
 * <ul>
 * <li>A refused login names the user only when the login typed is an existing user's (his login as stored, never the
 * text typed): a password typed in the login field never lands in the log. Otherwise the event says "Identifiant
 * inconnu" and keeps the request's address.</li>
 * <li>A badge's code is never written: the badge is named by its owner.</li>
 * </ul>
 */
@Component
@Log4j2
public class AccessEvents {

	/** What a refused login with a login that is nobody's shows in place of the text typed. */
	public static final String UNKNOWN_LOGIN = "Identifiant inconnu";

	/**
	 * The login-purpose header of the till's "Retour à l'administration" popup: its password check is the login
	 * request, not a login (ADMIN_RETURN_VERIFIED; a wrong password is a LOGIN_REFUSED that says so, still counted).
	 */
	public static final String TILL_ADMIN_RETURN = "TILL_ADMIN_RETURN";

	private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm:ss");

	private final EventLog eventLog;
	private final Function<String, UserAccount> userByLogin;
	private final Supplier<LocalDateTime> clock;
	/** Step 4: the refused logins in a row (one login, one address), in memory. */
	private final LoginTries tries = new LoginTries();

	@Autowired
	public AccessEvents(EventLog eventLog, UserAccountRepository users) {
		this(eventLog, login -> users.findByUsername(login).orElse(null));
	}

	/** With the lookup given: used by the tests. */
	public AccessEvents(EventLog eventLog, Function<String, UserAccount> userByLogin) {
		this(eventLog, userByLogin, LocalDateTime::now);
	}

	/** With the lookup and the clock given: used by the tests. */
	public AccessEvents(EventLog eventLog, Function<String, UserAccount> userByLogin, Supplier<LocalDateTime> clock) {
		this.eventLog = eventLog;
		this.userByLogin = userByLogin;
		this.clock = clock;
	}

	/** The module is on: a caller asks before reading something only the event needs. */
	public boolean isOn() {
		return eventLog.isOn();
	}

	// ─── Logins ──────────────────────────────────────────────────────

	/** A login answered with a token. */
	public void loginOk(UserAccount user, String address) {
		loginOk(user, address, null);
	}

	/** A login answered with a token; {@code purpose} TILL_ADMIN_RETURN: the till's admin-return password check. */
	public void loginOk(UserAccount user, String address, String purpose) {
		run("LOGIN_OK", () -> {
			tries.succeeded(LoginTries.loginKey(user.getId()), LoginTries.addressKey(address));
			if (adminReturn(purpose)) {
				eventLog.writeNow(NewEvent.of(EventCode.ADMIN_RETURN_VERIFIED).subject(user.getId(), user.getUsername())
						.user(user).from(address).details(sentence("Retour à l'administration — mot de passe vérifié pour "
								+ who(user) + from(address))));
				return;
			}
			eventLog.writeNow(NewEvent.of(EventCode.LOGIN_OK).subject(user.getId(), user.getUsername()).user(user)
					.from(address).details(sentence("Connexion de " + who(user) + role(user) + from(address))));
		});
	}

	private static boolean adminReturn(String purpose) {
		return purpose != null && TILL_ADMIN_RETURN.equalsIgnoreCase(purpose.trim());
	}

	/**
	 * A login refused (wrong password, unknown login, account disabled). The login typed is looked up: an existing
	 * user's login names him (as stored), anything else is "Identifiant inconnu"; the text typed is never kept. Step 4:
	 * Notable; from the {@link LoginTries#ALERT_FROM}th refusal in a row on one login or from one address,
	 * LOGIN_REFUSED_REPEATED (Alert), the run said in the details.
	 */
	public void loginRefused(String typedLogin, Exception failure, String address) {
		loginRefused(typedLogin, failure, address, null);
	}

	/** The same; {@code purpose} TILL_ADMIN_RETURN: the till's admin-return popup (said in the text, still counted). */
	public void loginRefused(String typedLogin, Exception failure, String address, String purpose) {
		String where = adminReturn(purpose) ? " (retour à l'administration)" : "";
		run("LOGIN_REFUSED", () -> {
			UserAccount known = known(typedLogin);
			LoginTries.Run run = tries.refused(known != null ? LoginTries.loginKey(known.getId()) : null,
					LoginTries.addressKey(address), clock.get());
			boolean repeated = run != null && run.count >= LoginTries.ALERT_FROM;
			NewEvent event = NewEvent.of(repeated ? EventCode.LOGIN_REFUSED_REPEATED : EventCode.LOGIN_REFUSED)
					.from(address);
			String text;
			if (known == null) {
				event.subject(null, UNKNOWN_LOGIN).user(null, null, null, null);
				text = "Connexion refusée : identifiant inconnu" + where + from(address) + ".";
			} else {
				event.subject(known.getId(), known.getUsername()).user(known);
				text = "Connexion refusée pour " + who(known) + " : " + reason(failure) + where + from(address) + ".";
			}
			if (repeated) {
				event.amount((double) run.count);
				text += " " + run.count + "e refus de suite " + (run.onLogin() ? "pour cet identifiant" : "depuis cette adresse")
						+ " depuis " + run.first.format(HOUR) + ".";
			}
			eventLog.writeNow(event.details(text));
		});
	}

	/** POST /logout: the logged user leaves (the token stays valid until it expires). */
	public void logout(UserAccount user) {
		run("LOGOUT", () -> eventLog.writeNow(NewEvent.of(EventCode.LOGOUT).subject(user.getId(), user.getUsername())
				.user(user).details(sentence("Déconnexion de " + who(user)))));
	}

	// ─── Badges ──────────────────────────────────────────────────────

	/**
	 * A badge opened a till action (grantedBy BADGE): at the scan for the customer list, when the request uses the
	 * scan's grant for the others. Written after the scan's or the request's transaction commits.
	 */
	public void badgeUsed(UserAccount scannedBy, UserAccount owner, BadgePermission action, Long sessionId) {
		run("TILL_RIGHT_USED", () -> {
			NewEvent event = NewEvent.of(EventCode.TILL_RIGHT_USED).subject(name(action), actionLabel(action))
					.authorisedBy(owner).session(sessionId, null)
					.details(sentence("Droit « " + actionLabel(action) + " » ouvert par le badge de " + who(owner)
							+ (scannedBy != null ? ", pour " + who(scannedBy) : "")));
			if (scannedBy != null) {
				event.user(scannedBy);
			}
			eventLog.write(event);
		});
	}

	/** A badge refused at the scan (unknown, revoked, expired, owner disabled, owner's role without the right). */
	public void badgeRefused(UserAccount scannedBy, UserAccount owner, BadgePermission action, String failureReason,
			Long sessionId) {
		run("TILL_RIGHT_REFUSED", () -> {
			NewEvent event = NewEvent.of(EventCode.TILL_RIGHT_REFUSED).subject(name(action), actionLabel(action))
					.change(null, failureReason).session(sessionId, null)
					.details("Badge refusé au scan pour « " + actionLabel(action) + " » : "
							+ scanRefusal(failureReason, owner) + ".");
			if (scannedBy != null) {
				event.user(scannedBy);
			}
			eventLog.writeNow(event);
		});
	}

	/**
	 * POST /badge/pass refused (403 TILL_RIGHT_REQUIRED): the till believed the role held the right, the server says it
	 * does not (the role changed since the login).
	 */
	public void rolePassRefused(UserAccount actor, BadgePermission action) {
		run("TILL_RIGHT_REFUSED", () -> {
			NewEvent event = NewEvent.of(EventCode.TILL_RIGHT_REFUSED).subject(name(action), actionLabel(action))
					.change(null, "ROLE_WITHOUT_RIGHT")
					.details("Refusé à la requête (passage sur le droit du rôle) : le rôle de "
							+ (actor != null ? who(actor) : "l'utilisateur") + " n'a pas le droit « "
							+ actionLabel(action) + " » (changé depuis la connexion). Un badge est demandé.");
			if (actor != null) {
				event.user(actor);
			}
			eventLog.writeNow(event);
		});
	}

	// ─── Texts ───────────────────────────────────────────────────────

	/** The till actions in French, as the till's badge popup names them. */
	public static String actionLabel(BadgePermission action) {
		if (action == null) {
			return "—";
		}
		switch (action) {
			case CONSULT_CUSTOMER_LIST:
				return "Consulter la liste des clients";
			case MAKE_RETURN:
				return "Effectuer un retour";
			case APPLY_LINE_DISCOUNT:
				return "Appliquer une remise sur la ligne";
			case APPLY_TOTAL_DISCOUNT:
				return "Appliquer une remise sur le total";
			case CLOSE_SESSION_WITH_DISCREPANCY:
				return "Fermer la session avec écart";
			case CASH_MOVEMENT:
				return "Mouvement de caisse";
			default:
				return action.name();
		}
	}

	/** The user's name as a person reads it: the full name, else the login. */
	public static String who(UserAccount user) {
		if (user == null) {
			return "—";
		}
		return user.getFullName() != null && !user.getFullName().trim().isEmpty() ? user.getFullName().trim()
				: user.getUsername();
	}

	private UserAccount known(String typedLogin) {
		if (typedLogin == null || typedLogin.trim().isEmpty()) {
			return null;
		}
		try {
			return userByLogin.apply(typedLogin);
		} catch (RuntimeException e) {
			return null; // not read: the login is treated as unknown, its text never kept
		}
	}

	static String reason(Exception failure) {
		if (failure instanceof BadCredentialsException) {
			return "mot de passe incorrect";
		}
		if (failure instanceof DisabledException) {
			return "compte désactivé";
		}
		if (failure instanceof LockedException) {
			return "compte verrouillé";
		}
		if (failure instanceof AccountExpiredException || failure instanceof CredentialsExpiredException) {
			return "compte expiré";
		}
		return "erreur du serveur à la connexion (" + (failure != null ? failure.getClass().getSimpleName() : "—") + ")";
	}

	static String scanRefusal(String failureReason, UserAccount owner) {
		String of = owner != null ? " de " + who(owner) : "";
		if (failureReason == null) {
			return "refusé";
		}
		switch (failureReason) {
			case "BADGE_NOT_EXISTS":
				return "badge inconnu";
			case "BADGE_REVOKED":
				return "badge" + of + " révoqué";
			case "BADGE_EXPIRED":
				return "badge" + of + " expiré";
			case "BADGE_OWNER_INACTIVE":
				return "badge" + of + ", compte désactivé";
			case "BADGE_NO_ACCESS":
				return "le rôle" + of + " n'a pas ce droit";
			default:
				return failureReason + of;
		}
	}

	/** Ends the text with one period (a name may end with one: "Amine K."). */
	public static String sentence(String text) {
		return text.endsWith(".") ? text : text + ".";
	}

	private static String role(UserAccount user) {
		if (user.getAppRole() == null) {
			return "";
		}
		String label = user.getAppRole().getLabel() != null ? user.getAppRole().getLabel() : user.getAppRole().getName();
		return label != null ? " (" + label + ")" : "";
	}

	private static String from(String address) {
		return address != null && !address.isEmpty() ? ", depuis " + address : "";
	}

	private static String name(BadgePermission action) {
		return action != null ? action.name() : null;
	}

	/** Runs one event's making: nothing at all when the module is off; a mistake (a missing value) is one WARN. */
	private void run(String what, Runnable making) {
		try {
			if (!eventLog.isOn()) {
				return;
			}
			making.run();
		} catch (RuntimeException e) {
			log.warn("Event log: {} not made ({})", what, e.toString());
		}
	}
}
