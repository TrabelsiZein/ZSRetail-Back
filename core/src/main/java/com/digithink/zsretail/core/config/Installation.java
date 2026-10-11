package com.digithink.zsretail.core.config;

import java.util.function.Predicate;

import org.springframework.core.env.PropertyResolver;

import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.model.enumeration.NodeType;

/**
 * Code cleanup step 4: every yes/no question about the installation that decides whether a part of the application
 * exists, in one place. Each one says in plain words when it is true; its answer comes from NodeOwnership, as the
 * startup resolves the configuration. Used through {@link ConditionalOnInstallation}. The table of the questions and
 * how many classes use each: docs/deployment-modes.md, "Which part exists where".
 */
public enum Installation {

	// ─── Head office ────────────────────────────────────────────

	/** This installation is a head office (node.type=HEAD_OFFICE). */
	HEAD_OFFICE("This installation is a head office.",
			env -> NodeOwnership.nodeTypeOf(env) == NodeType.HEAD_OFFICE),

	/** A head office whose ERP owns the catalogue, the customers and the supply. */
	HEAD_OFFICE_WITH_ERP("A head office whose ERP owns the catalogue, the customers and the supply.",
			NodeOwnership::isHeadOfficeErpSet),

	/** A head office without an ERP, or whose catalogue only comes from the ERP: it owns what it sends its stores. */
	HEAD_OFFICE_WITHOUT_ERP("A head office without an ERP, or whose catalogue only comes from the ERP.",
			NodeOwnership::isHeadOfficeWithoutErpSet),

	/** A head office whose catalogue only comes from the ERP (customers and supply kept at the head office). */
	HEAD_OFFICE_ERP_CATALOGUE_ONLY("A head office whose catalogue only comes from the ERP; it keeps its customers and"
			+ " its supply.", NodeOwnership::isErpCatalogueOnlySet),

	/** A head office that makes the BLs and supply invoices of its stores (headoffice.supply.source not ERP). */
	HEAD_OFFICE_OWN_SUPPLY("A head office without an ERP, or with the catalogue only from the ERP, that makes the BLs"
			+ " and supply invoices of its stores itself.",
			env -> NodeOwnership.isHeadOfficeWithoutErpSet(env) && !NodeOwnership.isSupplyFromErpSourceSet(env)),

	/** A head office whose stores' supply documents are the ERP's invoices (headoffice.supply.source=ERP). */
	HEAD_OFFICE_ERP_SUPPLY("A head office with the catalogue only from the ERP whose stores' supply documents are the"
			+ " ERP's invoices.",
			env -> NodeOwnership.isErpCatalogueOnlySet(env) && NodeOwnership.isSupplyFromErpSourceSet(env)),

	/** A head office that keeps no stock and makes no purchases (headoffice.stock.enabled=false). */
	HEAD_OFFICE_WITHOUT_STOCK("A head office that keeps no stock and makes no purchases.",
			NodeOwnership::isHeadOfficeWithoutStockSet),

	// ─── Store linked to a head office ──────────────────────────

	/** A store that calls a head office (headoffice.url set). */
	LINKED_TO_HEAD_OFFICE("A store that calls a head office: headoffice.url is set.",
			NodeOwnership::isHeadOfficeLinkSet),

	/** A linked store that copies its sales to the head office (sales.upstream includes HEAD_OFFICE). */
	SALES_TO_HEAD_OFFICE("A linked store that copies its sales to the head office.",
			NodeOwnership::isHeadOfficeSalesPushSet),

	/** A linked store with at least one domain owned by the head office: it pulls the copies down. */
	COPIES_FROM_HEAD_OFFICE("A linked store with at least one domain owned by the head office: it pulls the copies down.",
			NodeOwnership::isHeadOfficePullSet),

	/** A linked store whose catalogue is the head office's (ownership.catalogue=HEAD_OFFICE). */
	CATALOGUE_FROM_HEAD_OFFICE("A linked store whose catalogue and selling prices are the head office's.",
			NodeOwnership::isCatalogueFromHeadOffice),

	/** A linked store whose promotions are the head office's (ownership.promotions=HEAD_OFFICE). */
	PROMOTIONS_FROM_HEAD_OFFICE("A linked store whose promotions are the head office's.",
			env -> NodeOwnership.isOwnedByHeadOffice(env, DataDomain.PROMOTIONS)),

	/** A linked store whose loyalty program and members are the head office's (ownership.loyalty=HEAD_OFFICE). */
	LOYALTY_FROM_HEAD_OFFICE("A linked store whose loyalty program and members are the head office's.",
			env -> NodeOwnership.isOwnedByHeadOffice(env, DataDomain.LOYALTY)),

	/** A linked store whose goods come from the head office by BL (ownership.supply=HEAD_OFFICE written in its file). */
	SUPPLY_FROM_HEAD_OFFICE("A linked store whose goods come from the head office by BL (ownership.supply=HEAD_OFFICE"
			+ " written in its file).", NodeOwnership::isSupplyFromHeadOffice);

	private final String meaning;
	private final Predicate<PropertyResolver> answer;

	Installation(String meaning, Predicate<PropertyResolver> answer) {
		this.meaning = meaning;
		this.answer = answer;
	}

	/** When the question is true, in plain words. */
	public String meaning() {
		return meaning;
	}

	/** The answer for this configuration, as the startup resolves it (NodeOwnership). */
	public boolean isTrue(PropertyResolver env) {
		return answer.test(env);
	}
}
