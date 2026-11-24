package com.digithink.zsretail.core.eventlog.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.LoyaltyProgram;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.Promotion;
import com.digithink.zsretail.core.model.SalesDiscount;
import com.digithink.zsretail.core.model.SalesPrice;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.service.GeneralSetupChangeListener;

import lombok.extern.log4j.Log4j2;

/**
 * 3.4.0, event log step 9: the administrator's gestures (users, roles, badges, settings, prices, promotions, stock,
 * cost, inventories, purchases, receptions, imports, payment methods, loyalty), one call where the gesture really
 * happens, through {@link EventLog#write} (after the caller's commit when there is one). Like AccessEvents: it never
 * throws, and when the module is off it builds and reads nothing. The user is the logged one (EventLog). The French
 * sentence names everything as text, so a row stays readable after a rename or a delete.
 * <ul>
 * <li>An event only when a value really changed: the callers give the values before and after
 * ({@link #describe}), a save that changed nothing writes nothing.</li>
 * <li>Never a password, a badge code or a secret setting's value ({@link #secret}).</li>
 * <li>A bulk gesture (an import) is one event with its counts.</li>
 * </ul>
 * docs/modules/event-log.md, "Step 9".
 */
@Component
@Log4j2
public class AdminEvents implements GeneralSetupChangeListener {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

	/** A setting whose value is never written (its change is, masked). */
	private static final Pattern SECRET = Pattern.compile("PASSWORD|PASSWD|SECRET|TOKEN|API_?KEY|PRIVATE|_KEY$|^KEY_");
	static final String MASK = "******";

	private final EventLog eventLog;

	public AdminEvents(EventLog eventLog) {
		this.eventLog = eventLog;
	}

	public boolean isOn() {
		return eventLog.isOn();
	}

	// ─── Users ───────────────────────────────────────────────────────

	/** A user's values as the event compares them (never the password nor the badge's code itself). */
	public static Map<String, String> describe(UserAccount u) {
		Map<String, String> m = new LinkedHashMap<>();
		if (u == null) {
			return m;
		}
		m.put("Nom", u.getFullName());
		m.put("E-mail", u.getEmail());
		m.put("Rôle", roleName(u.getAppRole()));
		m.put("Actif", Boolean.FALSE.equals(u.getActive()) ? "non" : "oui");
		// the badge: whether there is one and an opaque mark of which (a changed code shows, never the code)
		m.put("Badge", u.getBadgeCode() == null || u.getBadgeCode().trim().isEmpty() ? "aucun"
				: "badge " + Integer.toHexString(u.getBadgeCode().trim().hashCode() & 0xfff));
		m.put("Expiration du badge", u.getBadgeExpirationDate() == null ? null : u.getBadgeExpirationDate().format(TIME));
		return m;
	}

	public void userCreated(UserAccount u) {
		run("USER_CREATED", () -> {
			Map<String, String> d = describe(u);
			eventLog.write(NewEvent.of(EventCode.USER_CREATED).subject(u.getId(), u.getUsername())
					.change(null, d.get("Rôle")).details(AccessEvents.sentence("Utilisateur créé : " + who(u) + ", rôle "
							+ d.get("Rôle") + ("aucun".equals(d.get("Badge")) ? "" : ", avec un badge"))));
		});
	}

	/**
	 * A user's edit: the fields changed (USER_UPDATED), the role (USER_ROLE_CHANGED), active (USER_ENABLED /
	 * USER_DISABLED), the password set (PASSWORD_CHANGED, never its value). Nothing when nothing changed.
	 */
	public void userUpdated(Map<String, String> before, UserAccount u, boolean passwordSet) {
		run("USER_UPDATED", () -> {
			Map<String, String> after = describe(u);
			Map<String, String> plain = new LinkedHashMap<>(before);
			Map<String, String> now = new LinkedHashMap<>(after);
			for (String special : new String[] { "Rôle", "Actif" }) {
				plain.remove(special);
				now.remove(special);
			}
			List<String> changes = changes(plain, now);
			if (!changes.isEmpty()) {
				eventLog.write(NewEvent.of(EventCode.USER_UPDATED).subject(u.getId(), u.getUsername())
						.details("Utilisateur " + who(u) + " modifié : " + String.join(" ; ", changes) + "."));
			}
			if (!Objects.equals(before.get("Rôle"), after.get("Rôle"))) {
				eventLog.write(NewEvent.of(EventCode.USER_ROLE_CHANGED).subject(u.getId(), u.getUsername())
						.change(before.get("Rôle"), after.get("Rôle")).details("Rôle de " + who(u) + " changé : "
								+ text(before.get("Rôle")) + " → " + text(after.get("Rôle")) + "."));
			}
			if (!Objects.equals(before.get("Actif"), after.get("Actif"))) {
				status(u, "oui".equals(after.get("Actif")));
			}
			if (passwordSet) {
				eventLog.write(NewEvent.of(EventCode.PASSWORD_CHANGED).subject(u.getId(), u.getUsername())
						.details(AccessEvents.sentence("Mot de passe de " + who(u) + " changé par un administrateur")));
			}
		});
	}

