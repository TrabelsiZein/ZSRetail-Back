package com.digithink.zsretail.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.model.ReturnVoucher;
import com.digithink.zsretail.service.ReturnVoucherService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("return-voucher")
@Log4j2
public class ReturnVoucherAPI extends _BaseController<ReturnVoucher, Long, ReturnVoucherService> {

//	@Autowired
//	private CurrentUserProvider currentUserProvider;

	/**
	 * Get voucher details by voucher number
	 */
	@GetMapping("/validate")
	public ResponseEntity<?> validateVoucher(@RequestParam String voucherNumber) {
		try {
			log.info("ReturnVoucherAPI::validateVoucher: voucherNumber=" + voucherNumber);

			ReturnVoucher voucher = service.findByVoucherNumber(voucherNumber)
					.orElseThrow(() -> new IllegalArgumentException("Voucher not found: " + voucherNumber));

			boolean isValid = service.isVoucherValid(voucher);
			double remainingAmount = service.getRemainingAmount(voucher);

			java.util.Map<String, Object> response = new java.util.HashMap<>();
			response.put("voucher", voucher);
			response.put("isValid", isValid);
			response.put("remainingAmount", remainingAmount);

			return ResponseEntity.ok(response);

		} catch (IllegalArgumentException e) {
			log.error("ReturnVoucherAPI::validateVoucher:validation error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("ReturnVoucherAPI::validateVoucher:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}
}
