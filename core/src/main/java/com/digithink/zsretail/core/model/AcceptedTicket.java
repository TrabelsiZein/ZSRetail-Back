package com.digithink.zsretail.core.model;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.ForeignKey;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 3.2.0, step 3: a ticket type a payment method of behaviour SCANNED_TICKETS accepts ("tickets acceptés"): its issuer,
 * its code (the barcode's prefix, digits), the deduction taken off its face value (0-100 %), its validity (months after
 * the end of the printed year; null: not checked) and where its barcode keeps the fields ({@link BarcodeLayout}). A
 * barcode is read by TicketBarcodeParser; the lines are kept by AcceptedTicketService (store). Table accepted_ticket,
 * also created by db/3.2.0/update.sql (the foreign key named as here).
 */
@Entity
@Table(name = "accepted_ticket")
@Data
@EqualsAndHashCode(callSuper = false)
@ToString(exclude = "paymentMethod")
@NoArgsConstructor
public class AcceptedTicket extends _BaseEntity {

	public static final int MAX_CODE_LENGTH = 20;

	@ManyToOne(optional = false)
	@JoinColumn(name = "payment_method_id", nullable = false,
			foreignKey = @ForeignKey(name = "fk_accepted_ticket_payment_method"))
	@JsonIgnore
	private PaymentMethod paymentMethod;

	@Column(nullable = false)
	private String issuerName;

	@Column(nullable = false, length = MAX_CODE_LENGTH)
	private String ticketCode;

	@Column(nullable = false, precision = 5, scale = 2)
	private BigDecimal deductionPercent = BigDecimal.ZERO;

	/** Months after the end of the printed year (1: until 31 January of the next year); null: no validity check. */
	private Integer validityMonths = 1;

	@Embedded
	private BarcodeLayout layout = BarcodeLayout.standard();

	@JsonProperty("paymentMethodId")
	public Long getPaymentMethodId() {
		return paymentMethod != null ? paymentMethod.getId() : null;
	}
}