	public void userStatusChanged(UserAccount u, boolean active) {
		run("USER_STATUS", () -> status(u, active));
	}

	private void status(UserAccount u, boolean active) {
		eventLog.write(NewEvent.of(active ? EventCode.USER_ENABLED : EventCode.USER_DISABLED).subject(u.getId(), u.getUsername())
				.details(AccessEvents.sentence("Utilisateur " + who(u) + (active ? " réactivé" : " désactivé"))));
	}

	public void userDeleted(UserAccount u) {
		run("USER_DELETED", () -> eventLog.write(NewEvent.of(EventCode.USER_DELETED).subject(u.getId(), u.getUsername())
				.change(roleName(u.getAppRole()), null)
				.details(AccessEvents.sentence("Utilisateur supprimé : " + who(u) + ", rôle " + roleName(u.getAppRole())))));
	}

	// ─── Badges ──────────────────────────────────────────────────────

	/** A badge made for a user (its code never written); {@code replaced}: he had one, now no longer valid. */
	public void badgeGenerated(UserAccount u, boolean replaced) {
		run("BADGE_GENERATED", () -> eventLog.write(NewEvent.of(EventCode.BADGE_GENERATED).subject(u.getId(), u.getUsername())
				.badgeOwner(u)
				.details("Badge généré pour " + who(u) + (replaced ? " (l'ancien badge ne passe plus)" : "") + ".")));
	}

	public void badgeRevoked(UserAccount u, String reason) {
		run("BADGE_REVOKED", () -> eventLog.write(NewEvent.of(EventCode.BADGE_REVOKED).subject(u.getId(), u.getUsername())
				.badgeOwner(u).change(null, reason)
				.details("Badge de " + who(u) + " révoqué" + (blank(reason) ? "." : " : " + AccessEvents.sentence(reason.trim())))));
	}

	public void badgeReactivated(UserAccount u, String formerReason) {
		run("BADGE_REACTIVATED", () -> eventLog.write(NewEvent.of(EventCode.BADGE_REACTIVATED).subject(u.getId(), u.getUsername())
				.badgeOwner(u).change(formerReason, null)
				.details("Badge de " + who(u) + " réactivé" + (blank(formerReason) ? "." : " (il avait été révoqué : "
						+ formerReason.trim() + ")."))));
	}

	// ─── Roles ───────────────────────────────────────────────────────

	public void roleCreated(AppRole role) {
		run("ROLE_CREATED", () -> eventLog.write(NewEvent.of(EventCode.ROLE_CREATED).subject(role.getId(), roleName(role))
				.details("Rôle créé : " + roleName(role) + ", " + count(role.getPermissions()) + ".")));
	}

	/** The rights of a role changed: each right added or taken off (a rename is ROLE_RENAMED). */
	public void roleRightsChanged(AppRole role, Collection<String> before, Collection<String> after) {
		run("ROLE_RIGHTS_CHANGED", () -> {
			Set<String> added = new TreeSet<>(after == null ? new TreeSet<>() : after);
			if (before != null) {
				added.removeAll(before);
			}
			Set<String> removed = new TreeSet<>(before == null ? new TreeSet<>() : before);
			if (after != null) {
				removed.removeAll(after);
			}
			if (added.isEmpty() && removed.isEmpty()) {
				return;
			}
			StringBuilder text = new StringBuilder("Droits du rôle ").append(roleName(role)).append(" changés :");
			if (!added.isEmpty()) {
				text.append(" ajoutés ").append(rights(added));
			}
			if (!removed.isEmpty()) {
				text.append(added.isEmpty() ? "" : " ;").append(" retirés ").append(rights(removed));
			}
			eventLog.write(NewEvent.of(EventCode.ROLE_RIGHTS_CHANGED).subject(role.getId(), roleName(role))
					.change(size(before) + " droits", size(after) + " droits").details(text.append(".").toString()));
		});
	}

