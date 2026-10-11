package com.digithink.zsretail.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.model.ReturnVoucher;
import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.digithink.zsretail.repository.ReturnVoucherRepository;
import com.digithink.zsretail.repository._BaseRepository;

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
public class ReturnVoucherService extends _BaseService<ReturnVoucher, Long> {

	@Autowired
	private ReturnVoucherRepository returnVoucherRepository;

	@Override
	protected _BaseRepository<ReturnVoucher, Long> getRepository() {
		return returnVoucherRepository;
	}

	/**
	 * Get voucher by voucher number
	 */
	public Optional<ReturnVoucher> findByVoucherNumber(String voucherNumber) {
		return returnVoucherRepository.findByVoucherNumber(voucherNumber);
	}

	/**
	 * Check if voucher is valid (not expired and not fully used)
	 */
	public boolean isVoucherValid(ReturnVoucher voucher) {
		if (voucher == null) {
			return false;
		}

		// Check if expired
		if (LocalDate.now().isAfter(voucher.getExpiryDate())) {
			return false;
		}

		// Check if fully used
		if (voucher.getUsedAmount() != null && voucher.getUsedAmount() >= voucher.getVoucherAmount()) {
			return false;
		}

		// Check status
		if (voucher.getStatus() != TransactionStatus.PENDING) {
			return false;
		}

		return true;
	}

	/**
	 * Get remaining amount on voucher
	 */
	public double getRemainingAmount(ReturnVoucher voucher) {
		if (voucher == null) {
			return 0.0;
		}

		double used = voucher.getUsedAmount() != null ? voucher.getUsedAmount() : 0.0;
		return voucher.getVoucherAmount() - used;
	}

	/**
	 * Use voucher amount (called when voucher is used as payment)
	 */
	@Transactional(rollbackFor = Exception.class)
	public ReturnVoucher useVoucherAmount(String voucherNumber, double amount) throws Exception {
		ReturnVoucher voucher = returnVoucherRepository.findByVoucherNumber(voucherNumber)
			.orElseThrow(() -> new IllegalArgumentException("Voucher not found: " + voucherNumber));

		if (!isVoucherValid(voucher)) {
			throw new IllegalStateException("Voucher is not valid (expired or fully used)");
		}

		double remaining = getRemainingAmount(voucher);
		if (amount > remaining) {
			throw new IllegalArgumentException("Amount (" + amount + ") exceeds remaining voucher amount (" + remaining + ")");
		}

		double newUsedAmount = (voucher.getUsedAmount() != null ? voucher.getUsedAmount() : 0.0) + amount;
		voucher.setUsedAmount(newUsedAmount);

		// If fully used, mark as completed
		if (newUsedAmount >= voucher.getVoucherAmount()) {
			voucher.setStatus(TransactionStatus.COMPLETED);
		}

		voucher = save(voucher);
		log.info("Voucher amount used: " + voucherNumber + ", amount: " + amount + ", remaining: " + getRemainingAmount(voucher));

		return voucher;
	}

	/**
	 * Get valid vouchers for a customer
	 */
	public List<ReturnVoucher> getValidVouchersForCustomer(Long customerId) {
		// This would need a customer parameter in the repository
		// For now, return all valid vouchers
		return returnVoucherRepository.findByStatusAndExpiryDateAfter(TransactionStatus.PENDING, LocalDate.now());
	}

	/**
	 * Get expired vouchers
	 */
	public List<ReturnVoucher> getExpiredVouchers() {
		return returnVoucherRepository.findByStatusAndExpiryDateBefore(TransactionStatus.PENDING, LocalDate.now());
	}
}

