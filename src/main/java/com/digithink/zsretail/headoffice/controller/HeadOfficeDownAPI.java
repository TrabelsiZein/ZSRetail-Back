package com.digithink.zsretail.headoffice.controller;

import java.util.Collections;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.security.StoreApiKeyFilter;
import com.digithink.zsretail.headoffice.service.CopiesDownFeed;

/**
 * Head office plan, task 3.1: the copies down of a domain for the calling store, GET /ho/down/{domain}?cursor=&limit=.
 * Head office only, under the /ho/** chain (store key, then license). The store is the principal set by
 * {@link StoreApiKeyFilter}. 200 with a CopiesDownAnswerDTO; 400 {"error"} for a cursor that cannot be read; 404
 * {"error"} for a domain without copies down.
 */
@RestController
@RequestMapping("ho/down")
@ConditionalOnHeadOffice
public class HeadOfficeDownAPI {

	private final CopiesDownFeed feed;

	public HeadOfficeDownAPI(CopiesDownFeed feed) {
		this.feed = feed;
	}

	@GetMapping("/{domain}")
	public ResponseEntity<?> pull(@AuthenticationPrincipal Store store, @PathVariable String domain,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		try {
			return ResponseEntity.ok(feed.pull(store, domain, cursor, limit));
		} catch (NoSuchElementException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Collections.singletonMap("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
		}
	}
}
