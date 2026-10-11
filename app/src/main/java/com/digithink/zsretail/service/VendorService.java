package com.digithink.zsretail.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.model.Vendor;
import com.digithink.zsretail.repository.VendorRepository;
import com.digithink.zsretail.repository._BaseRepository;

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
public class VendorService extends _BaseService<Vendor, Long> {

	@Autowired
	private VendorRepository vendorRepository;

	@Override
	protected _BaseRepository<Vendor, Long> getRepository() {
		return vendorRepository;
	}

	public VendorRepository getVendorRepository() {
		return vendorRepository;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Vendor save(Vendor vendor) throws Exception {
		if (vendor.getName() == null || vendor.getName().trim().isEmpty()) {
			throw new IllegalArgumentException("Vendor name is required");
		}
		if (vendor.getPhone() == null || vendor.getPhone().trim().isEmpty()) {
			throw new IllegalArgumentException("Vendor phone is required");
		}

		if (vendor.getId() == null
				&& (vendor.getVendorCode() == null || vendor.getVendorCode().trim().isEmpty())) {
			vendor.setVendorCode(generateVendorCode());
		} else if (vendor.getId() == null && vendor.getVendorCode() != null
				&& !vendor.getVendorCode().trim().isEmpty()) {
			if (vendorRepository.findByVendorCode(vendor.getVendorCode()).isPresent()) {
				throw new IllegalArgumentException("Vendor code already exists: " + vendor.getVendorCode());
			}
		}

		return super.save(vendor);
	}

	private String generateVendorCode() {
		java.time.LocalDateTime todayStart = java.time.LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
		long count = vendorRepository.countByCreatedAtGreaterThanEqual(todayStart);
		String dateStr = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
		String code = "VEND-" + dateStr + "-" + String.format("%03d", count + 1);
		log.info("Generated vendor code: " + code);
		return code;
	}

	public Page<Vendor> findVendorsPaginated(int page, int size, String searchTerm, String statusFilter) {
		PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "name"));

		Specification<Vendor> spec = (root, query, cb) -> {
			java.util.List<javax.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();

			if (statusFilter != null && !statusFilter.equals("all")) {
				if (statusFilter.equals("active")) {
					predicates.add(cb.equal(root.get("active"), true));
				} else if (statusFilter.equals("inactive")) {
					predicates.add(cb.equal(root.get("active"), false));
				}
			}

			if (searchTerm != null && !searchTerm.trim().isEmpty()) {
				String pattern = "%" + searchTerm.toLowerCase() + "%";
				predicates.add(cb.or(
						cb.like(cb.lower(root.get("name")), pattern),
						cb.like(cb.lower(root.get("vendorCode")), pattern),
						cb.like(cb.lower(root.get("phone")), pattern),
						cb.like(cb.lower(root.get("email")), pattern),
						cb.like(cb.lower(root.get("city")), pattern),
						cb.like(cb.lower(root.get("country")), pattern),
						cb.like(cb.lower(root.get("taxId")), pattern)));
			}

			return predicates.isEmpty() ? null : cb.and(predicates.toArray(new javax.persistence.criteria.Predicate[0]));
		};

		return vendorRepository.findAll(spec, pageable);
	}
}
