package com.digithink.zsretail.core.link.dto;

import java.time.LocalDateTime;

import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.3.0, communication step 6: an event of a store whose loyalty is the head office's, sent up (POST
 * /ho/communication/events, link job COMMUNICATION_PUSH). Its dedup key keeps it once at the head office (sent twice:
 * harmless). No status travels: the head office decides. Unknown fields are ignored (rows travel between versions).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommunicationEventCopyDTO {

	private String eventType;
	private String sourceType;
	/** The source's id in the store's database (meaningful only with originStoreCode). */
	private Long sourceId;
	private String dedupKey;
	private String recipientType;
	/** The card number, the same at the store and at the head office. */
	private String recipientRef;
	/** The values at that moment, JSON. */
	private String payload;
	/** The store where it happened (DEFAULT_LOCATION); the head office keeps the calling store's code. */
	private String originStoreCode;
	/** The store's clock: the lifetime of a message counts from it. */
	private LocalDateTime occurredAt;

	public static CommunicationEventCopyDTO of(CommunicationEvent event) {
		CommunicationEventCopyDTO copy = new CommunicationEventCopyDTO();
		copy.setEventType(event.getEventType());
		copy.setSourceType(event.getSourceType());
		copy.setSourceId(event.getSourceId());
		copy.setDedupKey(event.getDedupKey());
		copy.setRecipientType(event.getRecipientType());
		copy.setRecipientRef(event.getRecipientRef());
		copy.setPayload(event.getPayload());
		copy.setOriginStoreCode(event.getOriginStoreCode());
		copy.setOccurredAt(event.getOccurredAt());
		return copy;
	}
}
