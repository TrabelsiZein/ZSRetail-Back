package com.digithink.zsretail.headoffice.controller;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.headoffice.repository.HoNetworkStockQuery;
import com.digithink.zsretail.headoffice.service.HoNetworkStockService;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 7A.5: the stock of every store next to the head office's own stock (JWT, like the other admin
 * APIs). Head office without an ERP only: elsewhere these URLs answer 404. Errors {"error"}: 400 for an unknown store or a
 * page that cannot be read. See docs/modules/head-office.md, "Stock of the stores".
 */
@RestController
@RequestMapping("admin/headoffice/stock")
@ConditionalOnInstallation(Installation.HEAD_OFFICE_WITHOUT_ERP)
@Log4j2
public class HoNetworkStockAPI {

	private final HoNetworkStockService service;

	public HoNetworkStockAPI(HoNetworkStockService service) {
		this.service = service;
	}

	/**
	 * The head office items with their stock and each store's: {stores, content, totalElements, ...}. 3.1.0: familyId,
	 * subFamilyId, divisionCode, categoryCode, sectionCode; stock ALL (default), IN_STOCK, ZERO, BELOW_ZERO on the columns
	 * shown; belowZero=true is stock=BELOW_ZERO (when stock is absent). Step 2: sort code (default), name, headOffice,
	 * store-&lt;id&gt;, total; dir asc (default) or desc.
	 */
	@GetMapping
	public ResponseEntity<?> page(@RequestParam(required = false) Long storeId,
			@RequestParam(required = false) String search, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean belowZero,
			@RequestParam(required = false) String stock, @RequestParam(required = false) Long familyId,
			@RequestParam(required = false) Long subFamilyId, @RequestParam(required = false) String divisionCode,
			@RequestParam(required = false) String categoryCode, @RequestParam(required = false) String sectionCode,
			@RequestParam(required = false) String sort, @RequestParam(required = false) String dir) {
		HoNetworkStockQuery.Criteria filters = new HoNetworkStockQuery.Criteria();
		filters.setStock(stockOf(stock, belowZero));
		filters.setFamilyId(familyId);
		filters.setSubFamilyId(subFamilyId);
		filters.setDivisionCode(divisionCode);
		filters.setCategoryCode(categoryCode);
		filters.setSectionCode(sectionCode);
		return answer(() -> ResponseEntity.ok(service.page(storeId, search, page, size, filters, sort, descending(dir))));
	}

	/**
	 * The stores' own items (not from the head office) with their stock. Step 2: stock ALL (default), IN_STOCK, ZERO,
	 * BELOW_ZERO on the row's quantity (belowZero=true as BELOW_ZERO); sort store (default), code, name, quantity,
	 * storeTime, receivedAt; dir asc or desc.
	 */
	@GetMapping("/own")
	public ResponseEntity<?> own(@RequestParam(required = false) Long storeId,
			@RequestParam(required = false) String search, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean belowZero,
			@RequestParam(required = false) String stock, @RequestParam(required = false) String sort,
			@RequestParam(required = false) String dir) {
		return answer(() -> ResponseEntity.ok(service.ownItems(storeId, search, page, size, stockOf(stock, belowZero), sort,
				descending(dir))));
	}

	private static String stockOf(String stock, Boolean belowZero) {
		return stock != null && !stock.trim().isEmpty() ? stock
				: Boolean.TRUE.equals(belowZero) ? HoNetworkStockQuery.BELOW_ZERO : null;
	}

	private static boolean descending(String dir) {
		return "desc".equalsIgnoreCase(dir == null ? null : dir.trim());
	}

	private ResponseEntity<?> answer(Supplier<ResponseEntity<?>> call) {
		try {
			return call.get();
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(error(e.getMessage()));
		} catch (RuntimeException e) {
			log.error("HoNetworkStockAPI: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(String.valueOf(e.getMessage())));
		}
	}

	private static Map<String, String> error(String message) {
		return Collections.singletonMap("error", message);
	}
}
