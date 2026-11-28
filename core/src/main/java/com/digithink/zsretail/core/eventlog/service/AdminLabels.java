package com.digithink.zsretail.core.eventlog.service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 3.4.0, event log step 9: the French labels of the screen, so that an event names a setting and a right as the
 * administrator sees them (AdminEvents; the technical key stays in the sentence and in the event's columns). WRITTEN BY
 * ZSRetail-Front tools/admin-labels-check.mjs --write from the General setup page and the Roles page; the same tool,
 * run with the front's checks, fails when this file and the screen differ. A key not here: the key itself.
 */
final class AdminLabels {

	/** A setting's code → "its tab › its field". */
	static final Map<String, String> SETTINGS;

	/** A right's key → the Roles page's name (the till rights: AccessEvents.actionLabel). */
	static final Map<String, String> RIGHTS;

	static {
		Map<String, String> s = new HashMap<>();
		s.put("ALLOW_DECIMAL_QUANTITY", "Stock › Autoriser les quantités décimales");
		s.put("ALLOW_NEGATIVE_STOCK", "Stock › Autoriser le stock négatif");
		s.put("BLOCK_RETURN_FOR_PROMOTION", "Retours produits › Bloquer les retours pour les articles en promotion");
		s.put("CASHIER_CLOSING_TICKET", "Caisse et clôture › Ticket de fermeture caissier");
		s.put("CASH_DRAWER_OWNER", "Caisse et clôture › Le tiroir-caisse appartient");
		s.put("DEFAULT_LOCATION", "Comportement POS › Code emplacement par défaut");
		s.put("ENABLE_PAYMENT_METHOD_CHANGE", "Caisse et clôture › Autoriser le changement du mode de paiement (Historique des tickets)");
		s.put("ENABLE_SIMPLE_RETURN", "Retours produits › Activer le retour simple (remboursement espèce)");
		s.put("ENABLE_TAX_STAMP", "Timbre fiscal › Activer le timbre fiscal");
		s.put("ERP_SYNC_LAST_CUSTOMER", "Synchronisation ERP › Clients");
		s.put("ERP_SYNC_LAST_DELETION_LOG", "Synchronisation ERP › Journal de suppressions");
		s.put("ERP_SYNC_LAST_ITEM", "Synchronisation ERP › Articles");
		s.put("ERP_SYNC_LAST_ITEM_BARCODE", "Synchronisation ERP › Codes-barres articles");
		s.put("ERP_SYNC_LAST_ITEM_FAMILY", "Synchronisation ERP › Familles articles");
		s.put("ERP_SYNC_LAST_ITEM_SUBFAMILY", "Synchronisation ERP › Sous-familles articles");
		s.put("ERP_SYNC_LAST_LOCATION", "Synchronisation ERP › Emplacements");
		s.put("ERP_SYNC_LAST_SALES_DISCOUNT", "Synchronisation ERP › Remises de vente");
		s.put("ERP_SYNC_LAST_SALES_PRICE", "Synchronisation ERP › Prix de vente");
		s.put("ERP_SYNC_TRACKING_LEVEL", "Synchronisation ERP › Niveau de traçabilité ERP");
		s.put("EVENTLOG_AUTO_CLEANUP", "Journal des événements › Nettoyage automatique");
		s.put("EVENTLOG_KEEP_ALERT_MONTHS", "Journal des événements › Alertes");
		s.put("EVENTLOG_KEEP_NOTABLE_MONTHS", "Journal des événements › Notables");
		s.put("EVENTLOG_KEEP_STEP_MONTHS", "Journal des événements › Étapes");
		s.put("EVENTLOG_STORE_FROM", "Journal des événements › Enregistrer à partir de");
		s.put("LOYALTY_ENABLED", "Programme de fidélité › Activer le programme de fidélité");
		s.put("MAX_DAYS_FOR_RETURN", "Retours produits › Délai max de retour (jours)");
		s.put("PASSENGER_CUSTOMER", "Comportement POS › Code client passager");
		s.put("POS_CATALOGUE_VISIBLE", "Comportement POS › Catalogue visible par défaut dans l'écran de caisse");
		s.put("POS_HIDE_EMPTY_FAMILIES", "Comportement POS › Masquer les familles vides dans l'écran de caisse");
		s.put("POS_HIDE_EMPTY_SUB_FAMILIES", "Comportement POS › Masquer les sous-familles vides dans l'écran de caisse");
		s.put("POS_SHOW_IMAGES", "Comportement POS › Afficher les images dans le POS");
		s.put("POS_SHOW_STOCK", "Comportement POS › Afficher le stock dans le POS");
		s.put("TAX_STAMP_ERP_ITEM_CODE", "Timbre fiscal › Code article ERP du timbre");
		s.put("TAX_STAMP_VALUE_MILLIMES", "Timbre fiscal › Montant (millimes)");
		s.put("TILL_GRANT_MAX_MINUTES", "Caisse et clôture › Durée d'une autorisation par badge (minutes)");
		s.put("TOMBOLA_ENABLED", "Programme de fidélité › Activer l'impression du ticket tombola");
		s.put("VERIFY_PREVIOUS_SESSION_BEFORE_OPENING", "Caisse et clôture › Exiger la clôture de la session précédente avant une nouvelle ouverture");
		SETTINGS = Collections.unmodifiableMap(s);
		Map<String, String> r = new HashMap<>();
		r.put("delete:admin-customers", "Clients (supprimer)");
		r.put("delete:admin-event-log", "Nettoyer le journal des événements");
		r.put("delete:admin-roles", "Rôles (supprimer)");
		r.put("delete:admin-users", "Utilisateurs (supprimer)");
		r.put("read:admin-badge-scan-history", "Historique badges");
		r.put("read:admin-communication", "Communication (consulter)");
		r.put("read:admin-company-information", "Infos société (lecture)");
		r.put("read:admin-conventions", "Conventions (consulter)");
		r.put("read:admin-customers", "Clients (lecture)");
		r.put("read:admin-data-import", "Import données (lecture)");
		r.put("read:admin-erp-communications", "Communications ERP");
		r.put("read:admin-erp-jobs", "Tâches ERP");
		r.put("read:admin-event-log", "Journal des événements");
		r.put("read:admin-general-setup", "Configuration générale");
		r.put("read:admin-headoffice-store-orders", "Commandes des magasins (consulter)");
		r.put("read:admin-holink-deliveries", "Réceptions du siège");
		r.put("read:admin-holink-status", "Lien siège");
		r.put("read:admin-inventory-counts", "Inventaires (consulter)");
		r.put("read:admin-invoices", "Factures");
		r.put("read:admin-item-barcodes", "Codes-barres");
		r.put("read:admin-item-families", "Familles d'articles");
		r.put("read:admin-item-management", "Articles");
		r.put("read:admin-item-subfamilies", "Sous-familles");
		r.put("read:admin-license", "Licence");
		r.put("read:admin-locations", "Dépôts / localisations");
		r.put("read:admin-loyalty-member-functions", "Fonctions membres (lecture)");
		r.put("read:admin-loyalty-members", "Membres fidélité (lecture)");
		r.put("read:admin-loyalty-programs", "Programmes fidélité (lecture)");
		r.put("read:admin-loyalty-transactions", "Transactions fidélité");
		r.put("read:admin-margins", "Voir les coûts des lignes de ticket (marges)");
		r.put("read:admin-payment-methods", "Modes de paiement (lecture)");
		r.put("read:admin-print-product-labels", "Impression étiquettes");
		r.put("read:admin-promotions", "Promotions");
		r.put("read:admin-purchase-invoices", "Factures d'achat");
		r.put("read:admin-purchases", "Achats");
		r.put("read:admin-report-loyalty", "Rapport fidélité");
		r.put("read:admin-report-promotions", "Rapport promotions");
		r.put("read:admin-report-purchases", "Rapport achats");
		r.put("read:admin-report-sales", "Rapport ventes");
		r.put("read:admin-report-sessions", "Rapport sessions");
		r.put("read:admin-report-stock", "Rapport stock");
		r.put("read:admin-report-stock-movements", "Rapport mouvements stock");
		r.put("read:admin-returns", "Retours");
		r.put("read:admin-rfid-assignment", "Affectation des étiquettes RFID");
		r.put("read:admin-rfid-pos-machines", "Postes de caisse");
		r.put("read:admin-rfid-readers", "Lecteurs RFID");
		r.put("read:admin-rfid-tags", "Étiquettes RFID");
		r.put("read:admin-roles", "Rôles (lecture)");
		r.put("read:admin-sales", "Ventes");
		r.put("read:admin-sales-discounts", "Remises");
		r.put("read:admin-sales-prices", "Prix de vente");
		r.put("read:admin-scanned-tickets", "Tickets resto / cadeau encaissés");
		r.put("read:admin-sessions-history", "Sessions et écarts de caisse");
		r.put("read:admin-statistics", "Statistiques");
		r.put("read:admin-store-orders", "Commandes centrale (consulter)");
		r.put("read:admin-ticket-templates", "Modèles de tickets (consulter)");
		r.put("read:admin-users", "Utilisateurs (lecture)");
		r.put("read:admin-vendors", "Fournisseurs (lecture)");
		r.put("read:admin-warranty", "Garanties (lecture)");
		r.put("read:cashier-interface", "Accès à l'interface caissier");
		r.put("read:change-payment-method", "Changer mode de paiement");
		r.put("read:day-journal", "Voir le Journal");
		r.put("read:home", "Tableau de bord");
		r.put("read:prepare-invoice", "Préparer facture");
		r.put("read:purchase-history", "Historique achats");
		r.put("read:purchase-new", "Nouvel achat (lecture)");
		r.put("read:tickets-history", "Historique tickets");
		r.put("read:verify-session", "Clôturer la session");
		r.put("read:view-session-amounts", "Voir montants session");
		r.put("write:admin-communication", "Communication (modifier, renvoyer)");
		r.put("write:admin-communication-campaigns", "Communication (lancer, annuler une campagne)");
		r.put("write:admin-company-information", "Infos société (modifier)");
		r.put("write:admin-conventions", "Conventions (créer, modifier, gérer les cartes)");
		r.put("write:admin-customers", "Clients (modifier)");
		r.put("write:admin-data-import", "Import données (exécuter)");
		r.put("write:admin-headoffice-store-orders", "Commandes des magasins (poster, rejeter, relancer)");
		r.put("write:admin-inventory-counts", "Inventaires (importer, valider, supprimer)");
		r.put("write:admin-item-cost", "Ajuster le CMP (coût moyen)");
		r.put("write:admin-loyalty-member-functions", "Fonctions membres (modifier)");
		r.put("write:admin-loyalty-members", "Membres fidélité (modifier)");
		r.put("write:admin-loyalty-programs", "Programmes fidélité (modifier)");
		r.put("write:admin-payment-methods", "Modes de paiement (modifier)");
		r.put("write:admin-roles", "Rôles (modifier)");
		r.put("write:admin-store-orders", "Commandes centrale (commander, envoyer)");
		r.put("write:admin-ticket-templates", "Modèles de tickets (modifier)");
		r.put("write:admin-users", "Utilisateurs (modifier)");
		r.put("write:admin-vendors", "Fournisseurs (modifier)");
		r.put("write:admin-warranty", "Garanties (modifier)");
		r.put("write:purchase-new", "Nouvel achat (créer)");
		RIGHTS = Collections.unmodifiableMap(r);
	}

	private AdminLabels() {
	}
}
