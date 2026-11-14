package com.council.tenderapp.technicalservices.payment;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.council.tenderapp.ord.exceptions.InvalidOrderException;
import com.council.tenderapp.technicalservices.logging.AppLogService;

/** Simulated card payment; approves every positive amount. Disabled unless app.payments.allow-mock=true. */
@Component
public class MockCardPayment implements PaymentStrategy {

    private final AppLogService logService;
    private final boolean enabled;

    public MockCardPayment(AppLogService logService, @Value("${app.payments.allow-mock:false}") boolean enabled) {
        this.logService = logService;
        this.enabled = enabled;
    }

    @Override
    public PaymentResult pay(Long customerId, BigDecimal amountDue) {
        if (!enabled) {
            throw new InvalidOrderException("Use PayPal checkout for the remaining payment");
        }
        if (amountDue == null || amountDue.signum() <= 0) {
            return PaymentResult.declined("Amount must be greater than 0");
        }
        String reference = "MOCK-" + UUID.randomUUID();
        logService.info("Mock payment approved: customer " + customerId + " charged " + amountDue + " (" + reference + ")");
        return PaymentResult.approved(reference);
    }

    @Override
    public String label() {
        return "Mock card";
    }
}
