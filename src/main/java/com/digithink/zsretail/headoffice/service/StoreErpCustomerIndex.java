package com.digithink.zsretail.headoffice.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;

import lombok.extern.log4j.Log4j2;

/**
 * Invoices from the ERP: the filtered unique index of ho_store.erp_customer_no (one store per ERP customer, any number
 * of stores without one). Hibernate cannot create a filtered index, and on a new database ho_store does not exist yet
 * when db/2.1.0/update.sql runs: created here at the start of a head office when it is missing (the same statement as the
 * script). A failure (two stores with the same number already) is logged; StoreService refuses a second store anyway.
 */
@Component
@ConditionalOnHeadOffice
@Log4j2
public class StoreErpCustomerIndex {

	public static final String INDEX = "ux_ho_store_erp_customer_no";

	static final String CREATE_WHEN_MISSING = "IF COL_LENGTH('ho_store', 'erp_customer_no') IS NOT NULL"
			+ " AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = '" + INDEX + "' AND object_id = OBJECT_ID('ho_store'))"
			+ " CREATE UNIQUE INDEX " + INDEX + " ON ho_store (erp_customer_no) WHERE erp_customer_no IS NOT NULL";

	private final JdbcTemplate jdbc;

	public StoreErpCustomerIndex(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void ensure() {
		try {
			jdbc.execute(CREATE_WHEN_MISSING);
		} catch (RuntimeException e) {
			log.warn("Head office: the index {} could not be created ({}); the ERP customer numbers stay checked by the"
					+ " application", INDEX, e.getMessage());
		}
	}
}
