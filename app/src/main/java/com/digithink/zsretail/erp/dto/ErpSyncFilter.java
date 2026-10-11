package com.digithink.zsretail.erp.dto;

import java.time.OffsetDateTime;

public class ErpSyncFilter {

	private OffsetDateTime updatedAfter;
	private Integer limit;

	public OffsetDateTime getUpdatedAfter() {
		return updatedAfter;
	}

	public void setUpdatedAfter(OffsetDateTime updatedAfter) {
		this.updatedAfter = updatedAfter;
	}

	public Integer getLimit() {
		return limit;
	}

	public void setLimit(Integer limit) {
		this.limit = limit;
	}
}

