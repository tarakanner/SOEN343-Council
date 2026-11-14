//I, Anais Perron, certify that Codex helped me in generating the Template of this display. However, it has been heavily modified by myself
package com.council.tenderapp.technicalservices.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.council.tenderapp.ord.dtos.OrderItemRequestDto;
import com.council.tenderapp.ord.dtos.OrderResponseDto;
import com.council.tenderapp.ord.dtos.PlaceOrderRequestDto;
import com.council.tenderapp.ord.services.CustomerOrderService;
import com.council.tenderapp.prc.service.PricingService;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

class PayPalCheckoutServiceTest {
    @Test
    void chargesServerQuoteAndRecordsVerifiedCapture() throws Exception {
        AtomicReference<String> createBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/oauth2/token", exchange -> respond(exchange, "{\"access_token\":\"test-token\"}"));
        server.createContext("/v2/checkout/orders/ORDER-1/capture", exchange -> respond(exchange,
                "{\"purchase_units\":[{\"payments\":{\"captures\":[{\"id\":\"CAPTURE-1\",\"status\":\"COMPLETED\",\"amount\":{\"currency_code\":\"CAD\",\"value\":\"25.10\"}}]}}]}"));
        server.createContext("/v2/checkout/orders", exchange -> {
            createBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"id\":\"ORDER-1\",\"links\":[{\"rel\":\"payer-action\",\"href\":\"https://www.sandbox.paypal.com/checkoutnow?token=ORDER-1\"}]}");
        });
        server.start();
        try {
            CustomerOrderService orders = mock(CustomerOrderService.class);
            PlaceOrderRequestDto request = new PlaceOrderRequestDto(2L, List.of(new OrderItemRequestDto(3L, 1, List.of())));
            when(orders.quoteOrder(4L, request)).thenReturn(new PricingService.Quote(
                    new BigDecimal("12.00"), new BigDecimal("2.00"), new BigDecimal("1.50"),
                    new BigDecimal("1.60"), new BigDecimal("10.00"), new BigDecimal("25.10")));
            OrderResponseDto receipt = new OrderResponseDto(8L, 2L, "placed", new BigDecimal("25.10"),
                    Instant.now(), "PAYPAL-CAPTURE-1", List.of());
            when(orders.placeOrder(eq(4L), eq(request), eq(new PayPalPayment("CAPTURE-1", new BigDecimal("25.10")))))
                    .thenReturn(receipt);
            PayPalCheckoutService paypal = new PayPalCheckoutService(orders, new ObjectMapper(), mock(TenderCash.class), "client", "secret",
                    "http://127.0.0.1:" + server.getAddress().getPort());

            var created = paypal.create(4L, List.of(request), "tender-mobile-app://customer/checkout/confirm-order");
            assertThat(created.orderId()).isEqualTo("ORDER-1");
            assertThat(created.approvalUrl()).contains("ORDER-1");
            assertThat(new ObjectMapper().readTree(createBody.get()).path("purchase_units").path(0)
                        .path("amount").path("value").asText()).isEqualTo("25.10");
                assertThat(paypal.capture(4L, "ORDER-1")).containsExactly(receipt);
                verify(orders).placeOrder(4L, request, new PayPalPayment("CAPTURE-1", new BigDecimal("25.10")));
            } finally {
                server.stop(0);
            }
        }

        private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        }
    }
