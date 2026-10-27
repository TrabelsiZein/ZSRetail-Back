package com.digithink.zsretail.store.holink.model;

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
import com.digithink.zsretail.store.holink.enumeration.StoreOrderStatus;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 3.1.1: an order of the store to its head office ("Commandes centrale"), its number given when the draft is made (the
 * head office takes a number once, so an order sent again after a lost answer is never doubled). One DRAFT at a time
 * (filtered unique index ux_hol_order_draft of db/3.1.1/store-orders.sql). Store table of the head office link (prefix
 * hol_). See docs/modules/store-orders.md.
 */
@Entity
@Table(name = "hol_order", uniqueConstraints = @UniqueConstraint(name = "uk_hol_order_number", columnNames = "number"),
		indexes = @Index(name = "ix_hol_order_status", columnList = "status, created_at"))
@Data
@EqualsAndHashCode(callSuper = false)
@ToString(exclude = "lines")
@NoArgsConstructor
public class StoreOrder extends _BaseEntity {

	public static final int NUMBER_LENGTH = 30;
	public static final int NOTE_LENGTH = 500;
	public static final int ERROR_LENGTH = 1000;
	public static final int NAV_MESSAGE_LENGTH = 500;

	/** Store code, year and sequence, e.g. S01-2026-000012. */
	@Column(nullable = false, length = NUMBER_LENGTH)
	private String number;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private StoreOrderStatus status = StoreOrderStatus.DRAFT;

	/** Store clock of the send the head office accepted. */
	@Column(name = "sent_at")
	private LocalDateTime sentAt;

	/** The NAV document number, once the head office posted the order. */
	@Column(name = "nav_order_no", length = 50)
	private String navOrderNo;

	/** Why the head office could not post the order into NAV (status ERROR). */
	@Column(name = "error_message", length = ERROR_LENGTH)
	private String errorMessage;

	/** NAV's message on the order when it was posted (its answer); null when it gave none. */
	@Column(name = "nav_message", length = NAV_MESSAGE_LENGTH)
	private String navMessage;

	@Column(length = NOTE_LENGTH)
	private String note;

	@Column(name = "line_count", nullable = false)
	private int lineCount;

	@Column(name = "total_qty", nullable = false, precision = Quantities.PRECISION, scale = Quantities.SCALE)
	private BigDecimal totalQty = BigDecimal.ZERO;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id")
	private List<StoreOrderLine> lines = new ArrayList<>();

	/** lineCount and totalQty from the lines. */
	public void recount() {
		lineCount = lines.size();
		BigDecimal total = BigDecimal.ZERO;
		for (StoreOrderLine line : lines) {
			total = total.add(line.getQuantity() == null ? BigDecimal.ZERO : line.getQuantity());
		}
		totalQty = Quantities.normalize(total);
	}

	/** 3 decimals at most (DECIMAL(18,3)), read without trailing zeros. */
	public BigDecimal getTotalQty() {
		return Quantities.normalize(totalQty);
	}
}