	/** A role's name changed (shown name, the label): old → new; nothing when the same. */
	public void roleRenamed(AppRole role, String before, String after) {
		if (Objects.equals(norm(before), norm(after))) {
			return;
		}
		run("ROLE_RENAMED", () -> eventLog.write(NewEvent.of(EventCode.ROLE_RENAMED).subject(role.getId(), roleName(role))
				.change(before, after).details("Rôle renommé : " + text(before) + " → " + text(after) + ".")));
	}

	public void roleDeleted(AppRole role) {
		run("ROLE_DELETED", () -> eventLog.write(NewEvent.of(EventCode.ROLE_DELETED).subject(role.getId(), roleName(role))
				.details("Rôle supprimé : " + roleName(role) + ", " + count(role.getPermissions()) + ".")));
	}

	// ─── General setup ───────────────────────────────────────────────

	/**
	 * GeneralSetupService.updateFromAdmin, a value changed: SETTING_CHANGED, before → after (a secret masked). The
	 * event log's own settings write EVENTLOG_SETTINGS_CHANGED (EventLogSettingsChange), not this.
	 */
	@Override
	public void changed(String code, String oldValue, String newValue) {
		if (EventLogSettings.CODES.contains(code)) {
			return;
		}
		run("SETTING_CHANGED", () -> {
			boolean secret = secret(code);
			String before = secret ? MASK : oldValue;
			String after = secret ? MASK : newValue;
			String label = AdminLabels.SETTINGS.get(code);
			eventLog.write(NewEvent.of(EventCode.SETTING_CHANGED).subject(code, label != null ? label : code).change(before, after)
					.details("Paramètre " + (label != null ? "« " + label + " » (" + code + ")" : code) + " changé : "
							+ text(yesNo(before)) + " → " + text(yesNo(after)) + (secret ? " (valeur secrète, non écrite)." : ".")));
		});
	}

	/** A switch's value as the screen says it (true / false: oui / non); any other value as it is. */
	static String yesNo(String value) {
		return "true".equals(value) ? "oui" : "false".equals(value) ? "non" : value;
	}

	/** Rights as the Roles page names them, each key beside: "Licence (read:admin-license)". */
	static String rights(Collection<String> keys) {
		List<String> out = new ArrayList<>();
		for (String key : keys) {
			String label = AdminLabels.RIGHTS.get(key);
			if (label == null) {
				for (com.digithink.zsretail.core.model.enumeration.BadgePermission action : com.digithink.zsretail.core.model.enumeration.BadgePermission.values()) {
					if (action.key().equals(key)) {
						label = "Caisse : " + AccessEvents.actionLabel(action);
					}
				}
			}
			out.add(label == null ? key : label + " (" + key + ")");
		}
		return String.join(", ", out);
	}

	/** A setting whose value is never written: a password, a token, a key. */
	public static boolean secret(String code) {
		return code != null && SECRET.matcher(code.toUpperCase()).find();
	}

	// ─── Prices, discounts ───────────────────────────────────────────

	public static Map<String, String> describe(SalesPrice p) {
		Map<String, String> m = new LinkedHashMap<>();
		if (p == null) {
			return m;
		}
		m.put("Article", p.getItemNo());
		m.put("Variante", p.getVariantCode());
		m.put("Type", p.getSalesType() == null ? null : p.getSalesType().name());
		m.put("Code", p.getSalesCode());
		m.put("Prix", money(p.getUnitPrice()));
		m.put("TTC", p.getPriceIncludesVat() == null ? null : p.getPriceIncludesVat() ? "oui" : "non");
		m.put("Du", p.getStartingDate() == null ? null : p.getStartingDate().format(DAY));
		m.put("Au", p.getEndingDate() == null ? null : p.getEndingDate().format(DAY));
		m.put("Quantité minimum", number(p.getMinimumQuantity()));
		return m;
	}

