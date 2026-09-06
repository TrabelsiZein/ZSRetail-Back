package com.digithink.zsretail.model;

import java.time.LocalDate;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;

import com.digithink.zsretail.model.enumeration.InvoiceLineGroupingMode;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Purchase invoice (supplier invoice) header - aggregates one or more completed
 * purchases for a vendor. Standalone mode only.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class PurchaseInvoiceHeader extends _BaseEntity {

	@Column(nullable = false, unique = true)
	private String invoiceNumber;

	@Column(nullable = false)
	private LocalDate invoiceDate = LocalDate.now();

	@ManyToOne
	@JoinColumn(name = "vendor_id", nullable = false)
	private Vendor vendor;

	@ManyToOne
	@JoinColumn(name = "created_by_user")
	private UserAccount createdByUser;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private InvoiceLineGroupingMode lineGroupingMode = InvoiceLineGroupingMode.BY_ITEM;

	private Double subtotal;

	private Double taxAmount;

	private Double discountAmount;

	private Double totalAmount;

	private String notes;

	/** Editable snapshot of vendor info captured at invoice creation time. */
	private String snapshotVendorName;
	private String snapshotVendorAddress;
	private String snapshotVendorPhone;
	private String snapshotVendorTaxRegNo;

	@OneToMany(mappedBy = "purchaseInvoice")
	private List<PurchaseInvoiceLine> lines;
}
