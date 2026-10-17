package com.digithink.zsretail.store.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.model.ReturnVoucher;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.store.repository.ReturnVoucherRepository;

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

		// Check if fully used (3.1.0: on the millimes, as stored since then; a voucher stored before keeps 4 decimals)
		if (voucher.getUsedAmount() != null
				&& Quantities.roundAmount(voucher.getUsedAmount()) >= Quantities.roundAmount(voucher.getVoucherAmount())) {
			return false;
		}

		// Check status
		if (voucher.getStatus() != TransactionStatus.PENDING) {
			return false;
		}

		return true;
	}

	/**
	 * Get remaining amount on voucher; 3.1.0 (test log I-07): to the millime, so a voucher stored before with 21.3129
	 * offers 21.313, the amount its payment is stored with.
	 */
	public double getRemainingAmount(ReturnVoucher voucher) {
		if (voucher == null) {
			return 0.0;
		}

		double used = voucher.getUsedAmount() != null ? voucher.getUsedAmount() : 0.0;
		return Quantities.roundAmount(Quantities.roundAmount(voucher.getVoucherAmount()) - Quantities.roundAmount(used));
	}

	/**
	 * Use voucher amount (called when voucher is used as payment)
	 */
	@Transactional(rollbackFor = Exception.class)
	public ReturnVoucher useVoucherAmount(String voucherNumber, double requestedAmount) throws Exception {
		double amount = Quantities.roundAmount(requestedAmount); // 3.1.0 (I-07): the debit is the payment's amount, as stored
		ReturnVoucher voucher = returnVoucherRepository.findByVoucherNumber(voucherNumber)
			.orElseThrow(() -> new IllegalArgumentException("Voucher not found: " + voucherNumber));

		if (!isVoucherValid(voucher)) {
			throw new IllegalStateException("Voucher is not valid (expired or fully used)");
		}

		double remaining = getRemainingAmount(voucher);
		if (amount > remaining) {
			throw new IllegalArgumentException("Amount (" + amount + ") exceeds remaining voucher amount (" + remaining + ")");
		}

		double newUsedAmount = Quantities
				.roundAmount((voucher.getUsedAmount() != null ? voucher.getUsedAmount() : 0.0) + amount);
		voucher.setUsedAmount(newUsedAmount);

		// If fully used, mark as completed
		if (newUsedAmount >= Quantities.roundAmount(voucher.getVoucherAmount())) {
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

