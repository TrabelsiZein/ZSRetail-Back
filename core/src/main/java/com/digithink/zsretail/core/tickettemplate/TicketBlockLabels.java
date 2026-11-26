package com.digithink.zsretail.core.tickettemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 3.5.0, ticket templates step 2: the French names of the documents and blocks, as the page "Modèles de tickets" names
 * them (ZSRetail-Front locales, admin.ticketTemplates), for the event log's sentences (AdminEvents).
 */
public final class TicketBlockLabels {

	private static final Map<String, String> LABELS = new HashMap<>();

	static {
		Map<String, String> l = LABELS;
		l.put("SALE", "Ticket de vente");
		l.put("HEADER", "En-tête");
		l.put("HEADER.LOGO", "Logo");
		l.put("HEADER.STORE_NAME", "Nom du magasin");
		l.put("HEADER.ADDRESS", "Adresse");
		l.put("HEADER.TITLE", "Titre « REÇU DE VENTE »");
		l.put("HEADER.DUPLICATE", "DUPLICATA");
		l.put("INFO", "Informations du ticket");
		l.put("INFO.NUMBER", "Numéro de vente");
		l.put("INFO.DATE", "Date et heure");
		l.put("INFO.CASHIER", "Caissier");
		l.put("INFO.CUSTOMER_CODE", "Code client");
		l.put("ITEMS", "Articles");
		l.put("ITEMS.LINES", "Lignes");
		l.put("ITEMS.UNIT_PRICE", "Prix unitaire");
		l.put("ITEMS.DISCOUNT", "Remise de la ligne");
		l.put("ITEMS.VAT", "TVA de la ligne");
		l.put("TOTALS", "Totaux");
		l.put("TOTALS.TICKET_DISCOUNT", "Remise totale");
		l.put("TOTALS.TOTAL_VAT", "Total TVA");
		l.put("TOTALS.TOTAL", "TOTAL");
		l.put("VAT_BY_RATE", "TVA par taux");
		l.put("PAYMENTS", "Paiements");
		l.put("LOYALTY", "Fidélité");
		l.put("LOYALTY.CARD", "Carte et titulaire");
		l.put("LOYALTY.EARNED", "Points gagnés");
		l.put("LOYALTY.USED", "Points utilisés");
		l.put("LOYALTY.BALANCE", "Solde de points");
		l.put("BARCODE", "Code-barres");
		l.put("FOOTER", "Pied du ticket");
		l.put("FOOTER.THANK_YOU", "Remerciement");
		l.put("FOOTER.CONTACT", "Contact");
		l.put("FOOTER.MF", "Matricule fiscal");
		l.put("FOOTER.FOOTER_NOTE", "Texte de pied");
		l.put("FOOTER.SOFTWARE_LABEL", "Mention ZS Retail");
		l.put("TOMBOLA", "Ticket de tombola");
		l.put("SMALL", "Petite");
		l.put("NORMAL", "Normale");
		l.put("LARGE", "Grande");
	}

	private TicketBlockLabels() {
	}

	/** The French name of a code (a document, "SECTION" or "SECTION.ELEMENT", a size); the code itself when unknown. */
	public static String fr(String code) {
		String label = LABELS.get(code);
		return label != null ? label : code;
	}

	static boolean known(String code) {
		return LABELS.containsKey(code);
	}
}
