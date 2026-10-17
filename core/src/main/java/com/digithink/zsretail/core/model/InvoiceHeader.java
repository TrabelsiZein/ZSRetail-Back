package com.digithink.zsretail.core.model;

import java.time.LocalDate;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;

import com.digithink.zsretail.core.model.enumeration.InvoiceLineGroupingMode;
import com.digithink.zsretail.core.utils.Quantities;

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
	 * 3.1.0: the invoice's money amounts are stored to the millime, half up ({@link Quantities#roundAmount}), on every
	 * save, as the tickets (test log I-07).
	 */
	@PrePersist
	@PreUpdate
	public void roundAmounts() {
		subtotal = Quantities.roundAmountOrNull(subtotal);
		taxAmount = Quantities.roundAmountOrNull(taxAmount);
		discountAmount = Quantities.roundAmountOrNull(discountAmount);
		totalAmount = Quantities.roundAmountOrNull(totalAmount);
	}
}

