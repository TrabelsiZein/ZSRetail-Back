package com.digithink.zsretail.core.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.2, the store's item card, Stock tab: one stock movement of an item (GET /item/{id}/stock-movements), newest first.
 * unitPriceHt only for a role that may see costs (a purchase or a delivery carries a cost); a CMP adjustment only then.
 */
@Data
@NoArgsConstructor
public class ItemStockMovementDTO {

	private Long id;

	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
	private LocalDateTime date;

	/** StockMovementType: SALE, PURCHASE_RECEPTION, DELIVERY_IN, ADJUSTMENT_IN, COST_ADJUSTMENT... */
	private String type;

	/** IN or OUT. */
	private String direction;

	private BigDecimal quantity;

	/** The movement's unit price before VAT (a sale's price, a purchase's cost); null without the cost right. */
	private Double unitPriceHt;

	private String referenceType;

	private Long referenceId;

	private String notes;

	private String createdBy;
}
