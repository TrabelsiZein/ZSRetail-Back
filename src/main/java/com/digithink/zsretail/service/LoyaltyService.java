package com.digithink.zsretail.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.dto.CreateLoyaltyMemberRequestDTO;
import com.digithink.zsretail.dto.LoyaltyConfigDTO;
import com.digithink.zsretail.dto.LoyaltyMemberDTO;
import com.digithink.zsretail.dto.LoyaltyProgramDTO;
import com.digithink.zsretail.dto.LoyaltyTransactionDTO;
import com.digithink.zsretail.model.CashierSession;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.LoyaltyProgram;
import com.digithink.zsretail.model.LoyaltyTransaction;
import com.digithink.zsretail.model.MemberFunction;
import com.digithink.zsretail.model.ReturnHeader;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.enumeration.LoyaltyTransactionType;
import com.digithink.zsretail.repository.CustomerRepository;
import com.digithink.zsretail.repository.GeneralSetupRepository;
import com.digithink.zsretail.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.repository.LoyaltyProgramRepository;
import com.digithink.zsretail.repository.LoyaltyTransactionRepository;
import com.digithink.zsretail.repository.MemberFunctionRepository;

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
public class LoyaltyService {

	@Autowired
	private LoyaltyMemberRepository loyaltyMemberRepository;

	@Autowired
	private LoyaltyProgramRepository loyaltyProgramRepository;

	@Autowired
	private LoyaltyTransactionRepository loyaltyTransactionRepository;

	@Autowired
	private GeneralSetupRepository generalSetupRepository;

	@Autowired
	private CustomerRepository customerRepository;

	@Autowired
	private MemberFunctionRepository memberFunctionRepository;

	// ───────────────────────────────────────────────────────────────
	// Configuration
	// ───────────────────────────────────────────────────────────────

	public boolean isLoyaltyEnabled() {
		return generalSetupRepository.findByCode("LOYALTY_ENABLED")
				.map(gs -> "true".equalsIgnoreCase(gs.getValeur()))
				.orElse(false);
	}

	public Optional<LoyaltyProgram> getActiveProgram() {
		return loyaltyProgramRepository.findCurrentActivePrograms(LocalDate.now()).stream().findFirst();
	}

	public LoyaltyConfigDTO getLoyaltyConfig() {
		boolean enabled = isLoyaltyEnabled();
		Optional<LoyaltyProgram> programOpt = getActiveProgram();

		LoyaltyConfigDTO.LoyaltyProgramDTO programDTO = null;
		if (programOpt.isPresent()) {
			LoyaltyProgram p = programOpt.get();
			programDTO = new LoyaltyConfigDTO.LoyaltyProgramDTO(
					p.getId(), p.getProgramCode(), p.getName(), p.getDescription(),
					p.getStartDate() != null ? p.getStartDate().toString() : null,
					p.getEndDate() != null ? p.getEndDate().toString() : null,
					p.getPointsPerDinar(), p.getPointValueMillimes(),
					p.getMinimumRedemptionPoints(), p.getMaximumRedemptionPercentage(),
					p.getPointsExpiryDays());
		}

		return new LoyaltyConfigDTO(enabled, programDTO);
	}

	// ───────────────────────────────────────────────────────────────
	// Member Management
	// ───────────────────────────────────────────────────────────────

	public List<LoyaltyMemberDTO> searchMembers(String query) {
		List<LoyaltyMember> members = loyaltyMemberRepository.searchMembers(query);
		return members.stream()
				.filter(m -> Boolean.TRUE.equals(m.getActive()))
				.map(this::toMemberDTO)
				.collect(Collectors.toList());
	}

	public Optional<LoyaltyMemberDTO> getMemberByCardNumber(String cardNumber) {
		return loyaltyMemberRepository.findByCardNumber(cardNumber)
				.map(this::toMemberDTO);
	}

	public Optional<LoyaltyMemberDTO> getMemberById(Long id) {
		return loyaltyMemberRepository.findById(id)
				.map(this::toMemberDTO);
	}

