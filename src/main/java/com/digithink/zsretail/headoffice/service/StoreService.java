package com.digithink.zsretail.headoffice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.headoffice.dto.HeadOfficeHeartbeatDTO;
import com.digithink.zsretail.headoffice.dto.StoreListItemDTO;
import com.digithink.zsretail.headoffice.dto.StoreWithKeyDTO;
import com.digithink.zsretail.headoffice.enumeration.PriceListKind;
import com.digithink.zsretail.headoffice.enumeration.StoreKind;
import com.digithink.zsretail.headoffice.enumeration.StoreStatus;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.DataOwner;
import com.digithink.zsretail.model.enumeration.SalesUpstream;
import com.digithink.zsretail.repository._BaseRepository;
import com.digithink.zsretail.service.LoyaltyCardNumbers;
import com.digithink.zsretail.service._BaseService;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Head office stores list (task 1.2). Only the server writes the code normalisation, the API key hash,
 * lastContact and appVersion. The plain API key is returned once (create, regenerate) and never stored or logged.
 * See docs/modules/head-office.md.
 */
@Service
@ConditionalOnHeadOffice
@Log4j2
public class StoreService extends _BaseService<Store, Long> {

	static final String CODE_REQUIRED = "The store code is required.";
	static final String NAME_REQUIRED = "The store name is required.";
	static final String CODE_IS_FINAL = "The store code cannot be changed after creation.";
	static final String DELETE_AFTER_CONTACT = "This store has already contacted the head office: deactivate it instead.";
	static final String CODE_RESERVED = "The code " + LoyaltyCardNumbers.HEAD_OFFICE_CODE
			+ " is kept for the head office's own loyalty cards (LYL-HO-...): choose another code.";

	/** 32 random bytes: a 43-character URL-safe key. */
	static final int KEY_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	/** Compared when the code is unknown, so the answer time does not tell which codes exist. */
	private static final String UNKNOWN_STORE_HASH = sha256Hex("unknown-store");

	/** Default of headoffice.offline-after-seconds: three missed heartbeats at the default 60 s interval. */
	static final long DEFAULT_OFFLINE_AFTER_SECONDS = 180;

	@Autowired
	private StoreRepository storeRepository;

	/** Step 6: the price lists, on a head office without an ERP only (no bean on a head office with an ERP). */
	@Autowired(required = false)
	private ObjectProvider<HoPriceListService> priceLists;

	/** A store whose last contact is older than this is OFFLINE (task 1.5). */
	@Value("${headoffice.offline-after-seconds:" + DEFAULT_OFFLINE_AFTER_SECONDS + "}")
	private long offlineAfterSeconds = DEFAULT_OFFLINE_AFTER_SECONDS;

	@Override
	protected _BaseRepository<Store, Long> getRepository() {
		return storeRepository;
	}

	/** Every store with its status, computed now with the head office clock (Stores page, task 1.5). */
	public List<StoreListItemDTO> findAllWithStatus() {
		LocalDateTime now = LocalDateTime.now();
		return findAll().stream().map(store -> listItem(store, now)).collect(Collectors.toList());
	}

	/** One store with its status; empty when it does not exist. */
	public Optional<StoreListItemDTO> findByIdWithStatus(Long id) {
		return storeRepository.findById(id).map(store -> listItem(store, LocalDateTime.now()));
	}

	StoreListItemDTO listItem(Store store, LocalDateTime now) {
		Long seconds = store.getLastContact() == null ? null
				: Math.max(0, Duration.between(store.getLastContact(), now).getSeconds());
		return new StoreListItemDTO(store, statusOf(store, now, offlineAfterSeconds), seconds);
	}

	/**
	 * INACTIVE when deactivated, NEVER without a contact, otherwise ONLINE while the last contact is no older than
	 * the threshold (exactly at the threshold included) and OFFLINE after it.
	 */
	static StoreStatus statusOf(Store store, LocalDateTime now, long offlineAfterSeconds) {
		if (!Boolean.TRUE.equals(store.getActive())) {
			return StoreStatus.INACTIVE;
		}
		if (store.getLastContact() == null) {
			return StoreStatus.NEVER;
		}
		Duration age = Duration.between(store.getLastContact(), now);
		return age.compareTo(Duration.ofSeconds(offlineAfterSeconds)) <= 0 ? StoreStatus.ONLINE : StoreStatus.OFFLINE;
	}

