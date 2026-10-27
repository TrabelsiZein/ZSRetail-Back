package com.digithink.zsretail.headoffice.model;

import java.math.BigDecimal;
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
@ToString(exclude = "lines")
@NoArgsConstructor
public class HoStoreOrder extends _BaseEntity {

	public static final int NUMBER_LENGTH = 30;
	public static final int NOTE_LENGTH = 500;
	public static final int ERROR_LENGTH = 1000;

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

	/** 3 decimals at most (DECIMAL(18,3)), read without trailing zeros. */
	public BigDecimal getTotalQty() {
		return Quantities.normalize(totalQty);
	}
}