	public static Map<String, String> describe(SalesDiscount d) {
		Map<String, String> m = new LinkedHashMap<>();
		if (d == null) {
			return m;
		}
		m.put("Type", d.getType() == null ? null : d.getType().name());
		m.put("Code", d.getCode());
		m.put("Client", d.getSalesType() == null ? null : d.getSalesType().name() + (blank(d.getSalesCode()) ? "" : " " + d.getSalesCode()));
		m.put("Remise", d.getLineDiscount() == null ? null : number(d.getLineDiscount()) + " %");
		m.put("Du", d.getStartingDate() == null ? null : d.getStartingDate().format(DAY));
		m.put("Au", d.getEndingDate() == null ? null : d.getEndingDate().format(DAY));
		return m;
	}

	/** A sales price created ({@code before} empty) or changed; nothing when no value changed. */
	public void priceSaved(Map<String, String> before, SalesPrice p) {
		run("PRICE_SAVED", () -> saved(EventCode.PRICE_SAVED, p.getId(), "Prix " + text(p.getItemNo()), before, describe(p),
				"Prix de vente"));
	}

	public void priceDeleted(SalesPrice p) {
		run("PRICE_DELETED", () -> deleted(EventCode.PRICE_DELETED, p.getId(), "Prix " + text(p.getItemNo()), describe(p),
				"Prix de vente"));
	}

	public void discountSaved(Map<String, String> before, SalesDiscount d) {
		run("PRICE_SAVED", () -> saved(EventCode.PRICE_SAVED, d.getId(), "Remise " + text(d.getCode()), before, describe(d),
				"Remise"));
	}

	public void discountDeleted(SalesDiscount d) {
		run("PRICE_DELETED", () -> deleted(EventCode.PRICE_DELETED, d.getId(), "Remise " + text(d.getCode()), describe(d),
				"Remise"));
	}

	/** Step 9: a local item's price changed on its card (not a sales price, not an own price). */
	public void itemPriceChanged(Object itemId, String itemLabel, Double before, Double after) {
		if (sameMoney(before, after)) {
			return;
		}
		run("ITEM_PRICE_CHANGED", () -> eventLog.write(NewEvent.of(EventCode.ITEM_PRICE_CHANGED).subject(itemId, itemLabel)
				.change(money(before), money(after)).amount(after)
				.details("Prix de " + itemLabel + " changé : " + text(money(before)) + " → " + text(money(after)) + ".")));
	}

	/** The store's own price of a head office item (set), or the head office's given back. */
	public void ownPrice(boolean set, Object itemId, String itemLabel, Double before, Double after) {
		if (sameMoney(before, after) && set) {
			return;
		}
		run("OWN_PRICE", () -> eventLog.write(NewEvent.of(set ? EventCode.OWN_PRICE_SET : EventCode.OWN_PRICE_GIVEN_BACK)
				.subject(itemId, itemLabel).change(money(before), money(after)).amount(after)
				.details((set ? "Prix propre fixé pour " : "Prix du siège rendu à ") + itemLabel + " : " + text(money(before))
						+ " → " + text(money(after)) + ".")));
	}

	// ─── Promotions ──────────────────────────────────────────────────

	public static Map<String, String> describe(Promotion p) {
		Map<String, String> m = new LinkedHashMap<>();
		if (p == null) {
			return m;
		}
		m.put("Nom", p.getName());
		m.put("Type", p.getPromotionType() == null ? null : p.getPromotionType().name());
		m.put("Portée", p.getScope() == null ? null : p.getScope().name());
		m.put("Article", p.getItem() != null ? p.getItem().getItemCode() : p.getNavItemNo());
		m.put("Famille", p.getItemFamily() == null ? null : String.valueOf(p.getItemFamily().getId()));
		m.put("Sous-famille", p.getItemSubFamily() == null ? null : String.valueOf(p.getItemSubFamily().getId()));
		m.put("Quantité minimum", p.getMinimumQuantity() == null ? null : String.valueOf(p.getMinimumQuantity()));
		m.put("Montant minimum", money(p.getMinimumAmount()));
		m.put("Avantage", p.getBenefitType() == null ? null : p.getBenefitType().name());
		m.put("Remise %", number(p.getDiscountPercentage()));
		m.put("Remise", money(p.getDiscountAmount()));
		m.put("Quantité offerte", p.getFreeQuantity() == null ? null : String.valueOf(p.getFreeQuantity()));
		m.put("Du", p.getStartDate() == null ? null : p.getStartDate().format(DAY));
		m.put("Au", p.getEndDate() == null ? null : p.getEndDate().format(DAY));
		m.put("Jours", p.getDayOfWeek());
		m.put("De", p.getTimeStart() == null ? null : p.getTimeStart().toString());
		m.put("À", p.getTimeEnd() == null ? null : p.getTimeEnd().toString());
		m.put("Active", Boolean.FALSE.equals(p.getActive()) ? "non" : "oui");
		return m;
	}

