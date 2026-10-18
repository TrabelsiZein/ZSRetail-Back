package com.digithink.zsretail.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Step B (3.1.0): one till action of GET /config tillActions: {@code code} the BadgePermission constant name the till
 * sends to POST /badge/scan (and the i18n key of its label, lower case, in the frontend), {@code key} the role
 * permission key of step C ("till:..."), {@code checkedOnRequest} (step D) whether the server checks the right on the
 * request doing the action (the till then sends the scan's grant with it and does not log the pass itself).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TillActionDTO {

	private String code;
	private String key;
	private boolean checkedOnRequest;
}
