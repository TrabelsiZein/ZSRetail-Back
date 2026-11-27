package com.digithink.zsretail.core.service;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.GeneralSetupChangeLog;
import com.digithink.zsretail.core.model.enumeration.GeneralSetupChangeSource;
import com.digithink.zsretail.core.model.enumeration.GeneralSetupChangeType;
import com.digithink.zsretail.core.repository.GeneralSetupChangeLogRepository;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.erp.api.GeneralSetupValueCheck;

@Service
public class GeneralSetupService extends _BaseService<GeneralSetup, Long> {

	@Autowired
	private GeneralSetupRepository generalSetupRepository;

	@Autowired
	private GeneralSetupChangeLogRepository generalSetupChangeLogRepository;

	/** Release 2.2: the checks of the settings a mode owns (none on most installations). */
	@Autowired(required = false)
	private java.util.List<GeneralSetupValueCheck> valueChecks;

	/** 3.4.0, event log step 8: told after an administrator changed a row (EventLogSettingsChange). */
	@Autowired(required = false)
	private java.util.List<GeneralSetupChangeListener> changeListeners;

	/**
	 * 3.5.0, ticket templates step 6: rows no longer created, read or shown; an installation of before keeps them one
	 * release as source data (their values were carried into the ticket templates), then the next release drops them.
	 */
	public static final java.util.Set<String> RETIRED_CODES = java.util.Collections.unmodifiableSet(new java.util.HashSet<>(
			java.util.Arrays.asList("TICKET_RENDERER", "TICKET_SHOW_LOYALTY_BALANCE", "TICKET_SHOW_LOYALTY_EARNED")));

	@Override
	protected _BaseRepository<GeneralSetup, Long> getRepository() {
		return generalSetupRepository;
	}

	/** Every row but the retired ones (the General setup page: GET /general-setup). */
	@Override
	public java.util.List<GeneralSetup> findAll() {
		return super.findAll().stream().filter(s -> !RETIRED_CODES.contains(s.getCode()))
				.collect(java.util.stream.Collectors.toList());
	}

	/** Null for a retired code too. */
	public GeneralSetup findByCode(String code) {
		return RETIRED_CODES.contains(code) ? null : generalSetupRepository.findByCode(code).orElse(null);
	}

	public String findValueByCode(String code) {
		return RETIRED_CODES.contains(code) ? null
				: generalSetupRepository.findByCode(code).map(GeneralSetup::getValeur).orElse(null);
	}

	@Transactional
	public void updateValue(String code, String value) {
		generalSetupRepository.findByCode(code).ifPresent(setup -> {
			String oldValue = setup.getValeur();
			if (Objects.equals(oldValue, value)) {
				return;
			}

			setup.setValeur(value);
			setup.setUpdatedBy(resolveCurrentUsernameOrSystem());
			setup.setUpdatedAt(LocalDateTime.now());
			generalSetupRepository.save(setup);
			logChange(setup, oldValue, value, GeneralSetupChangeType.UPDATE, GeneralSetupChangeSource.SYSTEM, null);
		});
	}

	@Transactional
	public GeneralSetup updateFromAdmin(Long id, GeneralSetup updatedSetting, String reason) throws Exception {
		Optional<GeneralSetup> existingOpt = generalSetupRepository.findById(id);
		if (!existingOpt.isPresent()) {
			throw new IllegalArgumentException("Setting not found");
		}

		GeneralSetup existing = existingOpt.get();
		if (Boolean.TRUE.equals(existing.getReadOnly())) {
			throw new IllegalStateException("This setting is read only and cannot be modified.");
		}

		String oldValue = existing.getValeur();
		String newValue = updatedSetting.getValeur();
		if (valueChecks != null) {
			for (GeneralSetupValueCheck check : valueChecks) {
				check.check(existing.getCode(), newValue); // IllegalStateException: refused, answered 400
			}
		}

		existing.setValeur(newValue);
		existing.setDescription(updatedSetting.getDescription());

		GeneralSetup saved = this.save(existing);

		if (!Objects.equals(oldValue, newValue)) {
			logChange(saved, oldValue, newValue, GeneralSetupChangeType.UPDATE, GeneralSetupChangeSource.ADMIN_UI, reason);
			if (changeListeners != null) {
				for (GeneralSetupChangeListener listener : changeListeners) {
					listener.changed(saved.getCode(), oldValue, newValue);
				}
			}
		}

		return saved;
	}

	private void logChange(GeneralSetup setup, String oldValue, String newValue, GeneralSetupChangeType changeType,
			GeneralSetupChangeSource source, String reason) {
		GeneralSetupChangeLog log = new GeneralSetupChangeLog();
		log.setGeneralSetup(setup);
		log.setCode(setup.getCode());
		log.setOldValue(oldValue);
		log.setNewValue(newValue);
		log.setChangeType(changeType);
		log.setSource(source);
		log.setReason(reason);
		log.setCreatedBy(resolveCurrentUsernameOrSystem());
		log.setUpdatedBy(resolveCurrentUsernameOrSystem());
		log.setCreatedAt(LocalDateTime.now());
		log.setUpdatedAt(LocalDateTime.now());
		generalSetupChangeLogRepository.save(log);
	}

	private String resolveCurrentUsernameOrSystem() {
		try {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			if (authentication == null || !authentication.isAuthenticated()) {
				return "System";
			}
			Object principal = authentication.getPrincipal();
			if (principal == null) {
				return "System";
			}
			String username = principal.toString();
			return (username == null || username.trim().isEmpty() || "anonymousUser".equalsIgnoreCase(username)) ? "System"
					: username;
		} catch (Exception e) {
			return "System";
		}
	}
}
