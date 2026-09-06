package com.digithink.zsretail.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.ReturnVoucher;
import com.digithink.zsretail.model.enumeration.TransactionStatus;

@Repository
public interface ReturnVoucherRepository extends _BaseRepository<ReturnVoucher, Long> {
	
	Optional<ReturnVoucher> findByVoucherNumber(String voucherNumber);
	
	List<ReturnVoucher> findByCustomer(Customer customer);
	
	List<ReturnVoucher> findByStatus(TransactionStatus status);
	
	List<ReturnVoucher> findByStatusAndExpiryDateAfter(TransactionStatus status, LocalDate expiryDate);
	
	List<ReturnVoucher> findByStatusAndExpiryDateBefore(TransactionStatus status, LocalDate expiryDate);
}

