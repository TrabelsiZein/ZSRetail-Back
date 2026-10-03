package com.digithink.zsretail.holink.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeSalesPush;
import com.digithink.zsretail.config.NodeOwnership;

/**
 * Settings of the sales copies (task 2.1), checked at startup by {@link NodeOwnership}. See
 * docs/modules/head-office.md, "Sales copies".
 */
@Component
@ConditionalOnHeadOfficeSalesPush
public class SalesPushSettings {

	private final LocalDateTime fromDate;

	public SalesPushSettings(@Value("${headoffice.sales-push.from-date:}") String fromDate) {
		LocalDate date = NodeOwnership.parseSalesPushFromDate(fromDate);
		this.fromDate = date == null ? null : date.atStartOfDay();
	}

	/** Start of the day headoffice.sales-push.from-date; older documents are ignored. Null: the whole history. */
	public LocalDateTime getFromDate() {
		return fromDate;
	}
}
