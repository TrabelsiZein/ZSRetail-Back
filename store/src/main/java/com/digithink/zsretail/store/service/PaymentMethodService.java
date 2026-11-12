package com.digithink.zsretail.store.service;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodBehaviour;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.core.repository._BaseRepository;
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

	/** A refusal: its reason (the screen's text) and the message in English. */
	public static class Refusal extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final String reason;

		public Refusal(String reason, String message) {
			super(message);
			this.reason = reason;
		}

		public String getReason() {
			return reason;
		}
	}

	@Autowired
	private PaymentMethodRepository paymentMethodRepository;

	@Autowired
	private JdbcTemplate jdbc;

	@Override
	protected _BaseRepository<PaymentMethod, Long> getRepository() {
		return paymentMethodRepository;
	}

	/** A new method from the admin: no type, behaviour AMOUNT, the fields checked. Throws a Refusal. */
	public PaymentMethod prepareCreate(PaymentMethod method) {
		method.setId(null);
		method.setType(null);
		method.setBehaviour(PaymentMethodBehaviour.AMOUNT);
		method.setCode(trim(method.getCode()));
		method.setName(trim(method.getName()));
		if (method.getDisplayOrder() == null) {
			method.setDisplayOrder(0);
		}
		if (method.getActive() == null) {
			method.setActive(true);
		}
		check(method, null);
		return method;
	}

	/**
	 * The stored method with what the admin may change from {@code incoming}; type and behaviour kept, the switches only
	 * on a method without type. Throws a Refusal.
	 */
	public PaymentMethod prepareUpdate(PaymentMethod stored, PaymentMethod incoming) {
		stored.setCode(trim(incoming.getCode()));
		stored.setName(trim(incoming.getName()));
		stored.setDescription(incoming.getDescription());
		stored.setDisplayOrder(incoming.getDisplayOrder() != null ? incoming.getDisplayOrder() : 0);
		if (incoming.getActive() != null) {
			stored.setActive(incoming.getActive());
		}
		stored.setTitleNumberLength(incoming.getTitleNumberLength());
		if (stored.getType() == null) {
			stored.setRequireTitleNumber(Boolean.TRUE.equals(incoming.getRequireTitleNumber()));
			stored.setAskTitleNumber(Boolean.TRUE.equals(incoming.getAskTitleNumber()));
			stored.setRequireDueDate(Boolean.TRUE.equals(incoming.getRequireDueDate()));
			stored.setRequireDrawerName(Boolean.TRUE.equals(incoming.getRequireDrawerName()));
			stored.setRequireIssuingBank(Boolean.TRUE.equals(incoming.getRequireIssuingBank()));
		}
		check(stored, stored.getId());
		return stored;
	}

	/** Why the method may not be deleted (switch it off instead); empty when it may. */
	public Optional<Refusal> deletionRefusal(PaymentMethod method) {
		if (method.getType() != null) {
			return Optional.of(new Refusal("FIXED_TYPE", "The payment method " + method.getCode()
					+ " is one of the fixed methods: it cannot be deleted, switch it off instead."));
		}
		Long id = method.getId();
		Integer uses = jdbc.queryForObject(USES, Integer.class, id, id, id, id);
		if (uses != null && uses > 0) {
			return Optional.of(new Refusal("IN_USE", "The payment method " + method.getCode()
					+ " is used by payments or session counts: it cannot be deleted, switch it off instead."));
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
