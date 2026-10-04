package com.digithink.zsretail.config;

import java.util.Set;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.DataOwner;
import com.digithink.zsretail.model.enumeration.NodeType;
import com.digithink.zsretail.model.enumeration.SalesUpstream;

/**
 * Exposes the installation type, the owner of each data domain and where the sales go (head office design 2.1, 2.2),
 * resolved once at startup from application.standalone, node.type, ownership.* and sales.upstream
 * ({@link NodeOwnership#resolve(org.springframework.core.env.PropertyResolver)}, the one place that reads
 * application.standalone).
 * All mode-dependent behaviour should use this service. Step 9: every check asks one of its questions; the franchise
 * profiles were removed (task 9.4a) and a leftover franchise.admin or franchise.customer=true stops the startup.
 */
@Service
public class ApplicationModeService {

	@Autowired
	private Environment environment;

	/** Head office model (design 2.1, 2.2), resolved at startup. Not used by the application yet. */
	private NodeOwnership ownership;

	/** headoffice.url is set (task 1.4), resolved at startup like the head office link beans. */
	private boolean headOfficeLinked;

	/** Step 7A: the supply beans exist (NodeOwnership.isSupplyFromHeadOffice), resolved at startup. */
	private boolean supplyFromHeadOffice;

	/** Fails the startup when node.type, ownership.* or sales.upstream holds an invalid value. */
	@PostConstruct
	void initOwnership() {
		ownership = NodeOwnership.resolve(environment);
		headOfficeLinked = NodeOwnership.isHeadOfficeLinkSet(environment);
		// Same rule as NodeOwnership.isSupplyFromHeadOffice (accepted only without an ERP, with the catalogue too)
		supplyFromHeadOffice = headOfficeLinked && environment.containsProperty(DataDomain.SUPPLY.getPropertyKey())
				&& ownership.ownerOf(DataDomain.SUPPLY) == DataOwner.HEAD_OFFICE;
	}

	/** True on a store that calls a head office (headoffice.url set): the "Head office link" page exists. */
	public boolean isHeadOfficeLinked() {
		return headOfficeLinked;
	}

	/** Installation type: node.type, STORE when absent. */
	public NodeType getNodeType() {
		return ownership.getNodeType();
	}

	/** True on a head office (node.type=HEAD_OFFICE): no cashier session, no cashier login. See docs/modules/head-office.md. */
	public boolean isHeadOffice() {
		return ownership.getNodeType() == NodeType.HEAD_OFFICE;
	}

	/** Owner of a data domain: ownership.&lt;domain&gt;, derived from the mode flags when absent. */
	public DataOwner ownerOf(DataDomain domain) {
		return ownership.ownerOf(domain);
	}

	/**
	 * True on a store whose promotions are owned by its head office (ownership.promotions=HEAD_OFFICE, which needs
	 * headoffice.url): promotions are only consulted there, the pull job writes them (step 3).
	 */
	public boolean isPromotionsOwnedByHeadOffice() {
		return ownership.ownerOf(DataDomain.PROMOTIONS) == DataOwner.HEAD_OFFICE;
	}

	/**
	 * True on a store whose catalogue is its head office's (step 6): headoffice.url set, ownership.catalogue=HEAD_OFFICE
	 * (accepted only without an ERP). Same rule as the catalogue beans ({@link NodeOwnership#isCatalogueFromHeadOffice}).
	 */
	public boolean isCatalogueFromHeadOffice() {
		return headOfficeLinked && ownership.ownerOf(DataDomain.CATALOGUE) == DataOwner.HEAD_OFFICE;
	}

	/**
	 * True on a store whose goods come from its head office by BL (step 7A): headoffice.url set, an explicit
	 * ownership.supply=HEAD_OFFICE (accepted only without an ERP). Same rule as the supply beans ({@link NodeOwnership#isSupplyFromHeadOffice}).
	 */
	public boolean isSupplyFromHeadOffice() {
		return supplyFromHeadOffice;
	}

	/**
	 * True on a store whose loyalty is owned by its head office (ownership.loyalty=HEAD_OFFICE, which needs
	 * headoffice.url): one member register for the network; the program is only consulted there (step 4).
	 */
	public boolean isLoyaltyOwnedByHeadOffice() {
		return ownership.ownerOf(DataDomain.LOYALTY) == DataOwner.HEAD_OFFICE;
	}

	/** Where sales copies go: sales.upstream, derived from the mode flags when absent. Empty = nowhere. */
	public Set<SalesUpstream> salesUpstreams() {
		return ownership.getSalesUpstreams();
	}

	/** Step 9 (task 9.1b): see {@link NodeOwnership#isCatalogueFromErp()}. Same answer as the old isStandalone() check, negated. */
	public boolean isCatalogueFromErp() {
		return ownership.isCatalogueFromErp();
	}

	/** Step 9 (task 9.1b): see {@link NodeOwnership#isCustomersFromErp()}. Same answer as the old isStandalone() check, negated. */
	public boolean isCustomersFromErp() {
		return ownership.isCustomersFromErp();
	}

	/** Step 9 (task 9.1b): see {@link NodeOwnership#isSupplyFromErp()}. Same answer as the old isStandalone() check, negated. */
	public boolean isSupplyFromErp() {
		return ownership.isSupplyFromErp();
	}

	/** Step 9 (task 9.1b): see {@link NodeOwnership#hasErp()}. Same answer as the old isStandalone() check, negated. */
	public boolean hasErp() {
		return ownership.hasErp();
	}
}
