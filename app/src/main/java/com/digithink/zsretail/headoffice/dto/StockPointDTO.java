package com.digithink.zsretail.headoffice.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stock points, step 1: a point de stock as the admin API reads and writes it. sortOrder is changed only by
 * PUT .../order; itemCount and storeCount are computed, never read from the client.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StockPointDTO {

	private Long id;
	private String code;
	private String name;
	private Boolean active;
	private Integer sortOrder;
	private Long itemCount;
	private Long storeCount;

	/** Step 4: the last items run that read this point (time, OK / NO_ANSWER / FAILED, counts or error); never read from the client. */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private LocalDateTime lastReadAt;
	private String lastReadStatus;
	private String lastReadSummary;

	public StockPointDTO(Long id, String code, String name, Boolean active, Integer sortOrder, Long itemCount,
			Long storeCount) {
		this(id, code, name, active, sortOrder, itemCount, storeCount, null, null, null);
	}
}
