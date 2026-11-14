package com.council.tenderapp.technicalservices.payment;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

/** Orders fully covered by Tender Cash: no cash moves. TenderCash records the spend when the order is saved. */
@Component
public class TenderCashPayment implements PaymentStrategy {

    @Override
    public PaymentResult pay(Long customerId, BigDecimal amountDue) {
        if (amountDue.signum() != 0) {
            return PaymentResult.declined("Tender Cash does not cover the full order");
        }
        return PaymentResult.approved("TENDER-CASH");
    }

    @Override
    public String label() {
        return "Tender Cash";
    }
}