	public void promotionSaved(Map<String, String> before, Promotion p) {
		run("PROMOTION_SAVED", () -> saved(EventCode.PROMOTION_SAVED, p.getId(), "Promotion " + text(p.getCode()), before,
				describe(p), "Promotion " + text(p.getCode())));
	}

	public void promotionDeleted(Promotion p) {
		run("PROMOTION_DELETED", () -> deleted(EventCode.PROMOTION_DELETED, p.getId(), "Promotion " + text(p.getCode()),
				describe(p), "Promotion " + text(p.getCode())));
	}

	// ─── Stock, cost, inventories ────────────────────────────────────

	/** Stock corrected by hand: before, the change, after, the reason. */
	public void stockAdjusted(Object itemId, String itemLabel, BigDecimal before, BigDecimal delta, String reason, String note) {
		if (delta == null || delta.signum() == 0) {
			return;
		}
		run("STOCK_ADJUSTED", () -> {
			BigDecimal after = before == null ? null : before.add(delta);
			eventLog.write(NewEvent.of(EventCode.STOCK_ADJUSTED).subject(itemId, itemLabel)
					.change(qty(before), qty(after))
					.details("Stock de " + itemLabel + " ajusté : " + text(qty(before)) + " → " + text(qty(after)) + " ("
							+ (delta.signum() > 0 ? "+" : "") + qty(delta) + (blank(reason) ? "" : ", " + reason)
							+ (blank(note) ? "" : ", " + note.trim()) + ")."));
		});
	}

	public void costAdjusted(Object itemId, String itemLabel, Double before, Double after, String reason) {
		if (sameMoney(before, after)) {
			return;
		}
		run("COST_ADJUSTED", () -> eventLog.write(NewEvent.of(EventCode.COST_ADJUSTED).subject(itemId, itemLabel)
				.change(money(before), money(after)).amount(after)
				.details("CMP de " + itemLabel + " ajusté : " + text(money(before)) + " → " + text(money(after))
						+ (blank(reason) ? "." : " (" + reason.trim() + ")."))));
	}

	public void inventoryCreated(Object id, String number, int lines) {
		run("INVENTORY_CREATED", () -> eventLog.write(NewEvent.of(EventCode.INVENTORY_CREATED).subject(id, number)
				.details("Inventaire " + text(number) + " créé : " + lines + (lines > 1 ? " lignes." : " ligne."))));
	}

	/** An inventory applied: its lines, the stock raised and lowered (units), the lines with a difference. */
	public void inventoryValidated(Object id, String number, int lines, int different, BigDecimal up, BigDecimal down) {
		run("INVENTORY_VALIDATED", () -> eventLog.write(NewEvent.of(EventCode.INVENTORY_VALIDATED).subject(id, number)
				.details("Inventaire " + text(number) + " validé : " + lines + " lignes, " + different
						+ " avec un écart (+" + qty(up) + " / -" + qty(down) + " unités).")));
	}

	public void inventoryDeleted(Object id, String number) {
		run("INVENTORY_DELETED", () -> eventLog.write(NewEvent.of(EventCode.INVENTORY_DELETED).subject(id, number)
				.details("Inventaire " + text(number) + " supprimé (un brouillon : le stock n'avait pas changé).")));
	}

	// ─── Purchases, receptions, imports ──────────────────────────────

	public void purchaseProcessed(Object id, String number, String vendor, int lines, Double total) {
		run("PURCHASE_PROCESSED", () -> eventLog.write(NewEvent.of(EventCode.PURCHASE_PROCESSED).subject(id, number)
				.amount(total).details("Achat " + text(number) + " enregistré" + (blank(vendor) ? "" : " (" + vendor.trim() + ")")
						+ " : " + lines + (lines > 1 ? " lignes, " : " ligne, ") + "total " + text(money(total))
						+ ", stock et coûts mis à jour.")));
	}

