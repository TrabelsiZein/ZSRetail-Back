package com.digithink.zsretail.core.model;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;
import com.digithink.zsretail.core.utils.Quantities;
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
// Item variants (ZEN), step 11a: the "NAV items" page groups by nav_item_no (also in db/3.1.0/update.sql)
@Table(name = "item", indexes = @Index(name = "ix_item_nav_item_no", columnList = "nav_item_no"))
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

	/** 2.2.1: up to 3 decimals (bulk items); read through {@link #getStockQuantity()}, so 5.000 travels as 5. */
	@Column(precision = Quantities.PRECISION, scale = Quantities.SCALE)
	private BigDecimal stockQuantity;

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

	// Item variants (ZEN), step 1: one item per variant of the ERP (item_code = NAV item number + "~" + variant code).
	// Written by the imports; null for a customer whose ERP gives no variant. The brand goes in brand above.

	/** The ERP's item number of the variant (several items share it). */
	@Column(name = "nav_item_no")
	private String navItemNo;

	@Column(name = "variant_code")
	private String variantCode;

	@Column(name = "variant_description")
	private String variantDescription;

	@Column(name = "size_code")
	private String sizeCode;

	@Column(name = "size_description")
	private String sizeDescription;

	/** The ERP's order of the sizes (S before M before L). */
	@Column(name = "size_sort_order")
	private Integer sizeSortOrder;

	@Column(name = "color_code")
	private String colorCode;

	@Column(name = "color_description")
	private String colorDescription;

	@Column(name = "choice_code")
	private String choiceCode;

	@Column(name = "choice_description")
	private String choiceDescription;

	@Column(name = "season_code")
	private String seasonCode;

	@Column(name = "division_code")
	private String divisionCode;

	/** item_category.code (the level above the family); by code, no foreign key. */
	@Column(name = "category_code")
	private String categoryCode;

	/** item_section.code (the level below the sub-family); by code, no foreign key. */
	@Column(name = "section_code")
	private String sectionCode;

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

	/** The stock without trailing zeros (the column has 3 decimals): 5.000 is 5, 0.800 is 0.8. */
	public BigDecimal getStockQuantity() {
		return Quantities.normalize(stockQuantity);
	}
}
