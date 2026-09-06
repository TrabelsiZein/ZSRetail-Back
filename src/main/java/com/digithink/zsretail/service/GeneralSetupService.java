package com.digithink.zsretail.service;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.model.GeneralSetup;
import com.digithink.zsretail.model.GeneralSetupChangeLog;
import com.digithink.zsretail.model.enumeration.GeneralSetupChangeSource;
import com.digithink.zsretail.model.enumeration.GeneralSetupChangeType;
import com.digithink.zsretail.repository.GeneralSetupChangeLogRepository;
import com.digithink.zsretail.repository.GeneralSetupRepository;
import com.digithink.zsretail.repository._BaseRepository;

@Service
public class GeneralSetupService extends _BaseService<GeneralSetup, Long> {

	@Autowired
	private GeneralSetupRepository generalSetupRepository;

	@Autowired
	private GeneralSetupChangeLogRepository generalSetupChangeLogRepository;

	@Override
	protected _BaseRepository<GeneralSetup, Long> getRepository() {
		return generalSetupRepository;
	}

	public GeneralSetup findByCode(String code) {
		return generalSetupRepository.findByCode(code).orElse(null);
	}

	public String findValueByCode(String code) {
		return generalSetupRepository.findByCode(code).map(GeneralSetup::getValeur).orElse(null);
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

		existing.setValeur(newValue);
		existing.setDescription(updatedSetting.getDescription());

		GeneralSetup saved = this.save(existing);

		if (!Objects.equals(oldValue, newValue)) {
			logChange(saved, oldValue, newValue, GeneralSetupChangeType.UPDATE, GeneralSetupChangeSource.ADMIN_UI, reason);
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
