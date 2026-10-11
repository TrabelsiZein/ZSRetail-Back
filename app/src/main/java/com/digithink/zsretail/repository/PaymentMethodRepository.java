package com.digithink.zsretail.repository;

import java.util.Optional;

import com.digithink.zsretail.model.PaymentMethod;
import com.digithink.zsretail.model.enumeration.PaymentMethodType;

public interface PaymentMethodRepository extends _BaseRepository<PaymentMethod, Long> {

	Optional<PaymentMethod> findByCode(String code);

	Optional<PaymentMethod> findByType(PaymentMethodType methodType);

}
