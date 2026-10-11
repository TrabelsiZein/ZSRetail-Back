package com.digithink.zsretail.holink.dto;

import java.time.LocalDateTime;

import com.digithink.zsretail.holink.enumeration.DownRecordStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * A row of the store's list of the records received from the head office (GET admin/holink/received/{domain}), built
 * by the query itself (DownRecordRepository.findToCheck): every column but the payload, which the list never reads.
 */
@Getter
@AllArgsConstructor
public class DownRecordRowDTO {

	private String recordCode;
	private String recordName;
	private DownRecordStatus status;
	private String reason;
	private String info;
	private LocalDateTime receivedAt;
	private LocalDateTime statusSince;
}
