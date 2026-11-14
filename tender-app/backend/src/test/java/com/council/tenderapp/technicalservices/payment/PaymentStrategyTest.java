package com.council.tenderapp.technicalservices.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.council.tenderapp.ord.exceptions.InvalidOrderException;
import com.council.tenderapp.technicalservices.logging.AppLogService;

class PaymentStrategyTest {

    @Test
    void everyStrategyIsInterchangeableBehindTheInterface() {
        List<PaymentStrategy> strategies = List.of(new TenderCashPayment(),
                new MockCardPayment(mock(AppLogService.class), true), new PayPalPayment("C-1", new BigDecimal("5.00")));
        assertThat(strategies).extracting(PaymentStrategy::label).containsExactly("Tender Cash", "Mock card", "PayPal");
    }

    @Test
    void tenderCashOnlySettlesFullyCoveredOrders() {
        assertThat(new TenderCashPayment().pay(1L, new BigDecimal("0.00")))
                .isEqualTo(PaymentResult.approved("TENDER-CASH"));
        assertThat(new TenderCashPayment().pay(1L, new BigDecimal("0.01")).approved()).isFalse();
    }

    @Test
    void mockCardApprovesPositiveAmountsWhenEnabled() {
        MockCardPayment card = new MockCardPayment(mock(AppLogService.class), true);
        PaymentResult result = card.pay(1L, new BigDecimal("9.99"));
        assertThat(result.approved()).isTrue();
        assertThat(result.reference()).startsWith("MOCK-");
        assertThat(card.pay(1L, BigDecimal.ZERO).approved()).isFalse();
    }

    @Test
    void mockCardIsRefusedUnlessEnabled() {
        MockCardPayment card = new MockCardPayment(mock(AppLogService.class), false);
        assertThatThrownBy(() -> card.pay(1L, new BigDecimal("9.99")))
                .isInstanceOf(InvalidOrderException.class).hasMessageContaining("PayPal checkout");
    }

    @Test
    void payPalSettlesOnlyTheCapturedAmount() {
        PayPalPayment payPal = new PayPalPayment("C-1", new BigDecimal("25.10"));
        assertThat(payPal.pay(1L, new BigDecimal("25.1"))).isEqualTo(PaymentResult.approved("PAYPAL-C-1"));
        assertThatThrownBy(() -> payPal.pay(1L, new BigDecimal("25.11")))
                .isInstanceOf(InvalidOrderException.class).hasMessageContaining("price changed");
    }
}
