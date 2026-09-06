package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.digithink.zsretail.model.enumeration.ItemType;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Item entity - represents products/services in the POS system
 */
@Entity
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
	 * Franchise: mandatory selling price for franchise clients.
	 * When franchise admin mode is active, this field is required on save.
	 * Franchise clients receive this as their unitPrice during item sync.
	 * Null in normal (non-franchise) mode — no impact on existing behaviour.
	 */
	@Column(name = "franchise_sales_price")
	private Double franchiseSalesPrice;

	/**
	 * True when this item was synced from the franchise admin (read-only for franchise clients).
	 * False (default) when the item was created locally.
	 * Only meaningful when franchise.customer=true; ignored in all other modes.
	 */
	@Column(name = "from_franchise_admin")
	private Boolean fromFranchiseAdmin = false;
}
