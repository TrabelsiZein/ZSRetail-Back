package com.digithink.zsretail.store.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.enumeration.ConfigType;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodBehaviour;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.repository.AcceptedTicketRepository;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.core.service._BaseService;

/**
 * 3.2.0, step 2: the rules in front of the generic CRUD (PaymentMethodAPI). A method the admin creates has no type and
 * the behaviour AMOUNT; an update never changes the type or the behaviour, and the switches (N° titre off / optional /
 * required, due date, drawer name, bank) only on a method without type (cash, cheque... keep theirs: the code and the ERP
 * rely on them); the N° titre length is editable on every method. The code is unique. A method with a type, or used by a
 * payment, a payment change or a session count, is never deleted: it is switched off.
 */
@Service
public class PaymentMethodService extends _BaseService<PaymentMethod, Long> {

	/** The right to create, change or delete a payment method (reading stays open: the till needs the list). */
	public static final String WRITE_PERMISSION = "write:admin-payment-methods";

	static final int MAX_TITLE_NUMBER_LENGTH = 255;

	/** The rows that point to a method: its payments, the payment changes from or to it, the session count lines. */
	static final String USES = "SELECT (SELECT COUNT(*) FROM payment WHERE payment_method_id = ?)"
			+ " + (SELECT COUNT(*) FROM payment_change_log WHERE old_payment_method_id = ? OR new_payment_method_id = ?)"
			+ " + (SELECT COUNT(*) FROM session_cash_count WHERE payment_method_id = ?)";

	/** A refusal: its reason (the screen's text), the message in English, and (step 3b) details for the screen's text. */
	public static class Refusal extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final String reason;
		private final Map<String, String> details;

		public Refusal(String reason, String message) {
			this(reason, message, Collections.emptyMap());
		}

		public Refusal(String reason, String message, Map<String, String> details) {
			super(message);
			this.reason = reason;
			this.details = details;
		}

		public String getReason() {
			return reason;
		}

