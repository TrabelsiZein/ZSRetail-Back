package com.digithink.zsretail.core.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.log4j.Log4j2;

/**
 * Step S (3.1.0): the filtered unique index of user_account.badge_code (one user per badge code, any number of users
 * without a badge). Hibernate cannot create a filtered index, and a JPA unique constraint would create an unfiltered one
 * that refuses a second user without a badge; on a new database db/3.1.0/update.sql never runs. Created here at the
 * start of a store or a head office when it is missing (the same statement as the script), as StoreErpCustomerIndex does
 * for ho_store. A failure (two users with the same code already) is logged; UserAccountAPI refuses a code taken anyway.
 */
@Component
@Log4j2
public class UserBadgeCodeIndex {

	public static final String INDEX = "ux_user_account_badge_code";

	static final String CREATE_WHEN_MISSING = "IF COL_LENGTH('user_account', 'badge_code') IS NOT NULL"
			+ " AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = '" + INDEX + "' AND object_id = OBJECT_ID('user_account'))"
			+ " CREATE UNIQUE INDEX " + INDEX + " ON user_account (badge_code) WHERE badge_code IS NOT NULL";

	private final JdbcTemplate jdbc;

	public UserBadgeCodeIndex(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void ensure() {
		try {
			jdbc.execute(CREATE_WHEN_MISSING);
		} catch (RuntimeException e) {
			log.warn("The index {} could not be created ({}); the badge codes stay checked by the application", INDEX,
					e.getMessage());
		}
	}
}
