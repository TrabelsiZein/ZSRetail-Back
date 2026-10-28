package com.digithink.zsretail.store.holink.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1: the last order of this store that holds an ERP item, as the variants window shows it under its title ("Dernière
 * commande"): its number, status and date (sent, else created), and the quantity of each of the item's variants in it.
 * See docs/modules/store-orders.md.
 */
@Data
@NoArgsConstructor
public class StoreOrderLastDTO {

	private Long id;
	private String number;
	private String status;
	/** The send's date, else the creation's. */
	private LocalDateTime date;
	/** The order's lines of that ERP item only. */
	private List<Line> lines;
	/** 3.1.1: the order's colis of that ERP item by variant code (each colour row's variant); empty: none. */
	private Map<String, Integer> colis = new LinkedHashMap<>();

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Line {
		private Long itemId;
		private String variantCode;
		private BigDecimal quantity;
	}
}
