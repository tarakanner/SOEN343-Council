package com.council.tenderapp.technicalservices.payment;

import java.math.BigDecimal;

import com.council.tenderapp.ord.exceptions.InvalidOrderException;

/**
 * A PayPal capture that PayPalCheckoutService already verified. Paying with it only succeeds
 * if the order still costs exactly the captured amount.
 */
public record PayPalPayment(String captureId, BigDecimal capturedAmount) implements PaymentStrategy {

    @Override
    public PaymentResult pay(Long customerId, BigDecimal amountDue) {
        if (amountDue.compareTo(capturedAmount) != 0) {
            throw new InvalidOrderException("Order price changed after PayPal approval");
        }
        return PaymentResult.approved("PAYPAL-" + captureId);
    }

    @Override
    public String label() {
        return "PayPal";
    }
}
