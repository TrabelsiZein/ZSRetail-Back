package com.digithink.zsretail.headoffice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.headoffice.dto.StoreWithKeyDTO;
import com.digithink.zsretail.headoffice.enumeration.StoreKind;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.repository._BaseRepository;
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

	/** 32 random bytes: a 43-character URL-safe key. */
	static final int KEY_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	/** Compared when the code is unknown, so the answer time does not tell which codes exist. */
	private static final String UNKNOWN_STORE_HASH = sha256Hex("unknown-store");

	@Autowired
	private StoreRepository storeRepository;

	@Override
	protected _BaseRepository<Store, Long> getRepository() {
		return storeRepository;
	}

	/** New store with a server-generated key. Ignores lastContact, appVersion and any hash sent by the client. */
	public StoreWithKeyDTO create(Store input) throws Exception {
		String code = normalizeCode(input.getCode());
		if (code.isEmpty()) {
			throw new IllegalArgumentException(CODE_REQUIRED);
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
		return Optional.of(save(store));
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
		storeRepository.updateContact(id, when, normalizeVersion(appVersion));
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
