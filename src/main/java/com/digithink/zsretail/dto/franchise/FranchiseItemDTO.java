package com.digithink.zsretail.dto.franchise;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Item data sent by the franchise admin to franchise clients during sync.
 * The client sets unitPrice = franchiseSalesPrice from this DTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FranchiseItemDTO {

	private String itemCode;
	private String name;
	private String description;
	private Double franchiseSalesPrice;
	private Integer defaultVAT;
	private String barcode;
	private String imageUrl;
	private String unitOfMeasure;
	private String category;
	private String brand;
	private String itemFamilyCode;
	private String itemFamilyName;
	private String itemSubFamilyCode;
	private String itemSubFamilyName;
	private Boolean showInPos;
	private Boolean active;

	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
	private LocalDateTime updatedAt;

	/** Full list of barcodes for this item (including primary). */
	private List<BarcodeSyncDTO> barcodes;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class BarcodeSyncDTO {
		private String barcode;
		private Boolean isPrimary;
		private String description;
	}
}
