package com.digithink.zsretail.headoffice.controller;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.link.dto.HeadOfficeHeartbeatAnswerDTO;
import com.digithink.zsretail.core.link.dto.HeadOfficeHeartbeatDTO;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.model.enumeration.DataOwner;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.security.StoreApiKeyFilter;
import com.digithink.zsretail.headoffice.service.StoreService;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 1.4: the store's heartbeat. Head office only. The store is the principal set by
 * {@link StoreApiKeyFilter}; lastContact (head office clock) and the version sent are written by id through
 * {@link StoreService#recordContact}, never by saving the principal. Answers like GET /ho/ping. Task 3.6: also what
 * the store owns (ownership, sales upstreams), in the same update; a store that sends nothing is unknown.
 * <p>
 * 3.1.1: a head office with headoffice.supply.source=NONE supplies no store. A store that reports
 * ownership.supply=HEAD_OFFICE is refused after its contact is recorded (the Stores page shows what it reported): 409
 * {"error": {@link #SUPPLY_NONE_REFUSAL}}, which the store writes to its Communications log.
 */
@RestController
@RequestMapping("ho")
@ConditionalOnInstallation(Installation.HEAD_OFFICE)
@Log4j2
public class HeadOfficeHeartbeatAPI {

	/** 3.1.1: the answer to a store whose goods come from a head office that supplies no store. */
	public static final String SUPPLY_NONE_REFUSAL = "This head office supplies no store"
			+ " (headoffice.supply.source=NONE), but this store gets its goods from the head office"
			+ " (ownership.supply=HEAD_OFFICE). Set ownership.supply=LOCAL in this store's file and restart the store.";

	private final StoreService storeService;

	/** 3.1.1: headoffice.supply.source=NONE (ApplicationModeService.isSupplyNone()). */
	private final boolean supplyNone;

	@Autowired
	public HeadOfficeHeartbeatAPI(StoreService storeService, ApplicationModeService mode) {
		this(storeService, mode.isSupplyNone());
	}

	/** A head office that supplies its stores (any supply source but NONE). Used by the tests. */
	public HeadOfficeHeartbeatAPI(StoreService storeService) {
		this(storeService, false);
	}

	/** supplyNone as above. Used by the tests. */
	public HeadOfficeHeartbeatAPI(StoreService storeService, boolean supplyNone) {
		this.storeService = storeService;
		this.supplyNone = supplyNone;
	}

	/**
	 * Step 4: the answer also carries the store's loyalty rights (null in a row made before step 4: false). Step 6: and
	 * its catalogue rights, mayChangePrices and canPurchase (null: false). 3.1.1: {@link SupplyRefused} (409) on a head
	 * office that supplies no store, for a store whose goods come from the head office.
	 */
	@PostMapping("/heartbeat")
	public HeadOfficeHeartbeatAnswerDTO heartbeat(@AuthenticationPrincipal Store store,
			@RequestBody(required = false) HeadOfficeHeartbeatDTO body) {
		OffsetDateTime now = OffsetDateTime.now();
		storeService.recordContact(store.getId(), body, now.toLocalDateTime());
		if (supplyNone && suppliedByHeadOffice(body)) {
			log.warn("Heartbeat of store {} refused: it reports ownership.supply=HEAD_OFFICE, this head office supplies no"
					+ " store (headoffice.supply.source=NONE)", store.getCode());
			throw new SupplyRefused();
		}
		return new HeadOfficeHeartbeatAnswerDTO(store.getCode(), now.format(HeadOfficePingAPI.SERVER_TIME),
				Boolean.TRUE.equals(store.getCanEditMembers()), Boolean.TRUE.equals(store.getCanAdjustPoints()),
				Boolean.TRUE.equals(store.getRedeemRequiresOnline()), Boolean.TRUE.equals(store.getEnrolRequiresOnline()),
				Boolean.TRUE.equals(store.getMayChangePrices()), Boolean.TRUE.equals(store.getCanPurchase()));
	}

	/** 3.1.1: 409 {"error"} for a refused heartbeat. */
	@ExceptionHandler(SupplyRefused.class)
	public ResponseEntity<Map<String, String>> supplyRefused() {
		return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
				.body(Collections.singletonMap("error", SUPPLY_NONE_REFUSAL));
	}

	/** The store reports SUPPLY owned by HEAD_OFFICE (read leniently, like the owners saved); false for an older store. */
	static boolean suppliedByHeadOffice(HeadOfficeHeartbeatDTO body) {
		if (body == null || body.getOwnership() == null) {
			return false;
		}
		String owner = body.getOwnership().get(DataDomain.SUPPLY.name());
		return owner != null && DataOwner.HEAD_OFFICE.name().equalsIgnoreCase(owner.trim());
	}

	/** 3.1.1: a heartbeat refused because this head office supplies no store. */
	public static final class SupplyRefused extends RuntimeException {

		private static final long serialVersionUID = 1L;

		SupplyRefused() {
			super(SUPPLY_NONE_REFUSAL);
		}
	}
}
