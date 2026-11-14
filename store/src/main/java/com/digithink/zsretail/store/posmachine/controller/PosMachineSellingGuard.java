package com.digithink.zsretail.store.posmachine.controller;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.controller.ReturnHeaderAPI;
import com.digithink.zsretail.store.controller.SalesHeaderAPI;
import com.digithink.zsretail.store.cashmovement.controller.CashMovementAPI;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.posmachine.service.SessionPosMachineService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.log4j.Log4j2;

/**
 * 3.2.0, POS machines step 2, part B, rule 3: when the drawer belongs to the POS machine, a user whose open session has
 * a POS machine sells, puts a sale on hold and returns only from that machine's address. One check in front of the
 * selling endpoints (registered on /sales-header/**, /return-header/** and, step 5, /cash-movement/** by PosMachineWebConfig), the sale and return
 * code untouched: a request from another address is answered 409 {"error", "reason": "OTHER_POS_MACHINE", "code",
 * "params": {"name"}, "posMachineName"} before the controller runs. Never restricted: the drawer of the cashier, a user
 * without an open session (the endpoint refuses as before), a session without a POS machine, every endpoint not in
 * {@link #RESTRICTED} (the closing and the verification are in CashierSessionAPI, never here). Every mapped method of the
 * two controllers is in {@link #RESTRICTED} or {@link #NOT_RESTRICTED}: PosMachineSellingGuardTest fails on a method in
 * neither, so a new endpoint is classed when it is written. docs/modules/pos-machines.md.
 */
@Component
@Log4j2
public class PosMachineSellingGuard implements HandlerInterceptor {

	/** The till's actions on a sale or a return: refused from another POS machine than the session's. */
	public static final Map<Class<?>, Set<String>> RESTRICTED = classes(
			SalesHeaderAPI.class, names("processSale", "completePendingSale", "splitAndPay", // selling
					"savePendingSale", "updatePendingSale", "cancelPendingSale", "transferTable"), // a sale on hold
			ReturnHeaderAPI.class, names("processReturn"), // a return
			CashMovementAPI.class, names("record")); // step 5: cash out of / into the drawer

	/** The other mapped methods (the reads aside): after the sale, or the generic CRUD the till does not use. */
	public static final Map<Class<?>, Set<String>> NOT_RESTRICTED = classes(
			SalesHeaderAPI.class, names("changePaymentMethod", "prepareInvoice", "create", "update", "deleteById"),
			ReturnHeaderAPI.class, names("create", "update", "deleteById"),
			CashMovementAPI.class, names());

	private final SessionPosMachineService sessionMachines;
	private final CurrentUserProvider currentUserProvider;
	private final ObjectMapper json = new ObjectMapper();

	public PosMachineSellingGuard(SessionPosMachineService sessionMachines, CurrentUserProvider currentUserProvider) {
		this.sessionMachines = sessionMachines;
		this.currentUserProvider = currentUserProvider;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		if (!restricted(handler)) {
			return true;
		}
		UserAccount user;
		try {
			user = currentUserProvider.getCurrentUser();
		} catch (RuntimeException e) {
			return true; // no user read: the endpoint answers as before
		}
		Optional<PosMachine> sessionMachine = sessionMachines.otherMachine(user, request.getRemoteAddr());
		if (!sessionMachine.isPresent()) {
			return true;
		}
		String name = sessionMachine.get().getName();
		log.info("Refused from {}: the session of {} is on the POS machine '{}' ({} {})", request.getRemoteAddr(),
				user.getUsername(), name, request.getMethod(), request.getRequestURI());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", otherMachineMessage(name));
		body.put("reason", OTHER_POS_MACHINE);
		body.put("code", OTHER_POS_MACHINE);
		body.put("params", Collections.singletonMap("name", name));
		body.put("posMachineName", name);
		response.setStatus(HttpStatus.CONFLICT.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		json.writeValue(response.getWriter(), body);
		return false;
	}

	/** The code of the refusal. */
	public static final String OTHER_POS_MACHINE = "OTHER_POS_MACHINE";

	/** The English message of the refusal (the front shows serverErrors.OTHER_POS_MACHINE). */
	public static String otherMachineMessage(String name) {
		return "Your session is open on the POS machine '" + name + "': selling, putting a sale on hold, returns and cash"
				+ " movements are only possible there. Go back to '" + name + "', or close the session.";
	}

	/** True for a method of {@link #RESTRICTED}. */
	static boolean restricted(Object handler) {
		if (!(handler instanceof HandlerMethod)) {
			return false;
		}
		HandlerMethod method = (HandlerMethod) handler;
		Set<String> names = RESTRICTED.get(method.getBeanType());
		return names != null && names.contains(method.getMethod().getName());
	}

	private static Set<String> names(String... names) {
		return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(names)));
	}

	/** Pairs of a controller and its method names. */
	@SuppressWarnings("unchecked")
	private static Map<Class<?>, Set<String>> classes(Object... controllersAndNames) {
		Map<Class<?>, Set<String>> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < controllersAndNames.length; i += 2) {
			map.put((Class<?>) controllersAndNames[i], (Set<String>) controllersAndNames[i + 1]);
		}
		return Collections.unmodifiableMap(map);
	}
}
