package com.digithink.zsretail.store.rfid.dto;

import java.time.LocalDateTime;

import com.digithink.zsretail.store.rfid.model.RfidTag;
import com.digithink.zsretail.store.rfid.model.RfidTagSource;
import com.digithink.zsretail.store.rfid.model.RfidTagStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.NoArgsConstructor;

/** RFID tags step: one tag on the Tags page, with its item's code and name. */
@Data
@NoArgsConstructor
public class RfidTagDTO {

	private Long id;
	private String epc;
	private Long itemId;
	private String itemCode;
	private String itemName;
	private RfidTagStatus status;
	private RfidTagSource sourceType;
	private String sourceDocument;
	private Integer sourceLine;
	@JsonFormat(pattern = "yyyy-MM-dd | HH:mm:ss")
	private LocalDateTime soldAt;
	private String ticketNumber;
	@JsonFormat(pattern = "yyyy-MM-dd | HH:mm:ss")
	private LocalDateTime createdAt;
	private String createdBy;

	public static RfidTagDTO of(RfidTag tag) {
		RfidTagDTO dto = new RfidTagDTO();
		dto.id = tag.getId();
		dto.epc = tag.getEpc();
		if (tag.getItem() != null) {
			dto.itemId = tag.getItem().getId();
			dto.itemCode = tag.getItem().getItemCode();
			dto.itemName = tag.getItem().getName();
		}
		dto.status = tag.getStatus();
		dto.sourceType = tag.getSourceType();
		dto.sourceDocument = tag.getSourceDocument();
		dto.sourceLine = tag.getSourceLine();
		dto.soldAt = tag.getSoldAt();
		dto.ticketNumber = tag.getTicketNumber();
		dto.createdAt = tag.getCreatedAt();
		dto.createdBy = tag.getCreatedBy();
		return dto;
	}
}
