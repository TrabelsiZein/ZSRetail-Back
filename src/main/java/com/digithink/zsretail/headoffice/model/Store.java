package com.digithink.zsretail.headoffice.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;

import com.digithink.zsretail.headoffice.enumeration.StoreKind;
import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A store known to the head office (head office design 4.6). Head office only: the ho_store table also exists in
 * a store database, where it stays empty. {@code active} comes from {@link _BaseEntity}.
 */
@Entity
@Table(name = "ho_store")
// Task 3.6: computed from the owner columns, sent, never read back (a getter-only Map would otherwise be filled)
@JsonIgnoreProperties(value = { "ownership", "salesUpstreams" }, allowGetters = true)
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class Store extends _BaseEntity {

	/** Length of the app_version column (the JPA default); a longer version sent by a store is cut. */
	public static final int APP_VERSION_LENGTH = 255;

	/** The store's DEFAULT_LOCATION value: trimmed, uppercase, unique. Cannot be changed after creation. */
	@Column(nullable = false, unique = true, length = 50)
	private String code;

	@Column(nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private StoreKind kind = StoreKind.OWN;

	/** Last heartbeat of the store (task 1.4), head office clock; null until the first contact. Never written by the client. */
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	private LocalDateTime lastContact;

	/** Application version the store sent at its last heartbeat; null until then. Never written by the client. */
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	@Column(length = APP_VERSION_LENGTH)
	private String appVersion;

	/** SHA-256 (hex) of the store's API key; the key itself is never stored. Never serialized, never logged. */
	@JsonIgnore
	@ToString.Exclude
	@Column(nullable = false, length = 64)
	private String apiKeyHash;

	// ─── Loyalty rights of the store (step 4) ─────────────────────────────────
	// Set on the Stores page, sent to the store with each heartbeat answer, enforced by the head office. Null in rows
	// made before step 4: read as false. No default here: a PUT without the field must leave it as it is.

	/** The store may change members (edit, deactivate) through the head office. */
	@Column(name = "can_edit_members")
	private Boolean canEditMembers;

	/** The store may adjust points by hand (refused at the store in step 4 whatever the right). */
	@Column(name = "can_adjust_points")
	private Boolean canAdjustPoints;

	/**
	 * Step 5: the store spends points only with a balance refreshed from the head office in the last 2 minutes
	 * (otherwise spending is refused there; earning and the sale still work). False: the store spends against its own
	 * balance, never blocked.
	 */
	@Column(name = "redeem_requires_online")
	private Boolean redeemRequiresOnline;

	/**
	 * Enrol switch (decided 2026-10-04): the store enrols a member only when the head office answers the phone check of
	 * that enrol (otherwise 503 there). False (default): the store enrols offline and the duplicate is merged later.
	 */
	@Column(name = "enrol_requires_online")
	private Boolean enrolRequiresOnline;

	// ─── Catalogue and selling price settings (step 6) ────────────────────────
	// Same rules as the loyalty switches: null read as false; a PUT without the field leaves it as it is.

	/**
	 * Task 6.4: ho_price_list.id of the store's selling price list; null = the base price. Set at creation or with
	 * PUT /admin/headoffice/stores/{id}/selling-price-list (the generic PUT ignores it). Never sent to the store.
	 */
	@Column(name = "selling_price_list_id")
	private Long sellingPriceListId;

	/**
	 * Task 6.5: the store may put its own selling price on a head office item and keeps it across the pulls. Sent with
	 * each heartbeat answer.
	 */
	@Column(name = "may_change_prices")
	private Boolean mayChangePrices;

	/**
	 * Task 6.6: the store may purchase from its own suppliers and create its own items (only on a store whose catalogue
	 * is the head office's). Sent with each heartbeat answer.
	 */
	@Column(name = "can_purchase")
	private Boolean canPurchase;

	// ─── What the store owns, reported with each heartbeat (task 3.6) ─────────
	// Written only by POST /ho/heartbeat, in the same update as lastContact; null = unknown (no report yet, or a store
	// of an older version). Exposed in JSON as "ownership" and "salesUpstreams" below, never read from the client.

	/** DataOwner name the store reported for CATALOGUE; null when unknown. */
	@JsonIgnore
	@Column(name = "owner_catalogue", length = 20)
	private String ownerCatalogue;

	@JsonIgnore
	@Column(name = "owner_customers", length = 20)
	private String ownerCustomers;

	@JsonIgnore
	@Column(name = "owner_promotions", length = 20)
	private String ownerPromotions;

	@JsonIgnore
	@Column(name = "owner_loyalty", length = 20)
	private String ownerLoyalty;

	@JsonIgnore
	@Column(name = "owner_supply", length = 20)
	private String ownerSupply;

	/** SalesUpstream names reported, comma-separated in enum order; "" = nowhere; null = unknown. */
	@JsonIgnore
	@Column(name = "sales_upstreams", length = 50)
	private String reportedSalesUpstreams;

	/**
	 * Task 3.6: DataDomain name to DataOwner name as the store reported it at its last heartbeat, in DataDomain order
	 * (the shape of GET /config "ownership"); null when the store reported nothing (unknown). A domain whose report
	 * could not be read is null.
	 */
	@JsonProperty("ownership")
	public Map<String, String> getOwnership() {
		if (ownerCatalogue == null && ownerCustomers == null && ownerPromotions == null && ownerLoyalty == null
				&& ownerSupply == null) {
			return null;
		}
		Map<String, String> ownership = new LinkedHashMap<>();
		ownership.put(DataDomain.CATALOGUE.name(), ownerCatalogue);
		ownership.put(DataDomain.CUSTOMERS.name(), ownerCustomers);
		ownership.put(DataDomain.PROMOTIONS.name(), ownerPromotions);
		ownership.put(DataDomain.LOYALTY.name(), ownerLoyalty);
		ownership.put(DataDomain.SUPPLY.name(), ownerSupply);
		return ownership;
	}

	/** Task 3.6: the SalesUpstream names reported (empty = nowhere); null when unknown. */
	@JsonProperty("salesUpstreams")
	public List<String> getSalesUpstreams() {
		if (reportedSalesUpstreams == null) {
			return null;
		}
		List<String> upstreams = new ArrayList<>();
		for (String part : reportedSalesUpstreams.split(",")) {
			if (!part.isEmpty()) {
				upstreams.add(part);
			}
		}
		return upstreams;
	}
}
