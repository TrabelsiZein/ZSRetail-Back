package com.digithink.zsretail.core.repository;

import java.util.Optional;

import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;

public interface PaymentMethodRepository extends _BaseRepository<PaymentMethod, Long> {

	Optional<PaymentMethod> findByCode(String code);

	Optional<PaymentMethod> findByType(PaymentMethodType methodType);

}
