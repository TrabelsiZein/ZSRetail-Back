package com.digithink.zsretail.headoffice.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberCopyDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberEditDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberResultDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMovementCopyDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyPhoneCheckDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyPointsAdjustDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyResultDTO;
import com.digithink.zsretail.headoffice.model.HoLoyaltyAlias;
import com.digithink.zsretail.headoffice.model.HoLoyaltyMovement;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoLoyaltyAliasRepository;
import com.digithink.zsretail.headoffice.repository.HoLoyaltyMovementRepository;
import com.digithink.zsretail.holink.service.SalesCopyFinder;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.LoyaltyTransaction;
import com.digithink.zsretail.model.enumeration.LoyaltyTransactionType;
import com.digithink.zsretail.repository.CustomerRepository;
import com.digithink.zsretail.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.repository.LoyaltyProgramRepository;
import com.digithink.zsretail.repository.LoyaltyTransactionRepository;
import com.digithink.zsretail.repository.MemberFunctionRepository;
import com.digithink.zsretail.service.LoyaltyLedger;
import com.digithink.zsretail.service.LoyaltyService;
import com.digithink.zsretail.service.MemberFunctionCodes;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, step 4: what the stores send to the shared loyalty register, and their live questions.
 * <ul>
 * <li>Members up: a member enrolled at a store is created with the store's card number (balance 0: its points arrive
 * as movements). When the phone already has a card, the store's card becomes an inactive alias of that member
 * (ho_loyalty_alias) and the answer names the surviving card.</li>
 * <li>Movements up: each is applied once (store + the store's key, ho_loyalty_movement), in the order sent, to the
 * member of its card or of the alias. The balance never goes below zero; what could not be removed is the overspend.
 * Each writes a loyalty_transaction row, the ledger the head office pages show.</li>
 * <li>Phone check, and a member edit from a store that has the right (canEditMembers on its Stores row).</li>
 * </ul>
 * Each record is applied in its own transaction, one at a time (the phone checks and the balances are read then
 * written): a bad record is rejected with its reason and the next ones go on. Every change is recorded for every store
 * (copies down). Head office only. See docs/modules/head-office.md, "Shared loyalty".
 */
@Service
@ConditionalOnHeadOffice
@Log4j2
public class HoLoyaltyReceiver {

	static final int TIMEOUT_SECONDS = 15;
	static final int MESSAGE_LENGTH = 500;
	static final int DESCRIPTION_LENGTH = 500;

	/** created_by / updated_by of what a store wrote: STORE:&lt;code&gt;. */
	static final String STORE_USER_PREFIX = "STORE:";

	static final String ALREADY_APPLIED = "already applied";
	static final String NO_EDIT_RIGHT = "This store may not change loyalty members: the head office gives the right on its Stores page.";
	static final String NO_ADJUST_RIGHT = "this store may not adjust points (right off on the head office Stores page)";
	static final String NO_ADJUST_POINTS_RIGHT = "This store may not adjust points: the head office gives the right on its Stores page.";

	private final LoyaltyMemberRepository members;
	private final LoyaltyProgramRepository programs;
	private final LoyaltyTransactionRepository transactions;
	private final MemberFunctionRepository memberFunctions;
	private final CustomerRepository customers;
	private final HoLoyaltyAliasRepository aliases;
	private final HoLoyaltyMovementRepository movements;
	private final HoLoyaltyService register;
	private final TransactionOperations writes;

	/** Step 5: the head office's own LoyaltyService, for a store's point adjustment (looked up at the call). */
	private final Supplier<LoyaltyService> loyaltyService;

	/** One record at a time: the phone checks and the balances are read then written. */
	private final Object lock = new Object();

	@Autowired
	public HoLoyaltyReceiver(LoyaltyMemberRepository members, LoyaltyProgramRepository programs,
			LoyaltyTransactionRepository transactions, MemberFunctionRepository memberFunctions,
			CustomerRepository customers, HoLoyaltyAliasRepository aliases, HoLoyaltyMovementRepository movements,
			HoLoyaltyService register, ObjectProvider<LoyaltyService> loyaltyService,
			PlatformTransactionManager transactionManager) {
		this(members, programs, transactions, memberFunctions, customers, aliases, movements, register,
				(Supplier<LoyaltyService>) loyaltyService::getObject, timed(transactionManager));
	}

	/** With given collaborators and transactions: used by the tests. */
	public HoLoyaltyReceiver(LoyaltyMemberRepository members, LoyaltyProgramRepository programs,
			LoyaltyTransactionRepository transactions, MemberFunctionRepository memberFunctions,
			CustomerRepository customers, HoLoyaltyAliasRepository aliases, HoLoyaltyMovementRepository movements,
			HoLoyaltyService register, Supplier<LoyaltyService> loyaltyService, TransactionOperations writes) {
		this.loyaltyService = loyaltyService;
		this.members = members;
		this.programs = programs;
		this.transactions = transactions;
		this.memberFunctions = memberFunctions;
		this.customers = customers;
		this.aliases = aliases;
		this.movements = movements;
		this.register = register;
		this.writes = writes;
	}

	private static TransactionTemplate timed(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	// ─── Members up ──────────────────────────────────────────────

	/** POST /ho/loyalty/members: one result per member, in batch order. */
	public List<LoyaltyMemberResultDTO> receiveMembers(Store store, List<LoyaltyMemberCopyDTO> copies) {
		List<LoyaltyMemberResultDTO> results = new ArrayList<>();
		if (copies == null) {
			return results;
		}
		for (LoyaltyMemberCopyDTO copy : copies) {
			String card = copy == null ? null : trim(copy.getCardNumber());
			LoyaltyMemberResultDTO result;
			try {
				synchronized (lock) {
					result = writes.execute(status -> receiveMember(store, copy));
				}
			} catch (RuntimeException e) {
				result = LoyaltyMemberResultDTO.rejected(card, cut(SalesCopyFinder.cause(e)));
			}
			if (!result.isAccepted()) {
				log.warn("Head office: loyalty member {} from store '{}' rejected: {}", card, store.getCode(),
						result.getMessage());
			} else if (LoyaltyMemberResultDTO.MERGED.equals(result.getOutcome())) {
				log.info("Head office: loyalty card {} from store '{}' merged into {} (same phone)", card,
						store.getCode(), result.getSurvivingCardNumber());
			}
			results.add(result);
		}
		long accepted = results.stream().filter(LoyaltyMemberResultDTO::isAccepted).count();
		log.info("Head office: {} loyalty members received from store '{}' ({} accepted, {} rejected)", results.size(),
				store.getCode(), accepted, results.size() - accepted);
		return results;
	}

	private LoyaltyMemberResultDTO receiveMember(Store store, LoyaltyMemberCopyDTO copy) {
		if (copy == null) {
			return LoyaltyMemberResultDTO.rejected(null, "empty member");
		}
		String card = trim(copy.getCardNumber());
		if (card == null) {
			return LoyaltyMemberResultDTO.rejected(null, "cardNumber is required");
		}
		if (trim(copy.getFirstName()) == null) {
			return LoyaltyMemberResultDTO.rejected(card, "firstName is required");
		}
		if (trim(copy.getLastName()) == null) {
			return LoyaltyMemberResultDTO.rejected(card, "lastName is required");
		}
		Optional<LoyaltyMember> known = members.findByCardNumber(card);
		if (known.isPresent()) {
			return accepted(card, LoyaltyMemberResultDTO.EXISTS, known.get());
		}
		Optional<HoLoyaltyAlias> alias = aliases.findByCardNumber(card);
		if (alias.isPresent()) {
			return accepted(card, LoyaltyMemberResultDTO.MERGED, members.findById(alias.get().getMemberId())
					.orElseThrow(() -> new IllegalStateException("alias of an unknown member")));
		}
		String phone = LoyaltyService.normalizePhone(copy.getPhone());
		Optional<LoyaltyMember> holder = phone.isEmpty() ? Optional.empty() : phoneHolder(phone, null);
		if (holder.isPresent()) {
			HoLoyaltyAlias created = new HoLoyaltyAlias();
			created.setCardNumber(card);
			created.setMemberId(holder.get().getId());
			created.setStoreId(store.getId());
			created.setCreatedBy(storeUser(store));
			aliases.save(created);
			return accepted(card, LoyaltyMemberResultDTO.MERGED, holder.get());
		}
		LoyaltyMember member = new LoyaltyMember();
		member.setCardNumber(card);
		member.setFirstName(trim(copy.getFirstName()));
		member.setLastName(trim(copy.getLastName()));
		member.setPhone(phone.isEmpty() ? null : phone);
		member.setEmail(copy.getEmail());
		member.setBirthDate(copy.getBirthDate());
		member.setMemberFunction(MemberFunctionCodes.resolve(memberFunctions, copy.getMemberFunctionCode(),
				copy.getMemberFunctionName()));
		member.setCustomer(copy.getCustomerCode() == null ? null
				: customers.findByCustomerCode(copy.getCustomerCode()).orElse(null));
		member.setActive(copy.getActive() == null ? Boolean.TRUE : copy.getActive());
		if (copy.getEnrolledAt() != null) {
			member.setCreatedAt(copy.getEnrolledAt());
		}
		member.setCreatedBy(storeUser(store));
		member.setUpdatedBy(storeUser(store));
		LoyaltyMember saved = members.save(member);
		register.recordMember(saved.getCardNumber());
		return accepted(card, LoyaltyMemberResultDTO.CREATED, saved);
	}

	private static LoyaltyMemberResultDTO accepted(String card, String outcome, LoyaltyMember member) {
		return new LoyaltyMemberResultDTO(card, true, null, outcome,
				LoyaltyMemberResultDTO.MERGED.equals(outcome) ? member.getCardNumber() : null,
				LoyaltyMemberCopyDTO.of(member));
	}

	// ─── Movements up ────────────────────────────────────────────

	/** POST /ho/loyalty/movements: one result per movement (documentNumber = its key), in batch order. */
	public List<SalesCopyResultDTO> receiveMovements(Store store, List<LoyaltyMovementCopyDTO> copies) {
		List<SalesCopyResultDTO> results = new ArrayList<>();
		if (copies == null) {
			return results;
		}
		for (LoyaltyMovementCopyDTO copy : copies) {
			String key = copy == null ? null : trim(copy.getKey());
			SalesCopyResultDTO result;
			try {
				synchronized (lock) {
					result = writes.execute(status -> receiveMovement(store, copy));
				}
			} catch (RuntimeException e) {
				result = new SalesCopyResultDTO(key, false, cut(SalesCopyFinder.cause(e)));
			}
			if (!result.isAccepted()) {
				log.warn("Head office: loyalty movement {} from store '{}' rejected: {}", key, store.getCode(),
						result.getMessage());
			}
			results.add(result);
		}
		long accepted = results.stream().filter(SalesCopyResultDTO::isAccepted).count();
		log.info("Head office: {} loyalty movements received from store '{}' ({} accepted, {} rejected)",
				results.size(), store.getCode(), accepted, results.size() - accepted);
		return results;
	}

	private SalesCopyResultDTO receiveMovement(Store store, LoyaltyMovementCopyDTO copy) {
		if (copy == null) {
			return new SalesCopyResultDTO(null, false, "empty movement");
		}
		String key = trim(copy.getKey());
		if (key == null) {
			return new SalesCopyResultDTO(null, false, "key is required");
		}
		Optional<HoLoyaltyMovement> applied = movements.findByStoreIdAndStoreKey(store.getId(), key);
		if (applied.isPresent()) {
			// Sent again (the answer was lost): nothing applied twice; the store gets the member again at its next pull
			members.findById(applied.get().getMemberId()).ifPresent(m -> register.recordMember(m.getCardNumber()));
			return new SalesCopyResultDTO(key, true, ALREADY_APPLIED);
		}
		String card = trim(copy.getCardNumber());
		if (card == null) {
			return new SalesCopyResultDTO(key, false, "cardNumber is required");
		}
		Optional<LoyaltyMember> found = memberOfCard(card);
		if (!found.isPresent()) {
			return new SalesCopyResultDTO(key, false,
					"unknown card " + card + ": the member has not reached the head office yet");
		}
		LoyaltyTransactionType type;
		try {
			type = LoyaltyTransactionType.valueOf(copy.getType() == null ? "" : copy.getType().trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return new SalesCopyResultDTO(key, false, "unknown type '" + copy.getType() + "'");
		}
		if (copy.getPoints() == null || copy.getPoints() < 0) {
			return new SalesCopyResultDTO(key, false, "points is required (0 or more)");
		}
		String salesNumber = trim(copy.getSalesNumber());
		if (type == LoyaltyTransactionType.ADJUSTED && salesNumber == null
				&& !Boolean.TRUE.equals(store.getCanAdjustPoints())) {
			return new SalesCopyResultDTO(key, false, NO_ADJUST_RIGHT);
		}
		int delta = copy.getDelta() != null ? copy.getDelta()
				: LoyaltyLedger.deltaOf(type, copy.getPoints(), null, null);

		LoyaltyMember member = found.get();
		LoyaltyLedger.State state = new LoyaltyLedger.State(member.getLoyaltyPoints(), member.getTotalPointsEarned(),
				member.getTotalPointsRedeemed());
		LoyaltyLedger.Applied result = LoyaltyLedger.apply(state, type, delta, salesNumber != null);
		member.setLoyaltyPoints(state.getBalance());
		member.setTotalPointsEarned(state.getEarned());
		member.setTotalPointsRedeemed(state.getRedeemed());
		member.setUpdatedBy(storeUser(store));
		members.save(member);

		LoyaltyTransaction tx = new LoyaltyTransaction();
		tx.setLoyaltyMember(member);
		tx.setLoyaltyProgram(copy.getProgramCode() == null ? null
				: programs.findByProgramCode(copy.getProgramCode()).orElse(null));
		tx.setType(type);
		tx.setPoints(copy.getPoints());
		tx.setBalanceBefore(result.getBalanceBefore());
		tx.setBalanceAfter(result.getBalanceAfter());
		tx.setDescription(description(store, copy, card, member, result.getOverspend()));
		tx.setExpiryDate(copy.getExpiryDate());
		tx.setCreatedBy(storeUser(store));
		LoyaltyTransaction savedTx = transactions.save(tx);

		HoLoyaltyMovement movement = new HoLoyaltyMovement();
		movement.setStoreId(store.getId());
		movement.setStoreKey(key);
		movement.setCardNumber(card);
		movement.setMemberId(member.getId());
		movement.setType(type);
		movement.setPoints(copy.getPoints());
		movement.setDelta(delta);
		movement.setOverspendPoints(result.getOverspend());
		movement.setSalesNumber(salesNumber);
		movement.setReturnNumber(trim(copy.getReturnNumber()));
		movement.setProgramCode(trim(copy.getProgramCode()));
		movement.setStoreDate(copy.getDate());
		movement.setTransactionId(savedTx.getId());
		movement.setCreatedBy(storeUser(store));
		movements.save(movement);

		if (result.getOverspend() > 0) {
			log.warn("Head office: loyalty movement {} of store '{}' on card {}: {} points could not be removed"
					+ " (balance reached 0)", key, store.getCode(), member.getCardNumber(), result.getOverspend());
		}
		register.recordMember(member.getCardNumber());
		return new SalesCopyResultDTO(key, true, null);
	}

	/** "Store RS01, sale #X: &lt;the store's description&gt;", and the overspend when there is one. */
	private static String description(Store store, LoyaltyMovementCopyDTO copy, String card, LoyaltyMember member,
			int overspend) {
		StringBuilder text = new StringBuilder("Store ").append(store.getCode());
		if (trim(copy.getSalesNumber()) != null) {
			text.append(", sale #").append(copy.getSalesNumber().trim());
		}
		if (trim(copy.getReturnNumber()) != null) {
			text.append(", return #").append(copy.getReturnNumber().trim());
		}
		if (!card.equals(member.getCardNumber())) {
			text.append(", card ").append(card);
		}
		if (overspend > 0) {
			text.append(" (overspend: ").append(overspend).append(" points could not be removed)");
		}
		if (trim(copy.getDescription()) != null) {
			text.append(": ").append(copy.getDescription().trim());
		}
		return text.length() <= DESCRIPTION_LENGTH ? text.toString() : text.substring(0, DESCRIPTION_LENGTH);
	}

	// ─── Live questions ──────────────────────────────────────────

	/** GET /ho/loyalty/members/by-phone: the member holding the phone (the active card first), across the network. */
	public LoyaltyPhoneCheckDTO checkPhone(String rawPhone) {
		String phone = LoyaltyService.normalizePhone(rawPhone);
		if (phone.isEmpty()) {
			return new LoyaltyPhoneCheckDTO(false, null);
		}
		return phoneHolder(phone, null).map(m -> new LoyaltyPhoneCheckDTO(true, LoyaltyMemberCopyDTO.of(m)))
				.orElseGet(() -> new LoyaltyPhoneCheckDTO(false, null));
	}

	/**
	 * PUT /ho/loyalty/members/{cardNumber}: a member changed by a store that has the right. The phone rules of
	 * LoyaltyService.updateMember, across the network. Throws {@link NoRightException} (403), NoSuchElementException
	 * (404, unknown card), IllegalArgumentException (400), IllegalStateException (409, phone of another card).
	 */
	public LoyaltyMemberCopyDTO editMember(Store store, String cardNumber, LoyaltyMemberEditDTO edit) {
		if (!Boolean.TRUE.equals(store.getCanEditMembers())) {
			throw new NoRightException(NO_EDIT_RIGHT);
		}
		if (edit == null) {
			throw new IllegalArgumentException("The member is required");
		}
		synchronized (lock) {
			return writes.execute(status -> applyEdit(store, cardNumber, edit));
		}
	}

	private LoyaltyMemberCopyDTO applyEdit(Store store, String cardNumber, LoyaltyMemberEditDTO edit) {
		String card = trim(cardNumber);
		LoyaltyMember member = (card == null ? Optional.<LoyaltyMember>empty() : memberOfCard(card))
				.orElseThrow(() -> new NoSuchElementException("Unknown card " + cardNumber + " at the head office"));
		if (trim(edit.getFirstName()) == null || trim(edit.getLastName()) == null) {
			throw new IllegalArgumentException("First name and last name are required");
		}
		// A blank function keeps the member's own (a deactivation sends the member as it is). 2.2.2: a member without
		// function is accepted: the store sends none only when it has no active function (its own rule,
		// LoyaltyService.memberFunctionFor); the head office's list mixes the functions of every store.
		String phone = LoyaltyService.normalizePhone(edit.getPhone());
		if (!phone.equals(member.getPhone())) {
			if (phone.isEmpty()) {
				throw new IllegalArgumentException("Le numéro de téléphone est obligatoire");
			}
			if (!phone.matches("\\d{8}")) {
				throw new IllegalArgumentException("Le numéro de téléphone doit contenir 8 chiffres");
			}
			Optional<LoyaltyMember> holder = phoneHolder(phone, member.getId());
			if (holder.isPresent()) {
				LoyaltyMember m = holder.get();
				throw new IllegalStateException(LoyaltyService.phoneTakenMessage(m.getCardNumber(), m.getFirstName(),
						m.getLastName(), m.getActive()));
			}
		}
		member.setFirstName(trim(edit.getFirstName()));
		member.setLastName(trim(edit.getLastName()));
		member.setPhone(phone);
		member.setEmail(edit.getEmail());
		member.setBirthDate(edit.getBirthDate());
		if (trim(edit.getMemberFunctionCode()) != null) {
			member.setMemberFunction(MemberFunctionCodes.resolve(memberFunctions, edit.getMemberFunctionCode(),
					edit.getMemberFunctionName()));
		}
		member.setCustomer(trim(edit.getCustomerCode()) == null ? null
				: customers.findByCustomerCode(edit.getCustomerCode().trim()).orElse(null));
		if (edit.getActive() != null) {
			member.setActive(edit.getActive());
		}
		member.setUpdatedBy(storeUser(store));
		LoyaltyMember saved = members.save(member);
		register.recordMember(saved.getCardNumber());
		log.info("Head office: loyalty member {} changed by store '{}'", saved.getCardNumber(), store.getCode());
		return LoyaltyMemberCopyDTO.of(saved);
	}

	/**
	 * Step 5, GET /ho/loyalty/members/{cardNumber}: the member as the head office holds it now (the fresh balance a till
	 * asks for when a member is selected); an alias card answers its surviving member. NoSuchElementException (404).
	 */
	public LoyaltyMemberCopyDTO findMember(String cardNumber) {
		String card = trim(cardNumber);
		return (card == null ? Optional.<LoyaltyMember>empty() : memberOfCard(card)).map(LoyaltyMemberCopyDTO::of)
				.orElseThrow(() -> new NoSuchElementException("Unknown card " + cardNumber + " at the head office"));
	}

	/**
	 * Step 5, POST /ho/loyalty/members/{cardNumber}/adjust: a manual adjustment asked by a store with canAdjustPoints,
	 * applied as at the head office (LoyaltyService.adjustPoints: ADJUSTED row, never below zero), by
	 * "STORE:&lt;code&gt; (&lt;user&gt;)"; every store gets the new balance. Throws {@link NoRightException} (403),
	 * NoSuchElementException (404), IllegalArgumentException (400).
	 */
	public LoyaltyMemberCopyDTO adjustPoints(Store store, String cardNumber, LoyaltyPointsAdjustDTO request) {
		if (!Boolean.TRUE.equals(store.getCanAdjustPoints())) {
			throw new NoRightException(NO_ADJUST_POINTS_RIGHT);
		}
		if (request == null || request.getDelta() == null || request.getDelta() == 0) {
			throw new IllegalArgumentException("Delta cannot be zero");
		}
		if (trim(request.getReason()) == null) {
			throw new IllegalArgumentException("Reason is required");
		}
		String card = trim(cardNumber);
		synchronized (lock) {
			return writes.execute(status -> {
				LoyaltyMember member = (card == null ? Optional.<LoyaltyMember>empty() : memberOfCard(card)).orElseThrow(
						() -> new NoSuchElementException("Unknown card " + cardNumber + " at the head office"));
				String by = storeUser(store)
						+ (trim(request.getAdjustedBy()) == null ? "" : " (" + request.getAdjustedBy().trim() + ")");
				loyaltyService.get().adjustPoints(member.getId(), request.getDelta(), request.getReason().trim(), by);
				log.info("Head office: {} points adjusted on card {} by store '{}'", request.getDelta(),
						member.getCardNumber(), store.getCode());
				return LoyaltyMemberCopyDTO.of(members.findById(member.getId()).orElse(member));
			});
		}
	}

	// ─── Helpers ─────────────────────────────────────────────────

	/** The member of a card, or of the member it was merged into. */
	private Optional<LoyaltyMember> memberOfCard(String card) {
		Optional<LoyaltyMember> member = members.findByCardNumber(card);
		if (member.isPresent()) {
			return member;
		}
		return aliases.findByCardNumber(card).flatMap(alias -> members.findById(alias.getMemberId()));
	}

	private Optional<LoyaltyMember> phoneHolder(String phone, Long excludeId) {
		return members.findByPhone(phone).stream().filter(m -> excludeId == null || !excludeId.equals(m.getId()))
				.min(LoyaltyService.PHONE_HOLDER_ORDER);
	}

	private static String storeUser(Store store) {
		return STORE_USER_PREFIX + store.getCode();
	}

	private static String trim(String value) {
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}

	private static String cut(String message) {
		return message == null || message.length() <= MESSAGE_LENGTH ? message : message.substring(0, MESSAGE_LENGTH);
	}

	/** A store without the right asked for a change (403). */
	public static class NoRightException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public NoRightException(String message) {
			super(message);
		}
	}
}
