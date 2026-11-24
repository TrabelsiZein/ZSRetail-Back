package com.digithink.zsretail.core.eventlog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.eventlog.service.AdminEvents;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.LoyaltyProgram;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.Promotion;
import com.digithink.zsretail.core.model.SalesPrice;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.support.InMemoryEventLog;

/**
 * 3.4.0, event log step 9: the administrator's gestures (AdminEvents). An event only when a value really changed; the
 * fields that changed, before → after; never a password, a badge code or a secret setting's value; one event for a bulk
 * import; the catalogue's importance; off: nothing; a failure never reaches the gesture.
 */
class AdminEventsTest {

	final InMemoryEventLog log = new InMemoryEventLog();
	final AdminEvents events = new AdminEvents(log.eventLog());

	static AppRole role(String name, String label, String... rights) {
		AppRole role = new AppRole(name, label, false);
		role.setId((long) name.length());
		role.setPermissions(new HashSet<>(Arrays.asList(rights)));
		return role;
	}

	static UserAccount user() {
		UserAccount u = new UserAccount();
		u.setId(9L);
		u.setUsername("caissier_test");
		u.setFullName("Caissier Test");
		u.setEmail("c@test.tn");
		u.setActive(true);
		u.setAppRole(role("POS_USER", "Caissier"));
		return u;
	}

	@Test
	@DisplayName("A user: created (role, a badge said, never its code); edited: only the fields changed, the role apart"
			+ " (Alert), active apart, the password set (Alert, never its value); a save with no change writes nothing")
	void users() {
		UserAccount u = user();
		u.setBadgeCode("CAISSIER-TEST-002");
		u.setPassword("Secret!2026");
		events.userCreated(u);
		EventLogEntry created = log.only("USER_CREATED");
		assertEquals(EventImportance.NOTABLE, created.getImportance());
		assertEquals("Utilisateur créé : Caissier Test (caissier_test), rôle Caissier, avec un badge.", created.getDetails());

		Map<String, String> before = AdminEvents.describe(u);
		events.userUpdated(before, u, false);
		assertEquals(1, log.rows.size(), "no change: nothing");

		u.setEmail("caissier@test.tn");
		u.setBadgeCode("CAISSIER-TEST-003");
		u.setAppRole(role("RESPONSIBLE", "Responsable"));
		u.setActive(false);
		events.userUpdated(before, u, true);
		assertEquals(Arrays.asList("USER_CREATED", "USER_UPDATED", "USER_ROLE_CHANGED", "USER_DISABLED", "PASSWORD_CHANGED"),
				log.codes());
		String updated = log.only("USER_UPDATED").getDetails();
		assertTrue(updated.startsWith("Utilisateur Caissier Test (caissier_test) modifié : E-mail : c@test.tn → caissier@test.tn ;"
				+ " Badge : badge "), updated);
		EventLogEntry role = log.only("USER_ROLE_CHANGED");
		assertEquals(EventImportance.ALERT, role.getImportance());
		assertEquals("Caissier", role.getOldValue());
		assertEquals("Responsable", role.getNewValue());
		assertEquals(EventImportance.ALERT, log.only("PASSWORD_CHANGED").getImportance());
		for (EventLogEntry e : log.rows) {
			String all = e.getDetails() + e.getOldValue() + e.getNewValue();
			assertFalse(all.contains("CAISSIER-TEST"), "no badge code: " + all);
			assertFalse(all.contains("Secret!2026"), "no password: " + all);
		}
		events.userDeleted(u);
		assertEquals(EventImportance.ALERT, log.only("USER_DELETED").getImportance());
	}

	@Test
	@DisplayName("Badges: generated (replacing one said), revoked with its reason, reactivated; the owner kept, no code")
	void badges() {
		UserAccount u = user();
		u.setBadgeCode("CAISSIER-TEST-002");
		events.badgeGenerated(u, true);
		events.badgeRevoked(u, "Badge perdu");
		events.badgeReactivated(u, "Badge perdu");
		assertEquals(Arrays.asList("BADGE_GENERATED", "BADGE_REVOKED", "BADGE_REACTIVATED"), log.codes());
		assertEquals("Badge généré pour Caissier Test (caissier_test) (l'ancien badge ne passe plus).", log.rows.get(0).getDetails());
		assertEquals("Badge de Caissier Test (caissier_test) révoqué : Badge perdu.", log.rows.get(1).getDetails());
		assertEquals(Long.valueOf(9), log.rows.get(2).getBadgeOwnerId());
		log.rows.forEach(e -> assertFalse(e.getDetails().contains("CAISSIER-TEST"), e.getDetails()));
	}

