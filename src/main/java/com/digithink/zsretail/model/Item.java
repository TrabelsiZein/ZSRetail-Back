package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Item entity - represents products/services in the POS system.
 * Promotion.getItem is LAZY, so an Item can reach JSON as a Hibernate proxy (also
 * through Promotion.item / groupItems sharing that instance): the proxy-only
 * properties are ignored so it serializes like a loaded Item.
 */
@Entity
// franchiseSalesPrice, fromFranchiseAdmin: fields of the franchise profiles removed at step 9 (task 9.4a), still sent
// by older screens: ignored, never refused. Their columns stay in the table.
@JsonIgnoreProperties({ "hibernateLazyInitializer", "handler", "franchiseSalesPrice", "fromFranchiseAdmin" })
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class Item extends _BaseEntity {

	@Column(name = "erp_external_id")
	private String erpExternalId;

	@Column(nullable = false, unique = true)
	private String itemCode;

	@Column(nullable = false)
	private String name;

	private String description;

	@Enumerated(EnumType.STRING)
	private ItemType type = ItemType.PRODUCT;

	private Double unitPrice;

	public Integer defaultVAT;

	private Double costPrice;

	/** Last purchase price per unit (HT, before discount). Set automatically after each validated purchase. */
	@Column(name = "last_direct_cost")
	private Double lastDirectCost;

	/** Last net purchase price per unit (HT, after discount). Set automatically after each validated purchase. */
	@Column(name = "last_direct_net_cost")
	private Double lastDirectNetCost;

	private Integer stockQuantity;

	private Integer minStockLevel;

	private String barcode;

	private String imageUrl;

	private String unitOfMeasure;

	private String category;

	private String brand;

	@Column(name = "item_disc_group")
	private String itemDiscGroup;

	@Column(name = "maximum_authorized_discount")
	private Double maximumAuthorizedDiscount; // Maximum discount percentage allowed for this item

	@ManyToOne
	@JoinColumn(name = "item_family_id")
	private ItemFamily itemFamily;

	@ManyToOne
	@JoinColumn(name = "item_sub_family_id")
	private ItemSubFamily itemSubFamily;

	/**
	 * When false, item is hidden from POS (e.g. system items like Tax Stamp).
	 * Default true for normal products.
	 */
	@Column(name = "show_in_pos", nullable = false)
	private Boolean showInPos = true;

	/**
	 * Head office plan, step 6: HEAD_OFFICE when received from the head office (copies down of the catalogue); null =
	 * LOCAL, made here. Written only by the pull (on insert, or by a query when a local item of the same code becomes the
	 * head office item): never read from JSON, never changed by a save.
	 */
	@Enumerated(EnumType.STRING)
	@Column(length = 20, updatable = false)
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	private RecordOrigin origin;

	/**
	 * Step 6 (task 6.5): true when this store put its own selling price on a head office item (right "may change its
	 * selling prices"). The pull then keeps unitPrice and only saves the head office price in headOfficePrice. Null =
	 * false. Never read from JSON.
	 */
	@Column(name = "own_price")
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	private Boolean ownPrice;

	/** Step 6: the selling price the head office sent for this store, kept beside unitPrice. Never read from JSON. */
	@Column(name = "head_office_price")
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	private Double headOfficePrice;
}
