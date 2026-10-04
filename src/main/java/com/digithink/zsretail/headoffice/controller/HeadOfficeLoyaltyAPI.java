package com.digithink.zsretail.headoffice.controller;

import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberAnswerDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberCopyDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberEditDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMovementCopyDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyPhoneCheckDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyPointsAdjustDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyAnswerDTO;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.security.StoreApiKeyFilter;
import com.digithink.zsretail.headoffice.service.HoLoyaltyReceiver;

/**
 * Head office plan, step 4: the stores' calls to the shared loyalty register. Head office only, under the /ho/** chain
 * (store key, then license); the store is the principal set by {@link StoreApiKeyFilter}.
 * <ul>
 * <li>POST /ho/loyalty/members: members enrolled at the store, {"results": [LoyaltyMemberResultDTO]}.</li>
 * <li>POST /ho/loyalty/movements: loyalty movements, {"results": [{documentNumber: key, accepted, message}]}.</li>
 * <li>GET /ho/loyalty/members/by-phone?phone=: {found, member}.</li>
 * <li>PUT /ho/loyalty/members/{cardNumber}: a member changed by a store with the right: 200 the member; 403, 404,
 * 400, 409 {"error"}.</li>
 * </ul>
 */
@RestController
@RequestMapping("ho/loyalty")
@ConditionalOnHeadOffice
public class HeadOfficeLoyaltyAPI {

	private final HoLoyaltyReceiver receiver;

	public HeadOfficeLoyaltyAPI(HoLoyaltyReceiver receiver) {
		this.receiver = receiver;
	}

	@PostMapping("/members")
	public LoyaltyMemberAnswerDTO members(@AuthenticationPrincipal Store store,
			@RequestBody List<LoyaltyMemberCopyDTO> copies) {
		return new LoyaltyMemberAnswerDTO(receiver.receiveMembers(store, copies));
	}

	@PostMapping("/movements")
	public SalesCopyAnswerDTO movements(@AuthenticationPrincipal Store store,
			@RequestBody List<LoyaltyMovementCopyDTO> copies) {
		return new SalesCopyAnswerDTO(receiver.receiveMovements(store, copies));
	}

	@GetMapping("/members/by-phone")
	public LoyaltyPhoneCheckDTO byPhone(@RequestParam(required = false) String phone) {
		return receiver.checkPhone(phone);
	}

	/** Step 5: the member as the head office holds it now (fresh balance at the till); 404 for an unknown card. */
	@GetMapping("/members/{cardNumber}")
	public ResponseEntity<?> member(@PathVariable String cardNumber) {
		try {
			return ResponseEntity.ok(receiver.findMember(cardNumber));
		} catch (NoSuchElementException e) {
			return error(HttpStatus.NOT_FOUND, e.getMessage());
		}
	}

	/** Step 5: a manual adjustment from a store with canAdjustPoints: 200 the member; 403, 404, 400 {"error"}. */
	@PostMapping("/members/{cardNumber}/adjust")
	public ResponseEntity<?> adjust(@AuthenticationPrincipal Store store, @PathVariable String cardNumber,
			@RequestBody(required = false) LoyaltyPointsAdjustDTO request) {
		try {
			return ResponseEntity.ok(receiver.adjustPoints(store, cardNumber, request));
		} catch (HoLoyaltyReceiver.NoRightException e) {
			return error(HttpStatus.FORBIDDEN, e.getMessage());
		} catch (NoSuchElementException e) {
			return error(HttpStatus.NOT_FOUND, e.getMessage());
		} catch (IllegalArgumentException e) {
			return error(HttpStatus.BAD_REQUEST, e.getMessage());
		}
	}

	@PutMapping("/members/{cardNumber}")
	public ResponseEntity<?> edit(@AuthenticationPrincipal Store store, @PathVariable String cardNumber,
			@RequestBody(required = false) LoyaltyMemberEditDTO edit) {
		try {
			return ResponseEntity.ok(receiver.editMember(store, cardNumber, edit));
		} catch (HoLoyaltyReceiver.NoRightException e) {
			return error(HttpStatus.FORBIDDEN, e.getMessage());
		} catch (NoSuchElementException e) {
			return error(HttpStatus.NOT_FOUND, e.getMessage());
		} catch (IllegalArgumentException e) {
			return error(HttpStatus.BAD_REQUEST, e.getMessage());
		} catch (IllegalStateException e) {
			return error(HttpStatus.CONFLICT, e.getMessage());
		}
	}

	private static ResponseEntity<?> error(HttpStatus status, String message) {
		return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
	}
}