	/** A delivery received: its lines, the quantities sent and received, the lines with a gap. */
	public void receptionDone(Object id, String number, int lines, BigDecimal sent, BigDecimal received, int gaps) {
		run("RECEPTION_DONE", () -> eventLog.write(NewEvent.of(EventCode.RECEPTION_DONE).subject(id, number)
				.change(qty(sent), qty(received)).details("Réception " + text(number) + " : " + lines + " lignes, " + qty(sent)
						+ " envoyées, " + qty(received) + " reçues" + (gaps > 0 ? ", " + gaps + " ligne(s) avec un écart." : "."))));
	}

	/** One import run (Data import page): its type and counts, never one event per row. */
	public void importRun(String type, int total, int success, int errors) {
		run("IMPORT_RUN", () -> eventLog.write(NewEvent.of(EventCode.IMPORT_RUN).subject(type, "Import " + text(type))
				.change(String.valueOf(total), String.valueOf(success))
				.details("Import " + text(type) + " : " + total + " lignes lues, " + success + " enregistrées, " + errors
						+ " en erreur.")));
	}

	// ─── Payment methods, loyalty ────────────────────────────────────

	public static Map<String, String> describe(PaymentMethod p) {
		Map<String, String> m = new LinkedHashMap<>();
		if (p == null) {
			return m;
		}
		m.put("Nom", p.getName());
		m.put("Type", p.getType() == null ? null : p.getType().name());
		m.put("Actif", p.getActive() == null ? null : Boolean.FALSE.equals(p.getActive()) ? "non" : "oui");
		m.put("Longueur du titre", p.getTitleNumberLength() == null ? null : String.valueOf(p.getTitleNumberLength()));
		m.put("Description", p.getDescription());
		return m;
	}

	public void paymentMethodSaved(Map<String, String> before, PaymentMethod p) {
		run("PAYMENT_METHOD_SAVED", () -> saved(EventCode.PAYMENT_METHOD_SAVED, p.getId(), "Mode " + text(p.getCode()), before,
				describe(p), "Mode de paiement " + text(p.getCode())));
	}

	public void paymentMethodDeleted(PaymentMethod p) {
		run("PAYMENT_METHOD_DELETED", () -> deleted(EventCode.PAYMENT_METHOD_DELETED, p.getId(), "Mode " + text(p.getCode()),
				describe(p), "Mode de paiement " + text(p.getCode())));
	}

	/** Points given or taken by hand on a loyalty member's account (money: an Alert). */
	public void loyaltyPointsAdjusted(Object memberId, String memberLabel, Number before, Number after, String reason) {
		if (Objects.equals(before == null ? null : before.doubleValue(), after == null ? null : after.doubleValue())) {
			return;
		}
		run("LOYALTY_POINTS_ADJUSTED", () -> eventLog.write(NewEvent.of(EventCode.LOYALTY_POINTS_ADJUSTED)
				.subject(memberId, memberLabel).change(points(before), points(after))
				.details("Points de " + memberLabel + " ajustés à la main : " + text(points(before)) + " → " + text(points(after))
						+ (blank(reason) ? "." : " (" + reason.trim() + ")."))));
	}

	public static Map<String, String> describe(LoyaltyProgram p) {
		Map<String, String> m = new LinkedHashMap<>();
		if (p == null) {
			return m;
		}
		m.put("Nom", p.getName());
		m.put("Du", p.getStartDate() == null ? null : p.getStartDate().format(DAY));
		m.put("Au", p.getEndDate() == null ? null : p.getEndDate().format(DAY));
		m.put("Points par dinar", number(p.getPointsPerDinar()));
		m.put("Valeur d'un point (millimes)", p.getPointValueMillimes() == null ? null : String.valueOf(p.getPointValueMillimes()));
		m.put("Points minimum pour payer", p.getMinimumRedemptionPoints() == null ? null : String.valueOf(p.getMinimumRedemptionPoints()));
		m.put("Part maximum payée en points (%)", number(p.getMaximumRedemptionPercentage()));
		m.put("Expiration des points (jours)", p.getPointsExpiryDays() == null ? null : String.valueOf(p.getPointsExpiryDays()));
		m.put("Paliers", p.getEarningTiers() == null ? null : p.getEarningTiers().stream()
				.map(t -> "dès " + text(money(t.getThresholdAmount())) + " : " + text(number(t.getPointsPerDinar())) + " pt/DT")
				.reduce((a, b) -> a + " | " + b).orElse(""));
		m.put("Actif", p.getActive() == null ? null : Boolean.FALSE.equals(p.getActive()) ? "non" : "oui");
		return m;
	}

