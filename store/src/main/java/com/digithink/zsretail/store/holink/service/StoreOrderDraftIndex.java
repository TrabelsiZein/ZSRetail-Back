package com.digithink.zsretail.store.holink.service;

import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;

import lombok.extern.log4j.Log4j2;

/**
 * 3.1.1: the filtered unique index of hol_order that keeps one DRAFT at a time (two first lines added at once cannot
 * make two drafts). Hibernate cannot create a filtered index and on a new database db/3.1.1/store-orders.sql never
 * runs: created here at the start when it is missing (the same statement as the script), as UserBadgeCodeIndex does. A
 * failure (two drafts already) is logged; StoreOrderService keeps working on the oldest draft. 3.1.1, colis: the unique
 * keys of hol_order_colis of the earlier colis builds (uk_hol_order_colis_item, uk_hol_order_colis_variant) are dropped
 * (Hibernate adds uk_hol_order_colis_order_item but never drops an old key), as the script does.
 */
@Component
@ConditionalOnInstallation(Installation.SUPPLY_FROM_HEAD_OFFICE)
@Log4j2
public class StoreOrderDraftIndex {

	public static final String INDEX = "ux_hol_order_draft";

	static final String CREATE_WHEN_MISSING = "IF OBJECT_ID('hol_order', 'U') IS NOT NULL"
			+ " AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = '" + INDEX + "' AND object_id = OBJECT_ID('hol_order'))"
			+ " CREATE UNIQUE INDEX " + INDEX + " ON hol_order (status) WHERE status = 'DRAFT'";

	/** The colis' unique key of an earlier build: one row per item, which colis per colour row break. */
	static final List<String> OLD_COLIS_KEYS = Arrays.asList("uk_hol_order_colis_item", "uk_hol_order_colis_variant");

	/** The statement dropping an old key when present. */
	static String dropOldColisKey(String key) {
		return "IF OBJECT_ID('" + key + "', 'UQ') IS NOT NULL ALTER TABLE hol_order_colis DROP CONSTRAINT " + key;
	}

	private final JdbcTemplate jdbc;

	public StoreOrderDraftIndex(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void ensure() {
		try {
			jdbc.execute(CREATE_WHEN_MISSING);
		} catch (RuntimeException e) {
			log.warn("The index {} could not be created ({}); one draft at a time stays checked by the application",
					INDEX, e.getMessage());
		}
		for (String key : OLD_COLIS_KEYS) {
			try {
				jdbc.execute(dropOldColisKey(key));
			} catch (RuntimeException e) {
				log.warn("The old key {} of hol_order_colis could not be dropped ({}): run db/3.1.1/store-orders.sql",
						key, e.getMessage());
			}
		}
	}
}
