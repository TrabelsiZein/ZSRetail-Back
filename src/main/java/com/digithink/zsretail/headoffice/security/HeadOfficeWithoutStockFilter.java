package com.digithink.zsretail.headoffice.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeWithoutStock;

import lombok.extern.log4j.Log4j2;

/**
 * A head office that keeps no stock and makes no purchases (headoffice.stock.enabled=false) refuses the purchase,
 * vendor, purchase invoice, stock report and stock adjustment endpoints with 403 {"error"}, whatever the method. The
 * bean exists only there ({@link ConditionalOnHeadOfficeWithoutStock}): on a store, and on a head office that keeps its
 * stock, the shared controllers answer exactly as before. Nothing in those controllers changes; their own gates
 * (isSupplyFromErp...) still apply on a head office that keeps its stock.
 * <p>
 * A servlet filter at the lowest precedence, after the security chain: a request without a valid token is refused there
 * as before, a preflight (OPTIONS) is answered there, and the CORS headers are already on the response.
 */
@Component
@ConditionalOnHeadOfficeWithoutStock
@Log4j2
public class HeadOfficeWithoutStockFilter extends OncePerRequestFilter {

	static final String REFUSAL = "This head office keeps no stock and makes no purchases (headoffice.stock.enabled=false).";
	static final String REFUSAL_BODY = "{\"error\":\"" + REFUSAL + "\"}";

	/** The path and everything under it: /purchase-header, /vendor (generic CRUD included), the purchase invoices. */
	static final List<String> PREFIXES = Collections
			.unmodifiableList(Arrays.asList("/purchase-header", "/vendor", "/admin/purchase-invoices"));

	/** These exact paths: the stock and purchase reports. */
	static final List<String> PATHS = Collections
			.unmodifiableList(Arrays.asList("/report/stock", "/report/stock-movements", "/report/purchases"));

	/** POST /item/{id}/adjust-stock. */
	static final Pattern ADJUST_STOCK = Pattern.compile("/item/[^/]+/adjust-stock");

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !refused(path(request));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws IOException {
		log.info("Head office without stock: {} {} refused", request.getMethod(), path(request));
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType("application/json;charset=UTF-8");
		response.getWriter().write(REFUSAL_BODY);
	}

	/** The path inside the application (decoded, without the context path), without a trailing slash. */
	static String path(HttpServletRequest request) {
		String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
		return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
	}

	static boolean refused(String path) {
		for (String prefix : PREFIXES) {
			if (path.equals(prefix) || path.startsWith(prefix + "/")) {
				return true;
			}
		}
		return PATHS.contains(path) || ADJUST_STOCK.matcher(path).matches();
	}
}
