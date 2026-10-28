package com.digithink.zsretail.headoffice.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Index;
import javax.persistence.OneToMany;
import javax.persistence.OrderBy;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.headoffice.enumeration.HoStoreOrderStatus;
import com.digithink.zsretail.headoffice.enumeration.PostedMode;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 3.1.1: an order of a store received by the head office ("Commandes centrale"), its number the store's: taken once per
 * store (uk_ho_store_order_number), the same number again answers the order already here. Head office only data (prefix
 * ho_). See docs/modules/store-orders.md.
 */
@Entity
@Table(name = "ho_store_order",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_store_order_number", columnNames = { "store_id", "number" }),
		indexes = @Index(name = "ix_ho_store_order_status", columnList = "status, received_at"))
@Data
@EqualsAndHashCode(callSuper = false)
@ToString(exclude = { "lines", "colis" })
@NoArgsConstructor
public class HoStoreOrder extends _BaseEntity {

	public static final int NUMBER_LENGTH = 30;
	public static final int NOTE_LENGTH = 500;
	public static final int ERROR_LENGTH = 1000;
	public static final int NAV_MESSAGE_LENGTH = 500;
	public static final int INVOICE_NO_LENGTH = 30;

	/** ho_store.id of the store that sent it. */
	@Column(name = "store_id", nullable = false)
	private Long storeId;

	/** The store's number, e.g. S01-2026-000012. */
	@Column(nullable = false, length = NUMBER_LENGTH)
	private String number;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private HoStoreOrderStatus status = HoStoreOrderStatus.SENT;

	/** The store's location code as sent (its store code). */
	@Column(name = "location_code", length = 50)
	private String locationCode;

	/** The store's clock of the send. */
	@Column(name = "sent_at")
	private LocalDateTime sentAt;

	/** The head office's clock of the reception. */
	@Column(name = "received_at", nullable = false)
	private LocalDateTime receivedAt;

	@Column(name = "nav_order_no", length = 50)
	private String navOrderNo;

	@Column(name = "posted_at")
	private LocalDateTime postedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "posted_mode", length = 10)
	private PostedMode postedMode;

	/** Why the posting failed (ERROR) or why the head office refused it (REJECTED). */
	@Column(name = "error_message", length = ERROR_LENGTH)
	private String errorMessage;

	/** NAV's message on the order when it was posted (its answer); null when it gave none. */
	@Column(name = "nav_message", length = NAV_MESSAGE_LENGTH)
	private String navMessage;

	/** DELIVERED: the ERP's invoice of the order (its number, as ho_erp_invoice.bc_number). */
	@Column(name = "invoice_no", length = INVOICE_NO_LENGTH)
	private String invoiceNo;

	/** DELIVERED: the invoice's posting date. */
	@Column(name = "delivered_at")
	private LocalDate deliveredAt;

	/** The postings tried by the job or "retry now" (ERP mode). */
	@Column(name = "post_attempts", nullable = false)
	private int postAttempts;

	@Column(name = "last_attempt_at")
	private LocalDateTime lastAttemptAt;

	@Column(length = NOTE_LENGTH)
	private String note;

	@Column(name = "line_count", nullable = false)
	private int lineCount;

	@Column(name = "total_qty", nullable = false, precision = Quantities.PRECISION, scale = Quantities.SCALE)
	private BigDecimal totalQty = BigDecimal.ZERO;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id")
	private List<HoStoreOrderLine> lines = new ArrayList<>();

	/** 3.1.1: the colis of the order, one row per ERP item. */
	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id")
	private List<HoStoreOrderColis> colis = new ArrayList<>();

	/** 3.1.1: the colis of the order in all (total_qty counts the pieces of the lines only); null on older rows. */
	@Column(name = "total_colis")
	private Integer totalColis;

	public int getTotalColis() {
		return totalColis == null ? 0 : totalColis;
	}

	/** 3 decimals at most (DECIMAL(18,3)), read without trailing zeros. */
	public BigDecimal getTotalQty() {
		return Quantities.normalize(totalQty);
	}
}
