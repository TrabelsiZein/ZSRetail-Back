package com.digithink.zsretail.store.holink.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import com.digithink.zsretail.store.holink.model.StoreOrder;
import com.digithink.zsretail.store.holink.model.StoreOrderColis;
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
	/** The pieces of the lines (the colis apart). */
	private BigDecimal totalQty;
	/** 3.1.1: the colis in all. */
	private int totalColis;
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
		/** The pieces of one colis (the sum of the composition); null when unknown. */
		private Integer colisContent;
		private Integer colisAvailable;
		/** The colis the head office's ERP accepted when it posted the order (null: none given). */
		private Integer navAcceptedColis;
		private BigDecimal navAcceptedQty;
		private String navColisNumber;
		private String navMessage;
		/** One colis: the pieces of each variant, as the head office gave them when added. */
		private List<ColisPart> composition;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ColisPart {
		private Long itemId;
		private String itemCode;
		private String variantCode;
		private int qtyPerColis;
		private String sizeCode;
		private String sizeDescription;
		private String colorCode;
		private String colorDescription;
	}

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
		/** 3.1.1: the variant may be ordered by pieces, as the head office said (null: no rule). */
		private Boolean vracAllowed;
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
		view.setTotalColis(order.getTotalColis());
		return view;
	}

	/** The order with its lines (read inside the transaction: the lines are a lazy collection). */
	public static StoreOrderViewDTO of(StoreOrder order) {
		StoreOrderViewDTO view = row(order);
		view.setLines(order.getLines().stream().map(StoreOrderViewDTO::line).collect(Collectors.toList()));
		view.setColis(order.getColis().stream().map(StoreOrderViewDTO::colis).collect(Collectors.toList()));
		return view;
	}

	private static Colis colis(StoreOrderColis colis) {
		return new Colis(colis.getId(), colis.getNavItemNo(), colis.getDescription(), colis.getNbColis(),
				colis.getColisContent(), colis.getColisAvailable(), colis.getNavAcceptedColis(), colis.getNavAcceptedQty(),
				colis.getNavColisNumber(), colis.getNavMessage(),
				colis.getDetails().stream().map(detail -> new ColisPart(detail.getItemId(), detail.getItemCode(),
						detail.getVariantCode(), detail.getQtyPerColis(), detail.getSizeCode(), detail.getSizeDescription(),
						detail.getColorCode(), detail.getColorDescription())).collect(Collectors.toList()));
	}

	private static Line line(StoreOrderLine line) {
		return new Line(line.getId(), line.getItemId(), line.getItemCode(), line.getNavItemNo(), line.getVariantCode(),
				line.getDescription(), line.getVariantDescription(), line.getQuantity(), line.getAvailableQty(),
				line.getNavAcceptedQty(), line.getNavLineMessage(), line.getVracAllowed());
	}
}
