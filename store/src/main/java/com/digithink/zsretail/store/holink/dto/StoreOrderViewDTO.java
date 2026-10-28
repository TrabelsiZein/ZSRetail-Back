package com.digithink.zsretail.store.holink.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import com.digithink.zsretail.store.holink.model.StoreOrder;
import com.digithink.zsretail.store.holink.model.StoreOrderLine;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1: an order to the head office as the pages read it: a row of the list (lines null) or the order with its lines.
 * See docs/modules/store-orders.md.
 */
@Data
@NoArgsConstructor
public class StoreOrderViewDTO {

	private Long id;
	private String number;
	/** DRAFT, SENT, POSTED, ERROR, CANCELLED or DELIVERED. */
	private String status;
	private LocalDateTime createdAt;
	private String createdBy;
	private LocalDateTime sentAt;
	private String navOrderNo;
	private String errorMessage;
	/** The head office's message from its ERP on the order when it was posted. */
	private String navMessage;
	/** DELIVERED: the invoice of the order (a document of the reception page) and its posting date. */
	private String invoiceNo;
	private LocalDate deliveredAt;
	private String note;
	private int lineCount;
	private BigDecimal totalQty;
	/** Null in a list row. */
	private List<Line> lines;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Line {
		private Long id;
		private Long itemId;
		private String itemCode;
		private String navItemNo;
		private String variantCode;
		private String description;
		private String variantDescription;
		private BigDecimal quantity;
		/** The availability the line was ordered against (null: no limit). */
		private BigDecimal availableQty;
		/** The quantity the head office accepted when it posted the order (null: none given). */
		private BigDecimal navAcceptedQty;
		private String navLineMessage;
	}

	/** A row of the list: the header only. */
	public static StoreOrderViewDTO row(StoreOrder order) {
		StoreOrderViewDTO view = new StoreOrderViewDTO();
		view.setId(order.getId());
		view.setNumber(order.getNumber());
		view.setStatus(order.getStatus() == null ? null : order.getStatus().name());
		view.setCreatedAt(order.getCreatedAt());
		view.setCreatedBy(order.getCreatedBy());
		view.setSentAt(order.getSentAt());
		view.setNavOrderNo(order.getNavOrderNo());
		view.setErrorMessage(order.getErrorMessage());
		view.setNavMessage(order.getNavMessage());
		view.setInvoiceNo(order.getInvoiceNo());
		view.setDeliveredAt(order.getDeliveredAt());
		view.setNote(order.getNote());
		view.setLineCount(order.getLineCount());
		view.setTotalQty(order.getTotalQty());
		return view;
	}

	/** The order with its lines (read inside the transaction: the lines are a lazy collection). */
	public static StoreOrderViewDTO of(StoreOrder order) {
		StoreOrderViewDTO view = row(order);
		view.setLines(order.getLines().stream().map(StoreOrderViewDTO::line).collect(Collectors.toList()));
		return view;
	}

	private static Line line(StoreOrderLine line) {
		return new Line(line.getId(), line.getItemId(), line.getItemCode(), line.getNavItemNo(), line.getVariantCode(),
				line.getDescription(), line.getVariantDescription(), line.getQuantity(), line.getAvailableQty(),
				line.getNavAcceptedQty(), line.getNavLineMessage());
	}
}
