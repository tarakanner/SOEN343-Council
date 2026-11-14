package com.council.tenderapp.technicalservices.payment;

import java.math.BigDecimal;

/**
 * Strategy for settling the cash part of an order after Tender Cash is applied.
 * CustomerOrderService is the context: it prices the order, then delegates payment to one strategy.
 */
public interface PaymentStrategy {

    PaymentResult pay(Long customerId, BigDecimal amountDue);

    String label();
}
