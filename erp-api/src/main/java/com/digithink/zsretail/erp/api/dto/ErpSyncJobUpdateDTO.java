package com.digithink.zsretail.erp.api.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ErpSyncJobUpdateDTO {
	private String description;
	private String cronExpression;
	private Boolean enabled;
}

