package com.digithink.zsretail.headoffice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import com.digithink.zsretail.headoffice.model.HoStoreOrder;
import com.digithink.zsretail.headoffice.model.HoStoreOrderColis;
import com.digithink.zsretail.headoffice.model.HoStoreOrderLine;
import com.digithink.zsretail.headoffice.model.Store;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1: a store's order as the head office's page reads it: a row of the list (lines null) or the order with its
 * lines. See docs/modules/store-orders.md.
 */
@Data
@NoArgsConstructor
public class HoStoreOrderDTO {

	private Long id;
	private Long storeId;
	private String storeCode;
	private String storeName;
	private String number;
	/** SENT, POSTED, ERROR, REJECTED or DELIVERED. */
	private String status;
	private String locationCode;
	private LocalDateTime sentAt;
	private LocalDateTime receivedAt;
	private String navOrderNo;
	private LocalDateTime postedAt;
	/** ERP or MANUAL; null until posted. */
	private String postedMode;
	private String errorMessage;
	/** NAV's message on the order when it was posted. */
	private String navMessage;
	/** DELIVERED: the ERP's invoice number and its posting date. */
	private String invoiceNo;
	private LocalDate deliveredAt;
	private int postAttempts;
	private LocalDateTime lastAttemptAt;
	private String note;
	private int lineCount;
	/** The pieces of the lines (the colis apart). */
	private BigDecimal totalQty;
	/** 3.1.1: the colis in all. */
	private int totalColis;
	/** Who posted it by hand or rejected it, as the last change names it. */
	private String updatedBy;
	/** Null in a list row. */
	private List<Line> lines;
	/** 3.1.1: the colis, one per ERP item; null in a list row. */
	private List<Colis> colis;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Colis {
		private Long id;
		private String navItemNo;
		private String description;
		private int nbColis;
		private Integer contenance;
		private Integer colisPossibles;
		private Integer navAcceptedColis;
		private String navMessage;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Line {
		private Long id;
		private String itemCode;
		private String navItemNo;
		private String variantCode;
		private String description;
		private String variantDescription;
		private BigDecimal quantity;
		/** The quantity NAV accepted when the order was posted (null: none given). */
		private BigDecimal navAcceptedQty;
		private String navLineMessage;
	}

	/** A row of the list: the header only; store null when it is gone. */
	public static HoStoreOrderDTO row(HoStoreOrder order, Store store) {
		HoStoreOrderDTO view = new HoStoreOrderDTO();
		view.setId(order.getId());
		view.setStoreId(order.getStoreId());
		view.setStoreCode(store == null ? null : store.getCode());
		view.setStoreName(store == null ? null : store.getName());
		view.setNumber(order.getNumber());
		view.setStatus(order.getStatus() == null ? null : order.getStatus().name());
		view.setLocationCode(order.getLocationCode());
		view.setSentAt(order.getSentAt());
		view.setReceivedAt(order.getReceivedAt());
		view.setNavOrderNo(order.getNavOrderNo());
		view.setPostedAt(order.getPostedAt());
		view.setPostedMode(order.getPostedMode() == null ? null : order.getPostedMode().name());
		view.setErrorMessage(order.getErrorMessage());
		view.setNavMessage(order.getNavMessage());
		view.setInvoiceNo(order.getInvoiceNo());
		view.setDeliveredAt(order.getDeliveredAt());
		view.setPostAttempts(order.getPostAttempts());
		view.setLastAttemptAt(order.getLastAttemptAt());
		view.setNote(order.getNote());
		view.setLineCount(order.getLineCount());
		view.setTotalQty(order.getTotalQty());
		view.setTotalColis(order.getTotalColis());
		view.setUpdatedBy(order.getUpdatedBy());
		return view;
	}

	/** The order with its lines (read inside the transaction: the lines are a lazy collection). */
	public static HoStoreOrderDTO of(HoStoreOrder order, Store store) {
		HoStoreOrderDTO view = row(order, store);
		view.setLines(order.getLines().stream().map(HoStoreOrderDTO::line).collect(Collectors.toList()));
		view.setColis(order.getColis().stream().map(HoStoreOrderDTO::colis).collect(Collectors.toList()));
		return view;
	}

	private static Colis colis(HoStoreOrderColis colis) {
		return new Colis(colis.getId(), colis.getNavItemNo(), colis.getDescription(), colis.getNbColis(),
				colis.getContenance(), colis.getColisPossibles(), colis.getNavAcceptedColis(), colis.getNavMessage());
	}

	private static Line line(HoStoreOrderLine line) {
		return new Line(line.getId(), line.getItemCode(), line.getNavItemNo(), line.getVariantCode(),
				line.getDescription(), line.getVariantDescription(), line.getQuantity(), line.getNavAcceptedQty(),
				line.getNavLineMessage());
	}
}
