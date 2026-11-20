package com.digithink.zsretail.core.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.3.0, communication step 4: the communication module in GET /config (AppConfigDTO.communication).
 * docs/modules/communication.md.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommunicationConfigDTO {
	/** communication.enabled=true. */
	private boolean enabled;
	/** The messages are made and sent here (Installation.COMMUNICATION_SENDS_HERE): the Communication page exists. */
	private boolean sendsHere;
	/** The channels switched on, in CommunicationTypes.CHANNELS order (WHATSAPP, SMS, EMAIL); empty when off. */
	private List<String> channels;
}
