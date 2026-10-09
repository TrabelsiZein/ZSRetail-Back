package com.digithink.zsretail.headoffice.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeErpCatalogue;

import lombok.extern.log4j.Log4j2;

/**
 * ERP catalogue, step 2: a head office whose catalogue only comes from the ERP refuses with 403 {"error"} the writes
 * (POST, PUT, PATCH, DELETE) on the family, sub-family and barcode endpoints that isCatalogueFromErp() leaves open. The
 * bean exists only there ({@link ConditionalOnHeadOfficeErpCatalogue}): on a store and on every other head office the
 * shared controllers answer exactly as before. Nothing in those controllers changes.
 * <p>
 * Left to the controllers' own refusal (isCatalogueFromErp, their own message): POST /item, PUT and DELETE /item/{id},
 * POST /item/quick-product, POST /item-family, POST /item-sub-family, POST /admin/import/preview and /execute. Open on
 * purpose: the packs (/item/{id}/package-flag, /item-composition), the item images (/item-image) and the supply prices.
 * A GET always passes, except on the price lists. Release 2.2: the price lists are not used here: every request on
 * /admin/headoffice/price-lists and the PUT of a store's selling price list are refused ({@link #PRICE_LIST_REFUSAL});
 * the existing lists and store settings are kept as they are. The ERP import writes through the repositories and never
 * reaches this filter.
 * <p>
 * A servlet filter at the lowest precedence, after the security chain, like HeadOfficeWithoutStockFilter.
 */
@Component
@ConditionalOnHeadOfficeErpCatalogue
@Log4j2
public class HeadOfficeErpCatalogueFilter extends OncePerRequestFilter {

	static final String REFUSAL = "The catalogue of this head office comes from the ERP: families, sub-families, items"
			+ " and barcodes are read-only here.";
	static final String REFUSAL_BODY = "{\"error\":\"" + REFUSAL + "\"}";

	/** Release 2.2: the price lists, not used on this head office. */
	public static final String PRICE_LIST_REFUSAL = "The catalogue of this head office comes from the ERP: price lists are not used"
			+ " here (the prices come from the ERP, per point de stock).";
	public static final String PRICE_LIST_REFUSAL_BODY = "{\"error\":\"" + PRICE_LIST_REFUSAL + "\"}";

	/** Release 2.2: every request on the path and under it. */
	static final String PRICE_LISTS = "/admin/headoffice/price-lists";

	/** Release 2.2: PUT of a store's selling price list. */
	static final java.util.regex.Pattern SELLING_PRICE_LIST = java.util.regex.Pattern
			.compile("/admin/headoffice/stores/[^/]+/selling-price-list");

	/** The methods that write. */
	static final List<String> WRITES = Collections.unmodifiableList(Arrays.asList("POST", "PUT", "PATCH", "DELETE"));

	/** Every write on the path and under it. */
	static final List<String> BARCODE_PREFIXES = Collections.singletonList("/item-barcode");

	/**
	 * Every write under the path (/{id}...). A write on the path itself is a creation: POST is refused there by the
	 * controller with its own message, the other methods by this filter.
	 */
	static final List<String> FAMILY_PREFIXES = Collections
			.unmodifiableList(Arrays.asList("/item-family", "/item-sub-family"));

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !refused(request.getMethod(), HeadOfficeWithoutStockFilter.path(request));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws IOException {
		log.info("Head office with the catalogue from the ERP: {} {} refused", request.getMethod(),
				HeadOfficeWithoutStockFilter.path(request));
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType("application/json;charset=UTF-8");
		response.getWriter().write(priceList(request.getMethod(), HeadOfficeWithoutStockFilter.path(request)) ? PRICE_LIST_REFUSAL_BODY
				: REFUSAL_BODY);
	}

	/** Release 2.2: a request on the price lists (any method) or the PUT of a store's selling price list. */
	static boolean priceList(String method, String path) {
		if (path == null) {
			return false;
		}
		return path.equals(PRICE_LISTS) || path.startsWith(PRICE_LISTS + "/")
				|| ("PUT".equalsIgnoreCase(method) && SELLING_PRICE_LIST.matcher(path).matches());
	}

	static boolean refused(String method, String path) {
		if (priceList(method, path)) {
			return true;
		}
		if (method == null || !WRITES.contains(method.toUpperCase())) {
			return false;
		}
		for (String prefix : BARCODE_PREFIXES) {
			if (path.equals(prefix) || path.startsWith(prefix + "/")) {
				return true;
			}
		}
		for (String prefix : FAMILY_PREFIXES) {
			if (path.startsWith(prefix + "/") || (path.equals(prefix) && !"POST".equalsIgnoreCase(method))) {
				return true;
			}
		}
		return false;
	}
}
