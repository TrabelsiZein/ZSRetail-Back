package com.digithink.zsretail.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Step B (3.1.0): one till action of GET /config tillActions: {@code code} the BadgePermission constant name the till
 * sends to POST /badge/scan (and the i18n key of its label, lower case, in the frontend), {@code key} the role
 * permission key of step C ("till:...").
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TillActionDTO {

	private String code;
	private String key;
}
