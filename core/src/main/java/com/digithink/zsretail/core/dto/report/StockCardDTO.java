package com.digithink.zsretail.core.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.2, an item's stock card over a period (GET /report/stock-movements/item/{id}): its stock at the start and the
 * end, the period's entries and exits, and its movements in time order with the stock after each one.
 * docs/modules/reporting.md, "Stock movements report".
 */
@Data
@NoArgsConstructor
public class StockCardDTO {
    private Long itemId;
    private String itemCode;
    private String itemName;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    /** The stock at the start of dateFrom and at the end of dateTo, counted back from the stock now. */
    private BigDecimal openingQty;
    private BigDecimal closingQty;
    /** Every movement of the period (the lines may be fewer, see truncated). */
    private BigDecimal qtyIn;
    private BigDecimal qtyOut;
    private long movementCount;
    /** True when the period has more movements than the lines sent: the lines are the latest ones. */
    private boolean truncated;
    private List<StockCardLineDTO> lines = new ArrayList<>();
}
