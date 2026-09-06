package com.digithink.zsretail.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * Invoice header - aggregates one or more completed tickets for a customer.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class InvoiceHeader extends _BaseEntity {

	@Column(nullable = false, unique = true)
	private String invoiceNumber;

	@Column(nullable = false)
	private LocalDate invoiceDate = LocalDate.now();

	@ManyToOne
	@JoinColumn(name = "customer_id", nullable = false)
	private Customer customer;

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

	/** Editable snapshot of customer info captured at invoice creation time. */
	private String snapshotCustomerName;
	private String snapshotCustomerAddress;
	private String snapshotCustomerPhone;
	private String snapshotCustomerTaxRegNo;

	@OneToMany(mappedBy = "invoice")
	private List<InvoiceLine> lines;

	/**
	 * Franchise: location code of the franchise client this invoice targets.
	 * Copied from customer.defaultLocation at invoice creation time.
	 * Null for regular customer invoices — no impact on existing behaviour.
	 */
	@Column(name = "franchise_location_code")
	private String franchiseLocationCode;

	/**
	 * Franchise: timestamp set when the franchise client acknowledges receipt of this invoice.
	 * Null means the invoice has not yet been received by the franchise client.
	 */
	@Column(name = "franchise_received_at")
	private LocalDateTime franchiseReceivedAt;
}

