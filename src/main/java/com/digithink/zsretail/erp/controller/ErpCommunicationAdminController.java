package com.digithink.zsretail.erp.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.digithink.zsretail.erp.dto.ErpCommunicationViewDTO;
import com.digithink.zsretail.erp.enumeration.ErpCommunicationStatus;
import com.digithink.zsretail.erp.enumeration.ErpSyncOperation;
import com.digithink.zsretail.erp.model.ErpCommunication;
import com.digithink.zsretail.erp.repository.ErpCommunicationRepository;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.enumeration.Role;
import com.digithink.zsretail.security.CurrentUserProvider;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("admin/erp/communications")
@RequiredArgsConstructor
public class ErpCommunicationAdminController {

	private final ErpCommunicationRepository communicationRepository;
	private final CurrentUserProvider currentUserProvider;

	@GetMapping
	public ResponseEntity<Map<String, Object>> listCommunications(
			@RequestParam(name = "from", required = false) String from,
			@RequestParam(name = "to", required = false) String to,
			@RequestParam(name = "status", required = false) String status,
			@RequestParam(name = "operation", required = false) String operation,
			@RequestParam(name = "search", required = false) String search,
			@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "20") int size) {

		ensureAdminAccess();

		if (size <= 0 || size > 200) size = 20;
		if (page < 0) page = 0;

		LocalDateTime start = (from != null && !from.isBlank()) ? parseDateTime(from) : null;
		LocalDateTime end   = (to   != null && !to.isBlank())   ? parseDateTime(to)   : null;
		if (start != null && end != null && end.isBefore(start)) {
			LocalDateTime tmp = start; start = end; end = tmp;
		}

		final LocalDateTime finalStart = start;
		final LocalDateTime finalEnd   = end;

		ErpCommunicationStatus statusEnum    = parseEnum(ErpCommunicationStatus.class, status);
		ErpSyncOperation       operationEnum = parseEnum(ErpSyncOperation.class, operation);
		final String           searchTerm   = (search != null && !search.isBlank()) ? search.toLowerCase() : null;

		Specification<ErpCommunication> spec = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (finalStart != null)  predicates.add(cb.greaterThanOrEqualTo(root.get("startedAt"), finalStart));
			if (finalEnd != null)    predicates.add(cb.lessThan(root.get("startedAt"), finalEnd));
			if (statusEnum != null)  predicates.add(cb.equal(root.get("status"), statusEnum));
			if (operationEnum != null) predicates.add(cb.equal(root.get("operation"), operationEnum));
			if (searchTerm != null) {
				String like = "%" + searchTerm + "%";
				predicates.add(cb.or(
					cb.like(cb.lower(root.get("url")), like),
					cb.like(cb.lower(root.get("errorMessage")), like),
					cb.like(cb.lower(root.get("requestPayload")), like),
					cb.like(cb.lower(root.get("responsePayload")), like)
				));
			}
			return cb.and(predicates.toArray(new Predicate[0]));
		};

		PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt"));
		Page<ErpCommunication> pageResult = communicationRepository.findAll(spec, pageable);

		List<ErpCommunicationViewDTO> content = pageResult.getContent().stream()
				.map(this::mapToDto)
				.collect(Collectors.toList());

		Map<String, Object> response = new HashMap<>();
		response.put("content", content);
		response.put("totalElements", pageResult.getTotalElements());
		return ResponseEntity.ok(response);
	}

	private LocalDateTime parseDateTime(String value) {
		try {
			return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
		} catch (Exception e) {
			try {
				return LocalDate.parse(value).atStartOfDay();
			} catch (Exception ex) {
				return null;
			}
		}
	}

	private <E extends Enum<E>> E parseEnum(Class<E> enumClass, String value) {
		if (value == null || value.isBlank() || "ALL".equals(value)) return null;
		try { return Enum.valueOf(enumClass, value); } catch (IllegalArgumentException e) { return null; }
	}

	private ErpCommunicationViewDTO mapToDto(ErpCommunication entity) {
		ErpCommunicationViewDTO dto = new ErpCommunicationViewDTO();
		dto.setId(entity.getId());
		dto.setOperation(entity.getOperation());
		dto.setStatus(entity.getStatus());
		dto.setUrl(entity.getUrl());
		dto.setErrorMessage(entity.getErrorMessage());
		dto.setStartedAt(entity.getStartedAt());
		dto.setCompletedAt(entity.getCompletedAt());
		dto.setDurationMs(entity.getDurationMs());
		dto.setRequestPayload(entity.getRequestPayload());
		dto.setResponsePayload(entity.getResponsePayload());
		return dto;
	}

	private void ensureAdminAccess() {
		UserAccount currentUser = currentUserProvider.getCurrentUser();
		if (currentUser == null || currentUser.getRole() != Role.ADMIN) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin privileges required");
		}
	}
}
