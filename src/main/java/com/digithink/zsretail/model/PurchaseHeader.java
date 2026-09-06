package com.digithink.zsretail.model;

import java.time.LocalDateTime;

import java.util.ArrayList;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;

import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Purchase header entity - represents a purchase from a vendor (standalone mode only).
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class PurchaseHeader extends _BaseEntity {

	@Column(nullable = false, unique = true)
	private String purchaseNumber;

	@Column(nullable = false)
	private LocalDateTime purchaseDate = LocalDateTime.now();

	@ManyToOne
	@JoinColumn(name = "vendor_id", nullable = false)
	private Vendor vendor;

	@ManyToOne
	@JoinColumn(name = "created_by_user")
	private UserAccount createdByUser;

	@Enumerated(EnumType.STRING)
	private TransactionStatus status = TransactionStatus.COMPLETED;

	private Double subtotal;

	private Double taxAmount;

	private Double discountAmount;

	@Column(name = "total_amount")
	private Double totalAmount;

	/** Amount paid to vendor (standalone). Null = unpaid. */
	private Double paidAmount;

	/** Date when (partial or full) payment was recorded (standalone). */
	private LocalDateTime paidDate;

	private String notes;

	/** Vendor delivery note / BL number provided by the vendor at time of delivery. Optional. */
	@Column(name = "vendor_bl_number", length = 100)
	private String vendorBlNumber;

	/** Link to purchase invoice when this purchase is included in a supplier invoice. Null if not yet invoiced. */
	@ManyToOne
	@JoinColumn(name = "purchase_invoice_id")
	@JsonIgnore
	private PurchaseInvoiceHeader purchaseInvoice;

	/** True when this purchase has been included in a purchase invoice. */
	private Boolean invoiced = false;

	@OneToMany(mappedBy = "purchaseHeader", fetch = FetchType.LAZY)
	@JsonManagedReference
	private List<PurchaseLine> purchaseLines = new ArrayList<>();
}
