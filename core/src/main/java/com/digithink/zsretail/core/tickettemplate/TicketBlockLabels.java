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
		l.put("RETURN_VOUCHER", "Bon de retour");
		l.put("CASH_REFUND", "Remboursement en espèces");
		l.put("HEADER", "En-tête");
		l.put("HEADER.LOGO", "Logo");
		l.put("HEADER.STORE_NAME", "Nom du magasin");
		l.put("HEADER.ADDRESS", "Adresse");
		l.put("HEADER.TITLE", "Titre du document");
		l.put("HEADER.BADGE", "Numéro en bandeau");
		l.put("HEADER.DUPLICATE", "DUPLICATA");
		l.put("INFO", "Informations du ticket");
		l.put("INFO.NUMBER", "Numéro de vente");
		l.put("INFO.DATE", "Date et heure");
		l.put("INFO.CASHIER", "Caissier");
		l.put("INFO.CUSTOMER_CODE", "Code client");
		l.put("INFO.ORIGINAL_TICKET", "Ticket original");
		l.put("INFO.VALID_UNTIL", "Valide jusqu'au");
		l.put("TOP_TEXT", "Texte d'en-tête");
		l.put("TOP_TEXT.TEXT", "Lignes");
		l.put("ITEMS", "Articles");
		l.put("ITEMS.LINES", "Lignes");
		l.put("ITEMS.ITEM_CODE", "Code article");
		l.put("ITEMS.ITEM_BARCODE", "Code-barres de l'article");
		l.put("ITEMS.UNIT_PRICE", "Prix unitaire");
		l.put("ITEMS.DISCOUNT", "Remise de la ligne");
		l.put("ITEMS.PROMOTION_NAME", "Nom de la promotion");
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
		l.put("FOOTER.WEBSITE", "Site web");
		l.put("FOOTER.EMAIL", "E-mail");
		l.put("FOOTER.PHONE", "Téléphone");
		l.put("FOOTER.FAX", "Fax");
		l.put("FOOTER.MF", "Matricule fiscal");
		l.put("FOOTER.FOOTER_NOTE", "Texte de pied");
		l.put("FOOTER.MESSAGE", "Message du document");
		l.put("FOOTER.VALIDITY", "Rappel de la validité");
		l.put("FOOTER.SOFTWARE_LABEL", "Mention ZS Retail");
		l.put("TOMBOLA", "Ticket de tombola");
		l.put("SMALL", "Petite");
		l.put("NORMAL", "Normale");
		l.put("LARGE", "Grande");
		// step 2b: the layout and the element options
		l.put("spacing.AIRY", "Aérée");
		l.put("spacing.NORMAL", "Normale");
		l.put("spacing.COMPACT", "Compacte");
		l.put("separator.LINE", "Trait plein");
		l.put("separator.DASHES", "Tirets");
		l.put("separator.NONE", "Aucun");
		l.put("option.width", "largeur");
		l.put("option.maxHeight", "hauteur max.");
		l.put("option.align", "position");
		l.put("option.size", "taille");
		l.put("option.name", "nom");
		l.put("option.text", "texte");
		l.put("option.passenger", "client passager");
		l.put("align.LEFT", "à gauche");
		l.put("align.CENTER", "au centre");
		l.put("align.RIGHT", "à droite");
		l.put("size.SMALL", "petite");
		l.put("size.MEDIUM", "moyenne");
		l.put("size.LARGE", "grande");
		l.put("name.WRAP", "sur plusieurs lignes");
		l.put("name.CUT", "coupé sur une ligne");
		l.put("passenger.SHOW", "code affiché");
		l.put("passenger.HIDE", "code masqué");
	}

	private TicketBlockLabels() {
	}

	/** The French name of a code (a document, "SECTION" or "SECTION.ELEMENT", a size); the code itself when unknown. */
	public static String fr(String code) {
		String label = LABELS.get(code);
		return label != null ? label : code;
	}

	/** An option's value in words: "50 %", "20 mm", "à gauche", "grande", "coupé sur une ligne". */
	public static String value(String key, Object value) {
		if ("width".equals(key)) {
			return value + " %";
		}
		if ("maxHeight".equals(key)) {
			return value + " mm";
		}
		if ("text".equals(key)) {
			String text = String.valueOf(value).replace("\n", " / ");
			return "« " + (text.length() > 120 ? text.substring(0, 117) + "…" : text) + " »";
		}
		return fr(key + "." + value);
	}

	static boolean known(String code) {
		return LABELS.containsKey(code);
	}
}