	public Page<LoyaltyMemberDTO> getMembersPage(String search, Pageable pageable) {
		Page<LoyaltyMember> page = loyaltyMemberRepository.findAllBySearchTerm(
				search != null ? search : "", pageable);
		List<LoyaltyMemberDTO> dtos = page.getContent().stream()
				.map(this::toMemberDTO)
				.collect(Collectors.toList());
		return new PageImpl<>(dtos, pageable, page.getTotalElements());
	}

	@Transactional
	public LoyaltyMemberDTO createMember(CreateLoyaltyMemberRequestDTO request) {
		if (request.getMemberFunctionId() == null) {
			throw new IllegalArgumentException("La fonction du membre est obligatoire");
		}
		MemberFunction memberFunction = memberFunctionRepository.findById(request.getMemberFunctionId())
				.orElseThrow(() -> new IllegalArgumentException("Fonction du membre introuvable: " + request.getMemberFunctionId()));

		LoyaltyMember member = new LoyaltyMember();
		member.setCardNumber(generateCardNumber());
		member.setFirstName(request.getFirstName());
		member.setLastName(request.getLastName());
		member.setPhone(request.getPhone());
		member.setEmail(request.getEmail());
		member.setMemberFunction(memberFunction);
		member.setActive(true);
		member.setCreatedBy("System");
		member.setUpdatedBy("System");

		if (request.getBirthDate() != null && !request.getBirthDate().isBlank()) {
			try {
				member.setBirthDate(LocalDate.parse(request.getBirthDate(), DateTimeFormatter.ISO_LOCAL_DATE));
			} catch (Exception e) {
				log.warn("Could not parse birth date: {}", request.getBirthDate());
			}
		}

		if (request.getCustomerId() != null) {
			customerRepository.findById(request.getCustomerId())
					.ifPresent(member::setCustomer);
		}

		member = loyaltyMemberRepository.save(member);
		log.info("Created loyalty member: {}", member.getCardNumber());
		return toMemberDTO(member);
	}

	@Transactional
	public LoyaltyMemberDTO updateMember(Long id, CreateLoyaltyMemberRequestDTO request) {
		LoyaltyMember member = loyaltyMemberRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Loyalty member not found: " + id));

		if (request.getMemberFunctionId() == null) {
			throw new IllegalArgumentException("La fonction du membre est obligatoire");
		}
		MemberFunction memberFunction = memberFunctionRepository.findById(request.getMemberFunctionId())
				.orElseThrow(() -> new IllegalArgumentException("Fonction du membre introuvable: " + request.getMemberFunctionId()));

		member.setFirstName(request.getFirstName());
		member.setLastName(request.getLastName());
		member.setPhone(request.getPhone());
		member.setEmail(request.getEmail());
		member.setMemberFunction(memberFunction);
		member.setUpdatedBy("System");

		if (request.getBirthDate() != null && !request.getBirthDate().isBlank()) {
			try {
				member.setBirthDate(LocalDate.parse(request.getBirthDate(), DateTimeFormatter.ISO_LOCAL_DATE));
			} catch (Exception e) {
				log.warn("Could not parse birth date: {}", request.getBirthDate());
			}
		} else {
			member.setBirthDate(null);
		}

		if (request.getCustomerId() != null) {
			Customer customer = customerRepository.findById(request.getCustomerId()).orElse(null);
			member.setCustomer(customer);
		} else {
			member.setCustomer(null);
		}

		member = loyaltyMemberRepository.save(member);
		return toMemberDTO(member);
	}

	@Transactional
	public LoyaltyMemberDTO toggleMemberActive(Long id) {
		LoyaltyMember member = loyaltyMemberRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Loyalty member not found: " + id));
		member.setActive(!Boolean.TRUE.equals(member.getActive()));
		member.setUpdatedBy("System");
		member = loyaltyMemberRepository.save(member);
		return toMemberDTO(member);
	}

