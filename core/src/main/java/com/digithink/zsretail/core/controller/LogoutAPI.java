package com.digithink.zsretail.core.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.eventlog.service.AccessEvents;
import com.digithink.zsretail.core.security.CurrentUserProvider;

import lombok.extern.log4j.Log4j2;

/**
 * 3.4.0, event log step 3: POST /logout, called by the browser when the user leaves (from step 5). It only writes the
 * LOGOUT event: the API is stateless, the token stays valid until it expires and the browser drops it. 204 always, also
 * with the event log off (a head office included); a logged user is needed, as for every endpoint not listed in
 * SecurityConfig.
 */
@RestController
@Log4j2
public class LogoutAPI {

	@Autowired
	private CurrentUserProvider currentUserProvider;

	@Autowired(required = false)
	private AccessEvents accessEvents;

	@PostMapping("/logout")
	public ResponseEntity<Void> logout() {
		try {
			if (accessEvents != null && accessEvents.isOn()) {
				accessEvents.logout(currentUserProvider.getCurrentUser());
			}
		} catch (RuntimeException e) {
			log.warn("LogoutAPI::logout: event not written ({})", e.toString());
		}
		return ResponseEntity.noContent().build();
	}
}