		public Map<String, String> getDetails() {
			return details;
		}
	}

	/**
	 * 3.2.0, step 3b: the settings of General setup shown on a method's card (the values stay in general_setup, read
	 * where they always were), by the method's type: the cash method's ceiling, the cash added on the payment page and
	 * the cash check at closing; the return voucher's validity.
	 */
	static final Map<PaymentMethodType, List<String>> SETTINGS;
	static {
		Map<PaymentMethodType, List<String>> settings = new EnumMap<>(PaymentMethodType.class);
		settings.put(PaymentMethodType.CLIENT_ESPECES,
				Arrays.asList("PLAFOND_ESPECE", "AUTO_ADD_CASH_PAYMENT_ON_PAYMENT_PAGE", "ENABLE_CASH_DISCREPANCY_CHECK"));
		settings.put(PaymentMethodType.RETURN_VOUCHER, Arrays.asList("RETURN_VOUCHER_VALIDITY_DAYS"));
		SETTINGS = Collections.unmodifiableMap(settings);
	}

	@Autowired
	private PaymentMethodRepository paymentMethodRepository;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private AcceptedTicketRepository acceptedTicketRepository;

	@Autowired
	private GeneralSetupService generalSetupService;

	@Override
	protected _BaseRepository<PaymentMethod, Long> getRepository() {
		return paymentMethodRepository;
	}

	/**
	 * A new method from the admin: no type, the behaviour asked (AMOUNT by default; step 3: SCANNED_TICKETS, its N° titre
	 * and other details then off: the barcode is its title number), the fields checked. Throws a Refusal.
	 */
	public PaymentMethod prepareCreate(PaymentMethod method) {
		method.setId(null);
		method.setType(null);
		method.setBehaviour(method.getBehaviour() == PaymentMethodBehaviour.SCANNED_TICKETS
				? PaymentMethodBehaviour.SCANNED_TICKETS : PaymentMethodBehaviour.AMOUNT);
		clearDetailsOfScannedTickets(method);
		method.setCode(trim(method.getCode()));
		method.setName(trim(method.getName()));
		if (method.getDisplayOrder() == null) {
			method.setDisplayOrder(0);
		}
		if (method.getActive() == null) {
			method.setActive(true);
		}
		if (method.getSendToErp() == null) {
			method.setSendToErp(true);
		}
		check(method, null);
		return method;
	}

	/**
	 * The stored method with what the admin may change from {@code incoming}; the type kept, the switches only on a method
	 * without type. Step 3: the behaviour changes only on a method without type that has no payment (nor payment change
	 * or session count) and no accepted ticket (BEHAVIOUR_LOCKED). Throws a Refusal.
	 */
	public PaymentMethod prepareUpdate(PaymentMethod stored, PaymentMethod incoming) {
		PaymentMethodBehaviour current = behaviour(stored);
		PaymentMethodBehaviour wanted = incoming.getBehaviour() != null ? incoming.getBehaviour() : current;
		if (wanted != current) {
			if (behaviourLocked(stored)) {
				throw new Refusal("BEHAVIOUR_LOCKED", "The behaviour of the payment method " + stored.getCode()
						+ " changes only on a method without type, before any payment and any accepted ticket.");
			}
			stored.setBehaviour(wanted);
		}
		stored.setCode(trim(incoming.getCode()));
		stored.setName(trim(incoming.getName()));
		stored.setDescription(incoming.getDescription());
		stored.setDisplayOrder(incoming.getDisplayOrder() != null ? incoming.getDisplayOrder() : 0);
		if (incoming.getActive() != null) {
			stored.setActive(incoming.getActive());
		}
		stored.setTitleNumberLength(incoming.getTitleNumberLength());
		// Step 3b: "send to the ERP", for the sales made after; never on the return voucher
		if (incoming.getSendToErp() != null) {
			stored.setSendToErp(incoming.getSendToErp());
		}
		if (stored.getType() == PaymentMethodType.RETURN_VOUCHER) {
			stored.setSendToErp(false);
		}
		if (stored.getType() == null) {
			stored.setRequireTitleNumber(Boolean.TRUE.equals(incoming.getRequireTitleNumber()));
			stored.setAskTitleNumber(Boolean.TRUE.equals(incoming.getAskTitleNumber()));
			stored.setRequireDueDate(Boolean.TRUE.equals(incoming.getRequireDueDate()));
			stored.setRequireDrawerName(Boolean.TRUE.equals(incoming.getRequireDrawerName()));
			stored.setRequireIssuingBank(Boolean.TRUE.equals(incoming.getRequireIssuingBank()));
		}
		clearDetailsOfScannedTickets(stored);
		check(stored, stored.getId());
		return stored;
	}

	/** Step 3: the behaviour cannot change: a fixed type, or a payment (change, session count) or an accepted ticket. */
	public boolean behaviourLocked(PaymentMethod method) {
		return method.getType() != null || uses(method) > 0 || acceptedTicketRepository.countByPaymentMethod(method) > 0;
	}

	/** Step 3b: the General setup rows shown on the method's card (none for most methods), in their order. */
	public List<GeneralSetup> settingsOf(PaymentMethod method) {
		List<GeneralSetup> rows = new ArrayList<>();
		for (String code : SETTINGS.getOrDefault(method.getType(), Collections.emptyList())) {
			GeneralSetup row = generalSetupService.findByCode(code);
			if (row != null) {
				rows.add(row);
			}
		}
		return rows;
	}

	/**
	 * Step 3b: the card's settings saved ({code: value}), as General setup saves them (GeneralSetupService.updateFromAdmin:
	 * a read-only row refused, the value checks, the change log); only the keys of the method's type, checked by their
	 * type first: BOOLEAN true / false, NUMBER empty or a whole number from 0, PLAFOND_ESPECE empty or a number from 0.
	 * Nothing is written when one is refused (SETTING_UNKNOWN, SETTING_INVALID).
	 */
	public void saveSettings(PaymentMethod method, Map<String, String> values) throws Exception {
		List<String> allowed = SETTINGS.getOrDefault(method.getType(), Collections.emptyList());
		Map<GeneralSetup, String> changes = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : values.entrySet()) {
			if (!allowed.contains(entry.getKey())) {
				throw new Refusal("SETTING_UNKNOWN", "The setting " + entry.getKey() + " is not one of the payment method "
						+ method.getCode() + ".");
			}
			GeneralSetup row = generalSetupService.findByCode(entry.getKey());
			if (row == null) {
				continue;
			}
			String value = entry.getValue() == null ? "" : entry.getValue().trim();
			if (!validSetting(row, value)) {
				throw new Refusal("SETTING_INVALID", "The value of " + row.getCode() + " is not valid: " + value,
						Collections.singletonMap("code", row.getCode()));
			}
			changes.put(row, value);
		}
		for (Map.Entry<GeneralSetup, String> change : changes.entrySet()) {
			GeneralSetup row = change.getKey();
			if (change.getValue().equals(row.getValeur())) {
				continue;
			}
			GeneralSetup updated = new GeneralSetup();
			updated.setValeur(change.getValue());
			updated.setDescription(row.getDescription());
			generalSetupService.updateFromAdmin(row.getId(), updated, "Payment method " + method.getCode());
		}
	}

	static boolean validSetting(GeneralSetup row, String value) {
		if (row.getConfigType() == ConfigType.BOOLEAN) {
			return "true".equals(value) || "false".equals(value);
		}
		if (row.getConfigType() == ConfigType.NUMBER) {
			return value.isEmpty() || value.matches("\\d{1,9}");
		}
		if ("PLAFOND_ESPECE".equals(row.getCode())) {
			return value.isEmpty() || value.matches("\\d{1,12}(\\.\\d{1,3})?");
		}
		return true;
	}

	/** A method of behaviour null is AMOUNT (every method of before). */
	static PaymentMethodBehaviour behaviour(PaymentMethod method) {
		return method.getBehaviour() != null ? method.getBehaviour() : PaymentMethodBehaviour.AMOUNT;
	}

	/** A scanned-tickets method asks no N° titre, due date, drawer or bank: the barcode is the title number. */
	private static void clearDetailsOfScannedTickets(PaymentMethod method) {
		if (method.getBehaviour() == PaymentMethodBehaviour.SCANNED_TICKETS) {
			method.setRequireTitleNumber(false);
			method.setAskTitleNumber(false);
			method.setTitleNumberLength(null);
			method.setRequireDueDate(false);
			method.setRequireDrawerName(false);
			method.setRequireIssuingBank(false);
		}
	}

	private int uses(PaymentMethod method) {
		Long id = method.getId();
		Integer uses = jdbc.queryForObject(USES, Integer.class, id, id, id, id);
		return uses != null ? uses : 0;
	}

	/** Why the method may not be deleted (switch it off instead); empty when it may. */
	public Optional<Refusal> deletionRefusal(PaymentMethod method) {
		if (method.getType() != null) {
			return Optional.of(new Refusal("FIXED_TYPE", "The payment method " + method.getCode()
					+ " is one of the fixed methods: it cannot be deleted, switch it off instead."));
		}
		if (uses(method) > 0) {
			return Optional.of(new Refusal("IN_USE", "The payment method " + method.getCode()
					+ " is used by payments or session counts: it cannot be deleted, switch it off instead."));
		}
		if (acceptedTicketRepository.countByPaymentMethod(method) > 0) {
			return Optional.of(new Refusal("HAS_TICKETS", "The payment method " + method.getCode()
					+ " has accepted tickets: delete them first, or switch the method off."));
		}
		return Optional.empty();
	}

	private void check(PaymentMethod method, Long id) {
		if (method.getCode() == null || method.getCode().isEmpty()) {
			throw new Refusal("CODE_REQUIRED", "The code is required.");
		}
		if (method.getName() == null || method.getName().isEmpty()) {
			throw new Refusal("NAME_REQUIRED", "The name is required.");
		}
		Optional<PaymentMethod> sameCode = paymentMethodRepository.findByCode(method.getCode());
		if (sameCode.isPresent() && !sameCode.get().getId().equals(id)) {
			throw new Refusal("CODE_TAKEN", "The code " + method.getCode() + " is already used by the payment method "
					+ sameCode.get().getName() + ".");
		}
		Integer length = method.getTitleNumberLength();
		if (length != null && (length < 1 || length > MAX_TITLE_NUMBER_LENGTH)) {
			throw new Refusal("LENGTH_INVALID",
					"The N° titre length is between 1 and " + MAX_TITLE_NUMBER_LENGTH + ", or empty for any length.");
		}
	}

	private static String trim(String value) {
		return value == null ? null : value.trim();
	}
}
