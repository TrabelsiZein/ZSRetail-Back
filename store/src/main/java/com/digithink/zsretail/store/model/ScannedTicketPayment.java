package com.digithink.zsretail.store.model;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.ForeignKey;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.core.model.AcceptedTicket;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model._BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 3.2.0, step 4: a scanned ticket (meal ticket, gift cheque) that paid a sale, beside its payment row (one to one: the
 * payment's title number is the barcode, its amount the part applied). The values are copied at the sale, so a later
 * edit of the accepted ticket changes no past sale: issuer, ticket code, printed year, face value, deduction %, counted
 * value (face value minus the deduction), applied amount (at most what was left to pay), excess (counted - applied, kept
 * by the store). {@code liveBarcode} is the barcode while the ticket counts as used: the filtered unique index
 * ux_scanned_ticket_live_barcode (PaymentMethodsUpgrade, db/3.2.0/update.sql) lets one sale only hold it, across tills;
 * a sale that gave the paper back would set it to null (no such path today: only a pending sale, without payments, is
 * cancelled). Table scanned_ticket_payment, store only.
 */
@Entity
@Table(name = "scanned_ticket_payment",
		uniqueConstraints = @UniqueConstraint(name = "uk_scanned_ticket_payment_payment", columnNames = "payment_id"))
@Data
@EqualsAndHashCode(callSuper = false)
@ToString(exclude = { "payment", "acceptedTicket" })
@NoArgsConstructor
public class ScannedTicketPayment extends _BaseEntity {

	public static final int MAX_BARCODE_LENGTH = 64;

	@OneToOne(optional = false)
	@JoinColumn(name = "payment_id", nullable = false,
			foreignKey = @ForeignKey(name = "fk_scanned_ticket_payment_payment"))
	@JsonIgnore
	private Payment payment;

	@ManyToOne(optional = false)
	@JoinColumn(name = "accepted_ticket_id", nullable = false,
			foreignKey = @ForeignKey(name = "fk_scanned_ticket_payment_ticket"))
	@JsonIgnore
	private AcceptedTicket acceptedTicket;

	@Column(nullable = false, length = MAX_BARCODE_LENGTH)
	private String barcode;

	/** The barcode while the ticket counts as used (unique among the rows that have it); null once given back. */
	@Column(length = MAX_BARCODE_LENGTH)
	private String liveBarcode;

	@Column(nullable = false)
	private String issuerName;

	@Column(nullable = false, length = AcceptedTicket.MAX_CODE_LENGTH)
	private String ticketCode;

	private Integer printedYear;

	@Column(nullable = false, precision = 12, scale = 3)
	private BigDecimal faceValue;

	@Column(nullable = false, precision = 5, scale = 2)
	private BigDecimal deductionPercent;

	@Column(nullable = false, precision = 12, scale = 3)
	private BigDecimal countedValue;

	@Column(nullable = false, precision = 12, scale = 3)
	private BigDecimal appliedAmount;

	@Column(nullable = false, precision = 12, scale = 3)
	private BigDecimal excessAmount;
}