	/** New store with a server-generated key. Ignores lastContact, appVersion and any hash sent by the client. */
	public StoreWithKeyDTO create(Store input) throws Exception {
		String code = normalizeCode(input.getCode());
		if (code.isEmpty()) {
			throw new IllegalArgumentException(CODE_REQUIRED);
		}
		if (LoyaltyCardNumbers.HEAD_OFFICE_CODE.equals(code)) {
			throw new IllegalArgumentException(CODE_RESERVED); // step 4: its cards would take the head office's numbers
		}
		String name = input.getName() == null ? "" : input.getName().trim();
		if (name.isEmpty()) {
			throw new IllegalArgumentException(NAME_REQUIRED);
		}
		if (storeRepository.findByCodeIgnoreCase(code).isPresent()) {
			throw new IllegalStateException("A store with the code " + code + " already exists.");
		}

		Store store = new Store();
		store.setCode(code);
		store.setName(name);
		store.setKind(input.getKind() != null ? input.getKind() : StoreKind.OWN);
		store.setActive(input.getActive() != null ? input.getActive() : Boolean.TRUE);
		store.setCanEditMembers(Boolean.TRUE.equals(input.getCanEditMembers()));
		store.setCanAdjustPoints(Boolean.TRUE.equals(input.getCanAdjustPoints()));
		store.setRedeemRequiresOnline(Boolean.TRUE.equals(input.getRedeemRequiresOnline()));
		store.setEnrolRequiresOnline(Boolean.TRUE.equals(input.getEnrolRequiresOnline()));
		store.setMayChangePrices(Boolean.TRUE.equals(input.getMayChangePrices()));
		store.setCanPurchase(Boolean.TRUE.equals(input.getCanPurchase()));
		if (input.getSellingPriceListId() != null) {
			priceListService().checkAssignable(input.getSellingPriceListId());
			store.setSellingPriceListId(input.getSellingPriceListId()); // a new store has nothing to send again
		}
		applyInvoicing(store, input); // step 7B
		if (input.getSupplyPriceListId() != null) {
			priceListService().checkAssignable(input.getSupplyPriceListId(), PriceListKind.SUPPLY);
			store.setSupplyPriceListId(input.getSupplyPriceListId());
		}
		String key = newKey();
		store.setApiKeyHash(sha256Hex(key));
		Store saved = save(store);
		log.info("Head office: store {} created", saved.getCode());
		return new StoreWithKeyDTO(saved, key);
	}

	/**
	 * Applies name, kind and active only (each when sent). A different code is refused; lastContact, appVersion
	 * and the key hash are never taken from the client. Empty when the store does not exist.
	 */
	public Optional<Store> update(Long id, Store input) throws Exception {
		Optional<Store> found = storeRepository.findById(id);
		if (!found.isPresent()) {
			return Optional.empty();
		}
		Store store = found.get();
		if (input.getCode() != null && !normalizeCode(input.getCode()).equals(store.getCode())) {
			throw new IllegalArgumentException(CODE_IS_FINAL);
		}
		if (input.getName() != null) {
			String name = input.getName().trim();
			if (name.isEmpty()) {
				throw new IllegalArgumentException(NAME_REQUIRED);
			}
			store.setName(name);
		}
		if (input.getKind() != null) {
			store.setKind(input.getKind());
		}
		if (input.getActive() != null) {
			store.setActive(input.getActive());
		}
		if (input.getCanEditMembers() != null) {
			store.setCanEditMembers(input.getCanEditMembers());
		}
		if (input.getCanAdjustPoints() != null) {
			store.setCanAdjustPoints(input.getCanAdjustPoints());
		}
		if (input.getRedeemRequiresOnline() != null) {
			store.setRedeemRequiresOnline(input.getRedeemRequiresOnline());
		}
		if (input.getEnrolRequiresOnline() != null) {
			store.setEnrolRequiresOnline(input.getEnrolRequiresOnline());
		}
		if (input.getMayChangePrices() != null) {
			store.setMayChangePrices(input.getMayChangePrices());
		}
		if (input.getCanPurchase() != null) {
			store.setCanPurchase(input.getCanPurchase());
		}
		applyInvoicing(store, input); // step 7B; the supply price list has its own endpoint
		return Optional.of(save(store));
	}

	/**
	 * Step 7B: the invoicing settings sent (each one when present; a blank billing field clears it). 400
	 * (IllegalArgument) for a billing field too long or a discount outside 0..100.
	 */
	static void applyInvoicing(Store store, Store input) {
		if (input.getDeliveriesInvoiced() != null) {
			store.setDeliveriesInvoiced(input.getDeliveriesInvoiced());
		}
		if (input.getBillingLegalName() != null) {
			store.setBillingLegalName(billing("legal name", input.getBillingLegalName(), Store.BILLING_NAME_LENGTH));
		}
		if (input.getBillingTaxNumber() != null) {
			store.setBillingTaxNumber(billing("tax number", input.getBillingTaxNumber(), Store.BILLING_TAX_NUMBER_LENGTH));
		}
		if (input.getBillingAddress() != null) {
			store.setBillingAddress(billing("address", input.getBillingAddress(), Store.BILLING_ADDRESS_LENGTH));
		}
		if (input.getSupplyPriceMode() != null) {
			store.setSupplyPriceMode(input.getSupplyPriceMode());
		}
		if (input.getSupplyDiscountPercent() != null) {
			double percent = input.getSupplyDiscountPercent();
			if (Double.isNaN(percent) || percent < 0 || percent > 100) {
				throw new IllegalArgumentException("The supply discount must be from 0 to 100 %.");
			}
			store.setSupplyDiscountPercent(percent);
		}
		if (input.getInvoiceRhythm() != null) {
			store.setInvoiceRhythm(input.getInvoiceRhythm());
		}
	}