	@Transactional
	public LoyaltyMemberDTO linkCustomer(Long memberId, Long customerId) {
		LoyaltyMember member = loyaltyMemberRepository.findById(memberId)
				.orElseThrow(() -> new IllegalArgumentException("Loyalty member not found: " + memberId));

		if (customerId != null) {
			Customer customer = customerRepository.findById(customerId)
					.orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));
			member.setCustomer(customer);
		} else {
			member.setCustomer(null);
		}
		member.setUpdatedBy("System");
		member = loyaltyMemberRepository.save(member);
		return toMemberDTO(member);
	}

	// ───────────────────────────────────────────────────────────────
	// Points Operations
	// ───────────────────────────────────────────────────────────────

	/**
	 * Earn points after a completed sale.
	 * Points = floor(totalAmountTND × pointsPerDinar).
	 */
	@Transactional
	public int earnPoints(Long memberId, SalesHeader salesHeader, CashierSession cashierSession) {
		if (!isLoyaltyEnabled()) return 0;

		LoyaltyMember member = loyaltyMemberRepository.findById(memberId)
				.orElseThrow(() -> new IllegalArgumentException("Loyalty member not found: " + memberId));

		if (!Boolean.TRUE.equals(member.getActive())) {
			log.warn("Skipping earn points for inactive member: {}", member.getCardNumber());
			return 0;
		}

		LoyaltyProgram program = getActiveProgram().orElse(null);
		if (program == null) {
			log.warn("No active loyalty program found, skipping earnPoints");
			return 0;
		}

		double saleAmount = salesHeader.getTotalAmount() != null ? salesHeader.getTotalAmount() : 0.0;
		int earnedPoints = (int) Math.floor(saleAmount * program.getPointsPerDinar());

		if (earnedPoints <= 0) return 0;

		int balanceBefore = member.getLoyaltyPoints();
		int balanceAfter = balanceBefore + earnedPoints;

		member.setLoyaltyPoints(balanceAfter);
		member.setTotalPointsEarned(member.getTotalPointsEarned() + earnedPoints);
		member.setUpdatedBy("System");
		loyaltyMemberRepository.save(member);

		LoyaltyTransaction tx = new LoyaltyTransaction();
		tx.setLoyaltyMember(member);
		tx.setLoyaltyProgram(program);
		tx.setSalesHeader(salesHeader);
		tx.setCashierSession(cashierSession);
		tx.setType(LoyaltyTransactionType.EARNED);
		tx.setPoints(earnedPoints);
		tx.setBalanceBefore(balanceBefore);
		tx.setBalanceAfter(balanceAfter);
		tx.setDescription("Points earned from sale #" + salesHeader.getSalesNumber());
		tx.setCreatedBy("System");

		if (program.getPointsExpiryDays() != null) {
			tx.setExpiryDate(LocalDate.now().plusDays(program.getPointsExpiryDays()));
		}

		loyaltyTransactionRepository.save(tx);
		log.info("Earned {} points for member {} from sale {}", earnedPoints, member.getCardNumber(), salesHeader.getSalesNumber());
		return earnedPoints;
	}

	/**
	 * Redeem points at payment time.
	 * Validates the member has enough points and redemption is within program limits.
	 */
	@Transactional
	public double redeemPoints(Long memberId, int pointsToRedeem, SalesHeader salesHeader, CashierSession cashierSession) {
		if (!isLoyaltyEnabled() || pointsToRedeem <= 0) return 0.0;

		LoyaltyMember member = loyaltyMemberRepository.findById(memberId)
				.orElseThrow(() -> new IllegalArgumentException("Loyalty member not found: " + memberId));

		LoyaltyProgram program = getActiveProgram()
				.orElseThrow(() -> new IllegalStateException("No active loyalty program found"));

		if (member.getLoyaltyPoints() < pointsToRedeem) {
			throw new IllegalArgumentException(
					"Insufficient points: member has " + member.getLoyaltyPoints() + " but " + pointsToRedeem + " requested");
		}

		if (pointsToRedeem < program.getMinimumRedemptionPoints()) {
			throw new IllegalArgumentException(
					"Minimum redemption is " + program.getMinimumRedemptionPoints() + " points");
		}

		double deductionTND = (pointsToRedeem * program.getPointValueMillimes()) / 1000.0;

		// Validate against maximum redemption percentage
		if (salesHeader.getTotalAmount() != null && salesHeader.getTotalAmount() > 0) {
			double maxDeduction = salesHeader.getTotalAmount() * (program.getMaximumRedemptionPercentage() / 100.0);
			if (deductionTND > maxDeduction) {
				throw new IllegalArgumentException(
						"Redemption exceeds maximum allowed (" + program.getMaximumRedemptionPercentage() + "% of sale total)");
			}
		}

		int balanceBefore = member.getLoyaltyPoints();
		int balanceAfter = balanceBefore - pointsToRedeem;

		member.setLoyaltyPoints(balanceAfter);
		member.setTotalPointsRedeemed(member.getTotalPointsRedeemed() + pointsToRedeem);
		member.setUpdatedBy("System");
		loyaltyMemberRepository.save(member);

		LoyaltyTransaction tx = new LoyaltyTransaction();
		tx.setLoyaltyMember(member);
		tx.setLoyaltyProgram(program);
		tx.setSalesHeader(salesHeader);
		tx.setCashierSession(cashierSession);
		tx.setType(LoyaltyTransactionType.REDEEMED);
		tx.setPoints(pointsToRedeem);
		tx.setBalanceBefore(balanceBefore);
		tx.setBalanceAfter(balanceAfter);
		tx.setDescription("Points redeemed on sale #" + salesHeader.getSalesNumber() + " (= " + String.format("%.3f", deductionTND) + " TND)");
		tx.setCreatedBy("System");
		loyaltyTransactionRepository.save(tx);

		log.info("Redeemed {} points ({} TND) for member {} on sale {}", pointsToRedeem, deductionTND, member.getCardNumber(), salesHeader.getSalesNumber());
		return deductionTND;
	}

	/**
	 * Reverse the earned points when a sale is returned.
	 */
	@Transactional
	public void reversePoints(Long memberId, SalesHeader originalSale, ReturnHeader returnHeader) {
		if (!isLoyaltyEnabled()) return;

		LoyaltyMember member = loyaltyMemberRepository.findById(memberId)
				.orElseThrow(() -> new IllegalArgumentException("Loyalty member not found: " + memberId));

		// Find the original EARNED transaction for this sale
		Optional<LoyaltyTransaction> earnedTx = loyaltyTransactionRepository
				.findTopByLoyaltyMemberAndSalesHeaderAndTypeOrderByCreatedAtDesc(
						member, originalSale, LoyaltyTransactionType.EARNED);

		if (earnedTx.isEmpty()) {
			log.warn("No EARNED transaction found for member {} sale {}, skipping reversal",
					member.getCardNumber(), originalSale.getSalesNumber());
			return;
		}

		int pointsToReverse = earnedTx.get().getPoints();
		int balanceBefore = member.getLoyaltyPoints();
		int balanceAfter = Math.max(0, balanceBefore - pointsToReverse);

		member.setLoyaltyPoints(balanceAfter);
		member.setTotalPointsEarned(Math.max(0, member.getTotalPointsEarned() - pointsToReverse));
		member.setUpdatedBy("System");
		loyaltyMemberRepository.save(member);

		LoyaltyTransaction tx = new LoyaltyTransaction();
		tx.setLoyaltyMember(member);
		tx.setLoyaltyProgram(earnedTx.get().getLoyaltyProgram());
		tx.setSalesHeader(originalSale);
		tx.setReturnHeader(returnHeader);
		tx.setType(LoyaltyTransactionType.REVERSED);
		tx.setPoints(pointsToReverse);
		tx.setBalanceBefore(balanceBefore);
		tx.setBalanceAfter(balanceAfter);
		tx.setDescription("Points reversed due to return #" + returnHeader.getReturnNumber()
				+ " of sale #" + originalSale.getSalesNumber());
		tx.setCreatedBy("System");
		loyaltyTransactionRepository.save(tx);

		log.info("Reversed {} points for member {} due to return {}", pointsToReverse, member.getCardNumber(), returnHeader.getReturnNumber());
	}

	/**
	 * Admin manual point adjustment.
	 * delta is signed: positive = add, negative = remove.
	 */
	@Transactional
	public LoyaltyMemberDTO adjustPoints(Long memberId, int delta, String reason, String adjustedBy) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("Reason is required for point adjustment");
		}

		LoyaltyMember member = loyaltyMemberRepository.findById(memberId)
				.orElseThrow(() -> new IllegalArgumentException("Loyalty member not found: " + memberId));

		int balanceBefore = member.getLoyaltyPoints();
		int balanceAfter = Math.max(0, balanceBefore + delta);
		int actualDelta = balanceAfter - balanceBefore;

		member.setLoyaltyPoints(balanceAfter);
		member.setUpdatedBy(adjustedBy);

		if (delta > 0) {
			member.setTotalPointsEarned(member.getTotalPointsEarned() + actualDelta);
		} else if (delta < 0 && actualDelta < 0) {
			member.setTotalPointsRedeemed(member.getTotalPointsRedeemed() + Math.abs(actualDelta));
		}

		loyaltyMemberRepository.save(member);

		LoyaltyTransaction tx = new LoyaltyTransaction();
		tx.setLoyaltyMember(member);
		tx.setType(LoyaltyTransactionType.ADJUSTED);
		tx.setPoints(Math.abs(actualDelta));
		tx.setBalanceBefore(balanceBefore);
		tx.setBalanceAfter(balanceAfter);
		tx.setDescription("Manual adjustment by " + adjustedBy + ": " + reason + " (delta: " + (delta > 0 ? "+" : "") + delta + ")");
		tx.setCreatedBy(adjustedBy);
		loyaltyTransactionRepository.save(tx);

		log.info("Manual adjustment of {} points for member {} by {}. Reason: {}", delta, member.getCardNumber(), adjustedBy, reason);
		return toMemberDTO(member);
	}

	// ───────────────────────────────────────────────────────────────
	// Loyalty Programs
	// ───────────────────────────────────────────────────────────────

	public List<LoyaltyProgramDTO> getAllPrograms() {
		LocalDate today = LocalDate.now();
		return loyaltyProgramRepository.findAllByOrderByStartDateDesc().stream()
				.map(p -> toProgramDTO(p, today))
				.collect(Collectors.toList());
	}

	@Transactional
	public LoyaltyProgramDTO activateNewProgram(LoyaltyProgram newProgram) {
		LocalDate today = LocalDate.now();

		// Close any currently-active programs (ignore endDate — may be null or set in future)
		for (LoyaltyProgram current : loyaltyProgramRepository.findByActiveTrue()) {
			LocalDate closeOn = today;
			if (newProgram.getStartDate() != null && !newProgram.getStartDate().isAfter(today)) {
				closeOn = newProgram.getStartDate().minusDays(1);
				if (closeOn.isBefore(current.getStartDate() != null ? current.getStartDate() : closeOn)) {
					closeOn = today;
				}
			}
			current.setEndDate(closeOn);
			current.setActive(false);
			current.setUpdatedBy("System");
			loyaltyProgramRepository.save(current);
			log.info("Closed loyalty program: {} (endDate={})", current.getProgramCode(), closeOn);
		}

		newProgram.setActive(true);
		newProgram.setCreatedBy("System");
		newProgram.setUpdatedBy("System");

		if (newProgram.getStartDate() == null) {
			newProgram.setStartDate(today);
		}

		LoyaltyProgram saved = loyaltyProgramRepository.save(newProgram);
		log.info("Activated new loyalty program: {}", saved.getProgramCode());
		return toProgramDTO(saved, today);
	}

	@Transactional
	public LoyaltyProgramDTO updateProgram(Long id, LoyaltyProgram patch) {
		LoyaltyProgram existing = loyaltyProgramRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Loyalty program not found: " + id));

		long txCount = loyaltyTransactionRepository.countByLoyaltyProgram(existing);
		boolean canEditAll = (txCount == 0);

		// Always-editable (safe) fields
		if (patch.getName() != null && !patch.getName().isBlank()) {
			existing.setName(patch.getName());
		}
		existing.setDescription(patch.getDescription());
		existing.setEndDate(patch.getEndDate());

		// Rate / policy fields — only when no transactions reference this program
		if (canEditAll) {
			if (patch.getProgramCode() != null && !patch.getProgramCode().isBlank()) {
				existing.setProgramCode(patch.getProgramCode());
			}
			if (patch.getStartDate() != null) {
				existing.setStartDate(patch.getStartDate());
			}
			if (patch.getPointsPerDinar() != null) {
				existing.setPointsPerDinar(patch.getPointsPerDinar());
			}
			if (patch.getPointValueMillimes() != null) {
				existing.setPointValueMillimes(patch.getPointValueMillimes());
			}
			if (patch.getMinimumRedemptionPoints() != null) {
				existing.setMinimumRedemptionPoints(patch.getMinimumRedemptionPoints());
			}
			if (patch.getMaximumRedemptionPercentage() != null) {
				existing.setMaximumRedemptionPercentage(patch.getMaximumRedemptionPercentage());
			}
			existing.setPointsExpiryDays(patch.getPointsExpiryDays());
		} else {
			// Reject if the caller tried to change a locked field (value differs from existing)
			rejectLockedChange("programCode", existing.getProgramCode(), patch.getProgramCode());
			rejectLockedChange("startDate", existing.getStartDate(), patch.getStartDate());
			rejectLockedChange("pointsPerDinar", existing.getPointsPerDinar(), patch.getPointsPerDinar());
			rejectLockedChange("pointValueMillimes", existing.getPointValueMillimes(), patch.getPointValueMillimes());
			rejectLockedChange("minimumRedemptionPoints", existing.getMinimumRedemptionPoints(), patch.getMinimumRedemptionPoints());
			rejectLockedChange("maximumRedemptionPercentage", existing.getMaximumRedemptionPercentage(), patch.getMaximumRedemptionPercentage());
			rejectLockedChange("pointsExpiryDays", existing.getPointsExpiryDays(), patch.getPointsExpiryDays());
		}

		existing.setUpdatedBy("System");
		LoyaltyProgram saved = loyaltyProgramRepository.save(existing);
		log.info("Updated loyalty program: {} (canEditAll={})", saved.getProgramCode(), canEditAll);
		return toProgramDTO(saved, LocalDate.now());
	}

	@Transactional
	public void deleteProgram(Long id) {
		LoyaltyProgram existing = loyaltyProgramRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Loyalty program not found: " + id));
		long txCount = loyaltyTransactionRepository.countByLoyaltyProgram(existing);
		if (txCount > 0) {
			throw new IllegalStateException(
					"Cannot delete program '" + existing.getProgramCode() + "': it has " + txCount + " related transaction(s).");
		}
		loyaltyProgramRepository.delete(existing);
		log.info("Deleted loyalty program: {}", existing.getProgramCode());
	}

	@Transactional
	public LoyaltyProgramDTO deactivateProgram(Long id) {
		LoyaltyProgram existing = loyaltyProgramRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Loyalty program not found: " + id));
		LocalDate today = LocalDate.now();
		if (!Boolean.TRUE.equals(existing.getActive())
				&& existing.getEndDate() != null && existing.getEndDate().isBefore(today)) {
			throw new IllegalStateException("Program is already inactive.");
		}
		existing.setActive(false);
		if (existing.getEndDate() == null || existing.getEndDate().isAfter(today)) {
			existing.setEndDate(today);
		}
		existing.setUpdatedBy("System");
		LoyaltyProgram saved = loyaltyProgramRepository.save(existing);
		log.info("Deactivated loyalty program: {}", saved.getProgramCode());
		return toProgramDTO(saved, today);
	}

	private void rejectLockedChange(String field, Object current, Object patched) {
		if (patched == null) return;
		if (current == null || !current.equals(patched)) {
			throw new IllegalStateException(
					"Field '" + field + "' is locked: this program already has loyalty transactions.");
		}
	}

	private LoyaltyProgramDTO toProgramDTO(LoyaltyProgram p, LocalDate today) {
		long txCount = loyaltyTransactionRepository.countByLoyaltyProgram(p);
		boolean started = p.getStartDate() != null && !p.getStartDate().isAfter(today);
		boolean notEnded = p.getEndDate() == null || !p.getEndDate().isBefore(today);
		boolean current = Boolean.TRUE.equals(p.getActive()) && started && notEnded;
		boolean ended = p.getEndDate() != null && p.getEndDate().isBefore(today);

		LoyaltyProgramDTO dto = new LoyaltyProgramDTO();
		dto.setId(p.getId());
		dto.setProgramCode(p.getProgramCode());
		dto.setName(p.getName());
		dto.setDescription(p.getDescription());
		dto.setStartDate(p.getStartDate() != null ? p.getStartDate().toString() : null);
		dto.setEndDate(p.getEndDate() != null ? p.getEndDate().toString() : null);
		dto.setPointsPerDinar(p.getPointsPerDinar());
		dto.setPointValueMillimes(p.getPointValueMillimes());
		dto.setMinimumRedemptionPoints(p.getMinimumRedemptionPoints());
		dto.setMaximumRedemptionPercentage(p.getMaximumRedemptionPercentage());
		dto.setPointsExpiryDays(p.getPointsExpiryDays());
		dto.setActive(p.getActive());
		dto.setTransactionCount(txCount);
		dto.setCurrent(current);
		dto.setCanEditAll(txCount == 0);
		dto.setCanEditSafe(!ended);
		dto.setCanDelete(txCount == 0);
		dto.setCanDeactivate(current);
		return dto;
	}

	// ───────────────────────────────────────────────────────────────
	// Transaction History
	// ───────────────────────────────────────────────────────────────

	public Page<LoyaltyTransactionDTO> getTransactionHistory(Long memberId, Pageable pageable) {
		LoyaltyMember member = loyaltyMemberRepository.findById(memberId)
				.orElseThrow(() -> new IllegalArgumentException("Loyalty member not found: " + memberId));

		Page<LoyaltyTransaction> txPage = loyaltyTransactionRepository
				.findByLoyaltyMemberOrderByCreatedAtDesc(member, pageable);

		List<LoyaltyTransactionDTO> dtos = txPage.getContent().stream()
				.map(this::toTransactionDTO)
				.collect(Collectors.toList());

		return new PageImpl<>(dtos, pageable, txPage.getTotalElements());
	}

	public List<LoyaltyTransactionDTO> getAllTransactions(Long memberId) {
		LoyaltyMember member = loyaltyMemberRepository.findById(memberId)
				.orElseThrow(() -> new IllegalArgumentException("Loyalty member not found: " + memberId));
		return loyaltyTransactionRepository.findByLoyaltyMemberOrderByCreatedAtDesc(member)
				.stream().map(this::toTransactionDTO).collect(Collectors.toList());
	}

	// ───────────────────────────────────────────────────────────────
	// Helpers
	// ───────────────────────────────────────────────────────────────

	public double getPointsValueInDinars(int points) {
		return getActiveProgram()
				.map(p -> (points * p.getPointValueMillimes()) / 1000.0)
				.orElse(0.0);
	}

	private String generateCardNumber() {
		Integer maxSeq = loyaltyMemberRepository.findMaxCardSequence();
		int next = (maxSeq != null ? maxSeq : 0) + 1;
		return String.format("LYL-%06d", next);
	}

	public LoyaltyMemberDTO toMemberDTO(LoyaltyMember m) {
		LoyaltyMemberDTO dto = new LoyaltyMemberDTO();
		dto.setId(m.getId());
		dto.setCardNumber(m.getCardNumber());
		dto.setFirstName(m.getFirstName());
		dto.setLastName(m.getLastName());
		dto.setFullName(m.getFirstName() + " " + m.getLastName());
		dto.setPhone(m.getPhone());
		dto.setEmail(m.getEmail());
		dto.setBirthDate(m.getBirthDate() != null ? m.getBirthDate().toString() : null);
		dto.setLoyaltyPoints(m.getLoyaltyPoints());
		dto.setTotalPointsEarned(m.getTotalPointsEarned());
		dto.setTotalPointsRedeemed(m.getTotalPointsRedeemed());
		dto.setPointsValueDinars(getPointsValueInDinars(m.getLoyaltyPoints() != null ? m.getLoyaltyPoints() : 0));
		dto.setActive(m.getActive());
		dto.setEnrolledAt(m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);

		if (m.getCustomer() != null) {
			dto.setCustomerId(m.getCustomer().getId());
			dto.setCustomerCode(m.getCustomer().getCustomerCode());
			dto.setCustomerName(m.getCustomer().getName());
		}
		if (m.getMemberFunction() != null) {
			dto.setMemberFunctionId(m.getMemberFunction().getId());
			dto.setMemberFunctionName(m.getMemberFunction().getName());
		}
		return dto;
	}

	private LoyaltyTransactionDTO toTransactionDTO(LoyaltyTransaction tx) {
		LoyaltyTransactionDTO dto = new LoyaltyTransactionDTO();
		dto.setId(tx.getId());
		dto.setType(tx.getType() != null ? tx.getType().name() : null);
		dto.setPoints(tx.getPoints());
		dto.setBalanceBefore(tx.getBalanceBefore());
		dto.setBalanceAfter(tx.getBalanceAfter());
		dto.setDescription(tx.getDescription());
		dto.setExpiryDate(tx.getExpiryDate() != null ? tx.getExpiryDate().toString() : null);
		dto.setCreatedAt(tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : null);
		dto.setCreatedBy(tx.getCreatedBy());

		if (tx.getSalesHeader() != null) {
			dto.setSalesHeaderId(tx.getSalesHeader().getId());
			dto.setSalesNumber(tx.getSalesHeader().getSalesNumber());
		}
		if (tx.getReturnHeader() != null) {
			dto.setReturnHeaderId(tx.getReturnHeader().getId());
			dto.setReturnNumber(tx.getReturnHeader().getReturnNumber());
		}
		if (tx.getLoyaltyProgram() != null) {
			dto.setProgramName(tx.getLoyaltyProgram().getName());
		}
		if (tx.getLoyaltyMember() != null) {
			dto.setMemberId(tx.getLoyaltyMember().getId());
			dto.setMemberCardNumber(tx.getLoyaltyMember().getCardNumber());
			dto.setMemberFullName(tx.getLoyaltyMember().getFirstName() + " " + tx.getLoyaltyMember().getLastName());
		}
		return dto;
	}

	// ───────────────────────────────────────────────────────────────
	// Cross-member transactions (admin transactions page)
	// ───────────────────────────────────────────────────────────────

	public Page<LoyaltyTransactionDTO> getAllTransactionsFiltered(
			String type, String dateFrom, String dateTo, Long memberId, String search, Pageable pageable) {

		LoyaltyTransactionType typeEnum = null;
		if (type != null && !type.isBlank()) {
			try { typeEnum = LoyaltyTransactionType.valueOf(type); } catch (IllegalArgumentException ignored) {}
		}

		LocalDateTime from = null;
		if (dateFrom != null && !dateFrom.isBlank()) {
			try { from = LocalDate.parse(dateFrom).atStartOfDay(); } catch (Exception ignored) {}
		}

		LocalDateTime to = null;
		if (dateTo != null && !dateTo.isBlank()) {
			try { to = LocalDate.parse(dateTo).atTime(23, 59, 59); } catch (Exception ignored) {}
		}

		String searchParam = (search == null || search.isBlank()) ? null : search.trim();

		Page<LoyaltyTransaction> page = loyaltyTransactionRepository
				.findAllFiltered(typeEnum, from, to, memberId, searchParam, pageable);

		List<LoyaltyTransactionDTO> dtos = page.getContent().stream()
				.map(this::toTransactionDTO)
				.collect(Collectors.toList());

		return new PageImpl<>(dtos, pageable, page.getTotalElements());
	}
}
