package com.digithink.zsretail.core.link.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.2.0, POS machines step 5: the cash movements of a store's session, its totals only (no list at the head office):
 * inTotal (the apports), outTotal (the prélèvements and the dépenses). The head office's sessions list adds in - out to
 * its system side, as the store does; a copy without it (a store before 3.2.0) counts no movement.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionCashMovementsCopyDTO {
	private Double inTotal;
	private Double outTotal;
}
