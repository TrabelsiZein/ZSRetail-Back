package com.digithink.zsretail.store.livecash;

import java.util.Collections;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.controller.CashierSessionAPI;
import com.digithink.zsretail.store.service.SessionAmounts;

import lombok.extern.log4j.Log4j2;

/**
 * 3.2.0, "Caisse en direct": GET /cashier-session/live, read only (LiveCashService). The right of the Sessions page
 * (read:admin-sessions-history) opens it, else 403; without "Voir montants session" no amount is sent. A store only (a
 * store bean: the head office has no till).
 */
@RestController
@RequestMapping("cashier-session/live")
@Log4j2
public class LiveCashAPI {

	@Autowired
	private LiveCashService service;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	@GetMapping
	public ResponseEntity<?> read() {
		if (!currentUserProvider.currentUserHasPermission(CashierSessionAPI.SESSIONS_PERMISSION)) {
			return ResponseEntity.status(403).body(Collections.singletonMap("error",
					"Caisse en direct needs the right " + CashierSessionAPI.SESSIONS_PERMISSION));
		}
		try {
			return ResponseEntity.ok(service.read(SessionAmounts.visible(currentUserProvider)));
		} catch (Exception e) {
			log.error("LiveCashAPI::read:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(Collections.singletonMap("error", e.getMessage()));
		}
	}
}