	private static String billing(String what, String value, int length) {
		String trimmed = value.trim();
		if (trimmed.length() > length) {
			throw new IllegalArgumentException("The billing " + what + " is longer than " + length + " characters.");
		}
		return trimmed.isEmpty() ? null : trimmed;
	}

	/**
	 * Step 7B: the store's supply price list (a list of kind SUPPLY; null: none, the base supply price). Nothing is sent
	 * to the store: the supply price is used only when an invoice is created. 400 for an unknown, inactive or selling
	 * list, or on a head office with an ERP. Empty when the store does not exist.
	 */
	@Transactional
	public Optional<Store> setSupplyPriceList(Long id, Long priceListId) throws Exception {
		Optional<Store> found = storeRepository.findById(id);
		if (!found.isPresent()) {
			return Optional.empty();
		}
		Store store = found.get();
		HoPriceListService lists = priceListService();
		if (priceListId != null) {
			lists.checkAssignable(priceListId, PriceListKind.SUPPLY);
		}
		store.setSupplyPriceListId(priceListId);
		return Optional.of(save(store));
	}

	/**
	 * Task 6.4: the store's selling price list (null: none, the base price). The items of the old and the new list are
	 * sent again to this store only, in the same transaction. 400 (IllegalArgument) for an unknown or inactive list, or
	 * on a head office with an ERP (no price lists there). Empty when the store does not exist.
	 */
	@Transactional
	public Optional<Store> setSellingPriceList(Long id, Long priceListId) throws Exception {
		Optional<Store> found = storeRepository.findById(id);
		if (!found.isPresent()) {
			return Optional.empty();
		}
		Store store = found.get();
		HoPriceListService lists = priceListService();
		if (priceListId != null) {
			lists.checkAssignable(priceListId);
		}
		Long previous = store.getSellingPriceListId();
		if (java.util.Objects.equals(previous, priceListId)) {
			return Optional.of(store);
		}
		store.setSellingPriceListId(priceListId);
		Store saved = save(store);
		lists.storeListChanged(saved.getId(), previous, priceListId);
		log.info("Head office: store {} selling price list {} -> {}", saved.getCode(), previous, priceListId);
		return Optional.of(saved);
	}

	private HoPriceListService priceListService() {
		HoPriceListService lists = priceLists == null ? null : priceLists.getIfAvailable();
		if (lists == null) {
			throw new IllegalArgumentException(
					"Price lists exist only on a head office without an ERP: this head office has none.");
		}
		return lists;
	}

	/** New key for the store; the old one stops working. Empty when the store does not exist. */
	public Optional<StoreWithKeyDTO> regenerateKey(Long id) throws Exception {
		Optional<Store> found = storeRepository.findById(id);
		if (!found.isPresent()) {
			return Optional.empty();
		}
		Store store = found.get();
		String key = newKey();
		store.setApiKeyHash(sha256Hex(key));
		Store saved = save(store);
		log.info("Head office: API key regenerated for store {}", saved.getCode());
		return Optional.of(new StoreWithKeyDTO(saved, key));
	}

	/** Allowed only before the first contact; afterwards the store is deactivated instead. */
	@Override
	public void deleteById(Long id) {
		storeRepository.findById(id).ifPresent(store -> {
			if (store.getLastContact() != null) {
				throw new IllegalStateException(DELETE_AFTER_CONTACT);
			}
		});
		storeRepository.deleteById(id);
	}

	/**
	 * Heartbeat (task 1.4): the store's last contact (head office clock) and the version it sent. Writes these two
	 * columns only, by id: the /ho/** principal is detached and is never saved. The version is trimmed; blank gives
	 * null, longer than the column is cut.
	 */
	@Transactional
	public void recordContact(Long id, String appVersion, LocalDateTime when) {
		recordContact(id, new HeadOfficeHeartbeatDTO(appVersion), when);
	}