	public void loyaltyProgramSaved(Map<String, String> before, LoyaltyProgram p) {
		run("LOYALTY_PROGRAM_SAVED", () -> saved(EventCode.LOYALTY_PROGRAM_SAVED, p.getId(), "Programme " + text(p.getProgramCode()),
				before, describe(p), "Programme de fidélité " + text(p.getProgramCode())));
	}

	public void loyaltyProgramDeleted(LoyaltyProgram p) {
		run("LOYALTY_PROGRAM_DELETED", () -> deleted(EventCode.LOYALTY_PROGRAM_DELETED, p.getId(),
				"Programme " + text(p.getProgramCode()), describe(p), "Programme de fidélité " + text(p.getProgramCode())));
	}

	// ─── The common part ─────────────────────────────────────────────

	/** Created (before empty: its values) or changed (the fields changed); nothing when no value changed. */
	private void saved(EventCode code, Object id, String label, Map<String, String> before, Map<String, String> after,
			String what) {
		boolean created = before == null || before.isEmpty();
		List<String> changes = created ? values(after) : changes(before, after);
		if (changes.isEmpty()) {
			return;
		}
		eventLog.write(NewEvent.of(code).subject(id, label).details(what + (created ? " créé : " : " modifié : ")
				+ String.join(" ; ", changes) + "."));
	}

	private void deleted(EventCode code, Object id, String label, Map<String, String> before, String what) {
		eventLog.write(NewEvent.of(code).subject(id, label)
				.details(what + " supprimé" + (before.isEmpty() ? "." : " : " + String.join(" ; ", values(before)) + ".")));
	}

	/** "Field : before → after" for each value that changed. */
	static List<String> changes(Map<String, String> before, Map<String, String> after) {
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, String> e : after.entrySet()) {
			String old = norm(before.get(e.getKey()));
			String now = norm(e.getValue());
			if (!Objects.equals(old, now)) {
				out.add(e.getKey() + " : " + text(old) + " → " + text(now));
			}
		}
		return out;
	}

	private static List<String> values(Map<String, String> m) {
		List<String> out = new ArrayList<>();
		m.forEach((k, v) -> {
			if (norm(v) != null) {
				out.add(k + " " + v.trim());
			}
		});
		return out;
	}

	private static String norm(String v) {
		return v == null || v.trim().isEmpty() ? null : v.trim();
	}

	private static String text(String v) {
		return v == null || v.trim().isEmpty() ? "—" : v.trim();
	}

	private static boolean blank(String v) {
		return v == null || v.trim().isEmpty();
	}

	private static String who(UserAccount u) {
		return AccessEvents.who(u) + (u != null && u.getUsername() != null && !u.getUsername().equals(AccessEvents.who(u))
				? " (" + u.getUsername() + ")" : "");
	}

	private static String roleName(AppRole role) {
		if (role == null) {
			return null;
		}
		return role.getLabel() != null && !role.getLabel().trim().isEmpty() ? role.getLabel().trim() : role.getName();
	}

	private static String count(Collection<String> rights) {
		int n = size(rights);
		return n + (n > 1 ? " droits" : " droit");
	}

	private static int size(Collection<String> c) {
		return c == null ? 0 : c.size();
	}

	/** Money to the millime, "12,500"; null stays null. */
	static String money(Double v) {
		return v == null ? null : BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
	}

	private static boolean sameMoney(Double a, Double b) {
		return Objects.equals(money(a), money(b));
	}

	private static String number(Double v) {
		return v == null ? null : BigDecimal.valueOf(v).stripTrailingZeros().toPlainString().replace('.', ',');
	}

	private static String qty(BigDecimal v) {
		return v == null ? null : v.stripTrailingZeros().toPlainString().replace('.', ',');
	}

	private static String points(Number v) {
		return v == null ? null : number(v.doubleValue());
	}

	/** One event's making: nothing at all when the module is off; a mistake is one WARN, never the gesture's failure. */
	private void run(String what, Runnable making) {
		if (!eventLog.isOn()) {
			return;
		}
		try {
			making.run();
		} catch (RuntimeException e) {
			log.warn("Event log: {} not written ({})", what, e.toString());
		}
	}
}