	@Test
	@DisplayName("Roles: created; rights added and taken off (Alert), each named; renamed only: nothing; deleted (Alert)")
	void roles() {
		AppRole r = role("CHEF", "Chef de caisse", "read:home", "till:make-return");
		events.roleCreated(r);
		events.roleRightsChanged(r, r.getPermissions(), new HashSet<>(r.getPermissions()));
		assertEquals(1, log.rows.size(), "the same rights: nothing");
		events.roleRightsChanged(r, r.getPermissions(), new HashSet<>(Arrays.asList("read:home", "read:day-journal")));
		EventLogEntry changed = log.only("ROLE_RIGHTS_CHANGED");
		assertEquals(EventImportance.ALERT, changed.getImportance());
		assertEquals("Droits du rôle Chef de caisse changés : ajoutés read:day-journal ; retirés till:make-return.",
				changed.getDetails());
		events.roleRenamed(r, "Chef de caisse", "Chef de caisse");
		assertEquals(2, log.rows.size(), "the same name: nothing");
		events.roleRenamed(r, "Chef de caisse", "Responsable de caisse");
		EventLogEntry renamed = log.only("ROLE_RENAMED");
		assertEquals(EventImportance.NOTABLE, renamed.getImportance());
		assertEquals("Rôle renommé : Chef de caisse → Responsable de caisse.", renamed.getDetails());
		events.roleDeleted(r);
		assertEquals(EventImportance.ALERT, log.only("ROLE_DELETED").getImportance());
	}

	@Test
	@DisplayName("General setup: one event per field changed, before → after; a secret masked; the event log's own"
			+ " settings left to their own event")
	void settings() {
		events.changed("MAX_DAYS_FOR_RETURN", "10", "30");
		events.changed("COMM_SMTP_PASSWORD", "old-secret", "new-secret");
		events.changed("EVENTLOG_STORE_FROM", "STEP", "NOTABLE");
		assertEquals(Arrays.asList("SETTING_CHANGED", "SETTING_CHANGED"), log.codes());
		assertEquals("Paramètre MAX_DAYS_FOR_RETURN changé : 10 → 30.", log.rows.get(0).getDetails());
		EventLogEntry secret = log.rows.get(1);
		assertEquals("******", secret.getOldValue());
		assertEquals("******", secret.getNewValue());
		assertFalse(secret.getDetails().contains("secret-"), secret.getDetails());
		assertTrue(AdminEvents.secret("ERP_API_KEY"));
		assertTrue(AdminEvents.secret("HEADOFFICE_TOKEN"));
		assertFalse(AdminEvents.secret("TAX_STAMP_ERP_ITEM_CODE"));
	}

	@Test
	@DisplayName("Prices: created with its values, changed (the fields changed), unchanged (nothing), deleted; a local"
			+ " item's price, an own price set and given back; money to the millime")
	void prices() {
		SalesPrice p = new SalesPrice();
		p.setId(5L);
		p.setItemNo("CAP-SHA-DOC-0003");
		p.setUnitPrice(21.9);
		p.setStartingDate(LocalDate.of(2026, 11, 1));
		events.priceSaved(null, p);
		assertEquals("Prix de vente créé : Article CAP-SHA-DOC-0003 ; Prix 21,900 ; Du 01/11/2026.", log.rows.get(0).getDetails());
		Map<String, String> before = AdminEvents.describe(p);
		events.priceSaved(before, p);
		assertEquals(1, log.rows.size(), "unchanged: nothing");
		p.setUnitPrice(19.5);
		events.priceSaved(before, p);
		assertEquals("Prix de vente modifié : Prix : 21,900 → 19,500.", log.rows.get(1).getDetails());
		events.priceDeleted(p);
		assertEquals("PRICE_DELETED", log.rows.get(2).getCode());

		events.itemPriceChanged(1L, "A - Article", 10.0, 10.0);
		assertEquals(3, log.rows.size(), "the same price: nothing");
		events.itemPriceChanged(1L, "A - Article", 10.0, 12.5);
		assertEquals("Prix de A - Article changé : 10,000 → 12,500.", log.only("ITEM_PRICE_CHANGED").getDetails());
		events.ownPrice(true, 2L, "B - Article", 8.9, 8.5);
		events.ownPrice(false, 2L, "B - Article", 8.5, 8.9);
		assertEquals("Prix propre fixé pour B - Article : 8,900 → 8,500.", log.only("OWN_PRICE_SET").getDetails());
		assertEquals("Prix du siège rendu à B - Article : 8,500 → 8,900.", log.only("OWN_PRICE_GIVEN_BACK").getDetails());
	}

