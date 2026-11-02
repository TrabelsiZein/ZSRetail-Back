package com.digithink.zsretail.connector.parafendri.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.parafendri.ParafendriConnectorDescriptor;
import com.digithink.zsretail.connector.parafendri.client.ParafendriRestClient;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriCompanyRow;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.ErpStoreLocation;

import lombok.extern.log4j.Log4j2;

/**
 * ParaFendri, step 1: the connection check, once the application is up (the database ready, the installation summary
 * logged), on its own thread so that it never delays the start nor a sale: the company of company-id among the
 * companies of the API, then the store's location (the General Setup DEFAULT_LOCATION, read through
 * {@link ErpStoreLocation}) among the locations of the company. One INFO line when both are found, else one WARNING
 * naming the problem (Business Central unreachable, an HTTP status, the company or the location missing, no
 * DEFAULT_LOCATION). Never throws: the store sells without the ERP. {@link ParafendriStartupCheck} stays the settings
 * only, before the start. Exists only with erp.connector=parafendri.
 */
@Component
@ConditionalOnErpConnector(ParafendriConnectorDescriptor.NAME)
@Log4j2
public class ParafendriConnectionCheck {

	static final String THREAD_NAME = "parafendri-connection-check";
	static final String PREFIX = "ERP connector " + ParafendriConnectorDescriptor.NAME + ": ";

	private final ParafendriRestClient client;
	private final ErpStoreLocation storeLocation;

	public ParafendriConnectionCheck(ParafendriRestClient client, ErpStoreLocation storeLocation) {
		this.client = client;
		this.storeLocation = storeLocation;
	}

	/** The check of what one run found: its line and whether it is a warning. */
	static final class Result {

		final boolean ok;
		final String line;

		Result(boolean ok, String line) {
			this.ok = ok;
			this.line = line;
		}
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		start();
	}

	/** The check on its own daemon thread, started and returned (the tests wait for it). */
	Thread start() {
		Thread thread = new Thread(this::checkAndLog, THREAD_NAME);
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	/** The check, its line logged: INFO when all was found, WARNING otherwise. Never throws. */
	void checkAndLog() {
		Result result = check();
		if (result.ok) {
			log.info(result.line);
		} else {
			log.warn(result.line);
		}
	}

	/** The company, then the store's location; never throws, whatever fails. */
	Result check() {
		try {
			ParafendriCompanyRow company = client.checkCompany();
			String code = storeLocation.defaultLocationCode();
			code = code == null ? "" : code.trim();
			String reached = "Business Central reachable, company " + company.getName();
			if (code.isEmpty()) {
				return warning(reached + ", but the General Setup DEFAULT_LOCATION is empty: set the store's location"
						+ " code (e.g. PV-GREMDA).");
			}
			if (!client.checkLocation(code)) {
				return warning(reached + ", but the location " + code + " (General Setup DEFAULT_LOCATION) is not a"
						+ " location of the company: check the code.");
			}
			return new Result(true, PREFIX + reached + ", location " + code + " found.");
		} catch (RuntimeException e) {
			return warning("connection check failed, the store starts without the ERP: " + e.getMessage());
		}
	}

	private static Result warning(String text) {
		return new Result(false, PREFIX + text);
	}
}
