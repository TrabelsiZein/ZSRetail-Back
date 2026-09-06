package com.digithink.zsretail.controller;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.dto.CreateLoyaltyMemberRequestDTO;
import com.digithink.zsretail.dto.LoyaltyAdjustmentRequestDTO;
import com.digithink.zsretail.dto.LoyaltyConfigDTO;
import com.digithink.zsretail.dto.LoyaltyMemberDTO;
import com.digithink.zsretail.dto.LoyaltyProgramDTO;
import com.digithink.zsretail.dto.LoyaltyTransactionDTO;
import com.digithink.zsretail.model.LoyaltyProgram;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.service.LoyaltyService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("loyalty")
@Log4j2
public class LoyaltyAPI {

	@Autowired
	private LoyaltyService loyaltyService;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	// ───────────────────────────────────────────────────────────────
	// Config
	// ───────────────────────────────────────────────────────────────

	@GetMapping("/config")
	public ResponseEntity<LoyaltyConfigDTO> getConfig() {
		return ResponseEntity.ok(loyaltyService.getLoyaltyConfig());
	}

	// ───────────────────────────────────────────────────────────────
	// Members — POS + Admin
	// ───────────────────────────────────────────────────────────────

	@GetMapping("/member/search")
	public ResponseEntity<?> searchMembers(@RequestParam String q) {
		try {
			if (q == null || q.trim().length() < 2) {
				return ResponseEntity.ok(List.of());
			}
			List<LoyaltyMemberDTO> results = loyaltyService.searchMembers(q.trim());
			return ResponseEntity.ok(results);
		} catch (Exception e) {
			log.error("Error searching loyalty members", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", e.getMessage()));
		}
	}

	@GetMapping("/member/by-card/{cardNumber}")
	public ResponseEntity<?> getMemberByCardNumber(@PathVariable String cardNumber) {
		return loyaltyService.getMemberByCardNumber(cardNumber)
				.<ResponseEntity<?>>map(ResponseEntity::ok)
				.orElse(ResponseEntity.notFound().build());
	}

	@PostMapping("/member")
	public ResponseEntity<?> createMember(@RequestBody CreateLoyaltyMemberRequestDTO request) {
		try {
			if (request.getFirstName() == null || request.getFirstName().isBlank()) {
				return ResponseEntity.badRequest().body(Map.of("error", "First name is required"));
			}
			if (request.getLastName() == null || request.getLastName().isBlank()) {
				return ResponseEntity.badRequest().body(Map.of("error", "Last name is required"));
			}
			LoyaltyMemberDTO created = loyaltyService.createMember(request);
			return ResponseEntity.ok(created);
		} catch (Exception e) {
			log.error("Error creating loyalty member", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", e.getMessage()));
		}
	}

	// ───────────────────────────────────────────────────────────────
	// Members — Admin only
	// ───────────────────────────────────────────────────────────────

	@GetMapping("/members")
	public ResponseEntity<?> getMembers(
			@RequestParam(defaultValue = "") String search,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		try {
			Page<LoyaltyMemberDTO> result = loyaltyService.getMembersPage(
					search,
					PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));

			Map<String, Object> response = new HashMap<>();
			response.put("content", result.getContent());
			response.put("totalElements", result.getTotalElements());
			response.put("totalPages", result.getTotalPages());
			response.put("currentPage", page);
			response.put("pageSize", size);

			return ResponseEntity.ok(response);
		} catch (Exception e) {
			log.error("Error fetching loyalty members", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", e.getMessage()));
		}
	}

	@GetMapping("/member/{id}")
	public ResponseEntity<?> getMemberById(@PathVariable Long id) {
		return loyaltyService.getMemberById(id)
				.<ResponseEntity<?>>map(ResponseEntity::ok)
				.orElse(ResponseEntity.notFound().build());
	}

	@PutMapping("/member/{id}")
	public ResponseEntity<?> updateMember(@PathVariable Long id, @RequestBody CreateLoyaltyMemberRequestDTO request) {
		try {
			LoyaltyMemberDTO updated = loyaltyService.updateMember(id, request);
			return ResponseEntity.ok(updated);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			log.error("Error updating loyalty member", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", e.getMessage()));
		}
	}

	@PutMapping("/member/{id}/toggle-active")
	public ResponseEntity<?> toggleMemberActive(@PathVariable Long id) {
		try {
			LoyaltyMemberDTO updated = loyaltyService.toggleMemberActive(id);
			return ResponseEntity.ok(updated);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			log.error("Error toggling loyalty member active", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", e.getMessage()));
		}
	}

	@PutMapping("/member/{id}/link-customer")
	public ResponseEntity<?> linkCustomer(@PathVariable Long id,
			@RequestBody Map<String, Long> body) {
		try {
			Long customerId = body.get("customerId");
			LoyaltyMemberDTO updated = loyaltyService.linkCustomer(id, customerId);
			return ResponseEntity.ok(updated);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			log.error("Error linking customer to loyalty member", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", e.getMessage()));
		}
	}

	@GetMapping("/member/{id}/transactions")
	public ResponseEntity<?> getTransactions(
			@PathVariable Long id,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		try {
			Page<LoyaltyTransactionDTO> txPage = loyaltyService.getTransactionHistory(
					id, PageRequest.of(page, size));

			Map<String, Object> response = new HashMap<>();
			response.put("content", txPage.getContent());
			response.put("totalElements", txPage.getTotalElements());
			response.put("totalPages", txPage.getTotalPages());
			response.put("currentPage", page);

			return ResponseEntity.ok(response);
		} catch (Exception e) {
			log.error("Error fetching loyalty transactions for member {}", id, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", e.getMessage()));
		}
	}

	@PostMapping("/member/{id}/adjust")
	public ResponseEntity<?> adjustPoints(
			@PathVariable Long id,
			@RequestBody LoyaltyAdjustmentRequestDTO request) {
		try {
			if (request.getDelta() == null || request.getDelta() == 0) {
				return ResponseEntity.badRequest().body(Map.of("error", "Delta cannot be zero"));
			}
			if (request.getReason() == null || request.getReason().isBlank()) {
				return ResponseEntity.badRequest().body(Map.of("error", "Reason is required"));
			}
			String adjustedBy = currentUserProvider.getCurrentUser() != null
					? currentUserProvider.getCurrentUser().getUsername()
					: "admin";
			LoyaltyMemberDTO updated = loyaltyService.adjustPoints(id, request.getDelta(), request.getReason(), adjustedBy);
			return ResponseEntity.ok(updated);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			log.error("Error adjusting loyalty points for member {}", id, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", e.getMessage()));
		}
	}

	// ───────────────────────────────────────────────────────────────
	// All Transactions — Admin page
	// ───────────────────────────────────────────────────────────────

	@GetMapping("/transactions")
	public ResponseEntity<?> getAllTransactions(
			@RequestParam(required = false) String type,
			@RequestParam(required = false) String dateFrom,
			@RequestParam(required = false) String dateTo,
			@RequestParam(required = false) Long memberId,
			@RequestParam(required = false) String search,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		try {
			Page<LoyaltyTransactionDTO> txPage = loyaltyService.getAllTransactionsFiltered(
					type, dateFrom, dateTo, memberId, search,
					PageRequest.of(page, size));

			Map<String, Object> response = new HashMap<>();
			response.put("content", txPage.getContent());
			response.put("totalElements", txPage.getTotalElements());
			response.put("totalPages", txPage.getTotalPages());
			response.put("currentPage", page);
			return ResponseEntity.ok(response);
		} catch (Exception e) {
			log.error("Error fetching loyalty transactions", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", e.getMessage()));
		}
	}

	// ───────────────────────────────────────────────────────────────
	// Programs — Admin only
	// ───────────────────────────────────────────────────────────────

	@GetMapping("/programs")
	public ResponseEntity<?> getPrograms() {
		try {
			return ResponseEntity.ok(loyaltyService.getAllPrograms());
		} catch (Exception e) {
			log.error("Error fetching loyalty programs", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", e.getMessage()));
		}
	}

	@PostMapping("/programs")
	public ResponseEntity<?> createProgram(@RequestBody LoyaltyProgram newProgram) {
		try {
			if (newProgram.getName() == null || newProgram.getName().isBlank()) {
				return ResponseEntity.badRequest().body(Map.of("error", "Program name is required"));
			}
			if (newProgram.getProgramCode() == null || newProgram.getProgramCode().isBlank()) {
				// Auto-generate code if not provided
				newProgram.setProgramCode("PROG-" + LocalDate.now().getYear() + "-" + System.currentTimeMillis() % 10000);
			}
			LoyaltyProgramDTO created = loyaltyService.activateNewProgram(newProgram);
			return ResponseEntity.status(HttpStatus.CREATED).body(created);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			log.error("Error creating loyalty program", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", e.getMessage()));
		}
	}

	@PutMapping("/programs/{id}")
	public ResponseEntity<?> updateProgram(@PathVariable Long id, @RequestBody LoyaltyProgram patch) {
		try {
			LoyaltyProgramDTO updated = loyaltyService.updateProgram(id, patch);
			return ResponseEntity.ok(updated);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			log.error("Error updating loyalty program {}", id, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", e.getMessage()));
		}
	}

	@DeleteMapping("/programs/{id}")
	public ResponseEntity<?> deleteProgram(@PathVariable Long id) {
		try {
			loyaltyService.deleteProgram(id);
			return ResponseEntity.noContent().build();
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			log.error("Error deleting loyalty program {}", id, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", e.getMessage()));
		}
	}

	@PostMapping("/programs/{id}/deactivate")
	public ResponseEntity<?> deactivateProgram(@PathVariable Long id) {
		try {
			LoyaltyProgramDTO updated = loyaltyService.deactivateProgram(id);
			return ResponseEntity.ok(updated);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			log.error("Error deactivating loyalty program {}", id, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", e.getMessage()));
		}
	}
}