	@Test
	@DisplayName("Promotions, payment methods, loyalty programs: created, changed (fields), unchanged (nothing), deleted")
	void savedAndDeleted() {
		Promotion promo = new Promotion();
		promo.setId(3L);
		promo.setCode("TEST10");
		promo.setName("Test 10 %");
		promo.setDiscountPercentage(10.0);
		events.promotionSaved(null, promo);
		Map<String, String> before = AdminEvents.describe(promo);
		events.promotionSaved(before, promo);
		promo.setDiscountPercentage(15.0);
		events.promotionSaved(before, promo);
		events.promotionDeleted(promo);
		assertEquals(Arrays.asList("PROMOTION_SAVED", "PROMOTION_SAVED", "PROMOTION_DELETED"), log.codes());
		assertEquals("Promotion TEST10 modifié : Remise % : 10 → 15.", log.rows.get(1).getDetails());

		PaymentMethod pm = new PaymentMethod();
		pm.setId(4L);
		pm.setCode("CHQ");
		pm.setName("Chèque");
		pm.setActive(true);
		Map<String, String> pmBefore = AdminEvents.describe(pm);
		pm.setActive(false);
		events.paymentMethodSaved(pmBefore, pm);
		EventLogEntry method = log.only("PAYMENT_METHOD_SAVED");
		assertEquals("PAYMENT_METHOD", method.getSubjectType());
		assertEquals("Mode de paiement CHQ modifié : Actif : oui → non.", method.getDetails());

		LoyaltyProgram program = new LoyaltyProgram();
		program.setId(6L);
		program.setProgramCode("FID");
		program.setName("Fidélité");
		Map<String, String> pBefore = AdminEvents.describe(program);
		program.setPointsPerDinar(2.0);
		events.loyaltyProgramSaved(pBefore, program);
		assertEquals("Programme de fidélité FID modifié : Points par dinar : 1 → 2.", log.only("LOYALTY_PROGRAM_SAVED").getDetails());
	}

	@Test
	@DisplayName("Stock (Alert, no change: nothing), cost, inventories, purchase, reception, loyalty points (Alert); one"
			+ " import event with its counts")
	void stockAndRuns() {
		events.stockAdjusted(1L, "A - Article", new BigDecimal("12"), BigDecimal.ZERO, "CORRECTION", null);
		assertTrue(log.rows.isEmpty());
		events.stockAdjusted(1L, "A - Article", new BigDecimal("12"), new BigDecimal("-2.5"), "DAMAGE", null);
		EventLogEntry stock = log.only("STOCK_ADJUSTED");
		assertEquals(EventImportance.ALERT, stock.getImportance());
		assertEquals("Stock de A - Article ajusté : 12 → 9,5 (-2,5, DAMAGE).", stock.getDetails());
		events.costAdjusted(1L, "A - Article", 4.2, 4.2, "x");
		events.costAdjusted(1L, "A - Article", 4.2, 4.5, "facture fournisseur");
		assertEquals("CMP de A - Article ajusté : 4,200 → 4,500 (facture fournisseur).", log.only("COST_ADJUSTED").getDetails());
		events.inventoryCreated(1L, "INV-0001", 120);
		events.inventoryValidated(1L, "INV-0001", 120, 7, new BigDecimal("3"), new BigDecimal("5.5"));
		events.inventoryDeleted(2L, "INV-0002");
		assertEquals(EventImportance.NOTABLE, log.only("INVENTORY_DELETED").getImportance(), "a draft only");
		assertEquals("Inventaire INV-0001 validé : 120 lignes, 7 avec un écart (+3 / -5,5 unités).",
				log.only("INVENTORY_VALIDATED").getDetails());
		events.purchaseProcessed(1L, "ACH-000012", "Fournisseur A", 3, 1234.5);
		events.receptionDone(1L, "BL-0042", 4, new BigDecimal("40"), new BigDecimal("38"), 1);
		events.importRun("ITEMS", 500, 496, 4);
		events.loyaltyPointsAdjusted(1L, "FID0001 - Amine K.", 120, 120, "x");
		events.loyaltyPointsAdjusted(1L, "FID0001 - Amine K.", 120, 170, "geste commercial");
		assertEquals("Import ITEMS : 500 lignes lues, 496 enregistrées, 4 en erreur.", log.only("IMPORT_RUN").getDetails());
		assertEquals(EventImportance.ALERT, log.only("LOYALTY_POINTS_ADJUSTED").getImportance());
		assertEquals("Points de FID0001 - Amine K. ajustés à la main : 120 → 170 (geste commercial).",
				log.only("LOYALTY_POINTS_ADJUSTED").getDetails());
		assertEquals(Double.valueOf(1234.5), log.only("PURCHASE_PROCESSED").getAmount());
	}

	@Test
	@DisplayName("Off: nothing at all; a failure inside never reaches the gesture")
	void offAndFailures() {
		log.on = false;
		events.userCreated(user());
		events.changed("MAX_DAYS_FOR_RETURN", "10", "30");
		assertTrue(log.rows.isEmpty());
		log.on = true;
		assertDoesNotThrow(() -> events.userCreated(null));
		assertDoesNotThrow(() -> events.roleRightsChanged(null, Collections.singleton("a"), Collections.emptySet()));
		assertTrue(log.rows.isEmpty());
	}
}