	/**
	 * Task 3.6: the heartbeat with what the store owns, in the same update as the contact. Each owner is kept only when
	 * it is an owner its domain allows, otherwise null (unknown); an absent map leaves every domain unknown. Upstreams:
	 * the known names in enum order, "" for an empty list (nowhere), null when absent (unknown). Lenient: a value it
	 * cannot read never refuses the heartbeat.
	 */
	@Transactional
	public void recordContact(Long id, HeadOfficeHeartbeatDTO heartbeat, LocalDateTime when) {
		Map<String, String> ownership = heartbeat == null ? null : heartbeat.getOwnership();
		storeRepository.updateContact(id, when, normalizeVersion(heartbeat == null ? null : heartbeat.getAppVersion()),
				owner(ownership, DataDomain.CATALOGUE), owner(ownership, DataDomain.CUSTOMERS),
				owner(ownership, DataDomain.PROMOTIONS), owner(ownership, DataDomain.LOYALTY),
				owner(ownership, DataDomain.SUPPLY), upstreams(heartbeat == null ? null : heartbeat.getSalesUpstreams()));
	}

	/** The owner reported for a domain (key and value trimmed, any case), when the domain allows it; null otherwise. */
	static String owner(Map<String, String> ownership, DataDomain domain) {
		if (ownership == null) {
			return null;
		}
		for (Map.Entry<String, String> entry : ownership.entrySet()) {
			if (entry.getKey() != null && entry.getValue() != null
					&& domain.name().equalsIgnoreCase(entry.getKey().trim())) {
				for (DataOwner owner : DataOwner.values()) {
					if (owner.name().equalsIgnoreCase(entry.getValue().trim()) && domain.allows(owner)) {
						return owner.name();
					}
				}
			}
		}
		return null;
	}

	/** Known SalesUpstream names, in enum order, comma-separated; "" for none; null when the list is absent. */
	static String upstreams(List<String> reported) {
		if (reported == null) {
			return null;
		}
		List<String> known = new ArrayList<>();
		for (SalesUpstream upstream : SalesUpstream.values()) {
			for (String value : reported) {
				if (value != null && upstream.name().equalsIgnoreCase(value.trim()) && !known.contains(upstream.name())) {
					known.add(upstream.name());
				}
			}
		}
		return String.join(",", known);
	}

	/**
	 * The active store with this code (trimmed, case-insensitive) whose key hash matches the presented key, compared
	 * in constant time. Empty otherwise.
	 */
	public Optional<Store> authenticate(String code, String presentedKey) {
		return Optional.ofNullable(check(code, presentedKey).getStore());
	}

	/**
	 * The key check of the /ho/** filter (task 1.3): the store when accepted, otherwise the reason, for the head
	 * office log only (the caller always gets the same 401). An unknown code is compared against a fixed hash, so
	 * the answer time does not tell which codes exist. An inactive store is reported only when its key is right.
	 */
	public KeyCheck check(String code, String presentedKey) {
		Optional<Store> found = code == null ? Optional.empty() : storeRepository.findByCodeIgnoreCase(code.trim());
		String expected = found.map(Store::getApiKeyHash).orElse(UNKNOWN_STORE_HASH);
		String presented = sha256Hex(presentedKey == null ? "" : presentedKey);
		boolean keyMatches = presentedKey != null && MessageDigest.isEqual(
				presented.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII));
		if (!found.isPresent()) {
			return new KeyCheck(KeyCheck.Outcome.UNKNOWN_STORE, null);
		}
		if (!keyMatches) {
			return new KeyCheck(KeyCheck.Outcome.WRONG_KEY, null);
		}
		if (!Boolean.TRUE.equals(found.get().getActive())) {
			return new KeyCheck(KeyCheck.Outcome.INACTIVE_STORE, null);
		}
		return new KeyCheck(KeyCheck.Outcome.ACCEPTED, found.get());
	}

	/** Result of {@link StoreService#check}: the store only when the outcome is ACCEPTED. */
	@Getter
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static final class KeyCheck {

		public enum Outcome {
			ACCEPTED("accepted"), UNKNOWN_STORE("unknown store"), WRONG_KEY("wrong key"), INACTIVE_STORE("inactive store");

			/** Written in the head office log. */
			@Getter
			private final String reason;

			Outcome(String reason) {
				this.reason = reason;
			}
		}

		private final Outcome outcome;
		private final Store store;

		public boolean isAccepted() {
			return outcome == Outcome.ACCEPTED;
		}
	}

	/** Trimmed and uppercase; empty when null. */
	static String normalizeCode(String code) {
		return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
	}

	/** Trimmed; null when blank; cut to the column length. */
	static String normalizeVersion(String version) {
		if (version == null || version.trim().isEmpty()) {
			return null;
		}
		String trimmed = version.trim();
		return trimmed.length() > Store.APP_VERSION_LENGTH ? trimmed.substring(0, Store.APP_VERSION_LENGTH) : trimmed;
	}

	static String newKey() {
		byte[] bytes = new byte[KEY_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String sha256Hex(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}
}
