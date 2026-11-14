package com.council.tenderapp.technicalservices.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.council.tenderapp.ord.entities.MenuItem;
import com.council.tenderapp.ord.entities.Restaurant;
import com.council.tenderapp.ord.repositories.MenuItemRepository;
import com.council.tenderapp.ord.repositories.RestaurantRepository;
import com.council.tenderapp.reg.dtos.LoginRequest;
import com.council.tenderapp.reg.dtos.RegisterRequest;
import com.council.tenderapp.reg.entities.Role;
import com.council.tenderapp.reg.services.CustomerProfileService;
import com.council.tenderapp.reg.services.LoginService;
import com.council.tenderapp.reg.services.RegistrationService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Full stack: HTTP request -> security -> controllers -> CustomerOrderService (strategy context)
 * -> payment strategy -> database. PayPal's API is replaced by a local stub server.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:payment-e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
class PaymentStrategyEndToEndTest {

    private static HttpServer payPal;

    @DynamicPropertySource
    static void payPalStub(DynamicPropertyRegistry registry) throws IOException {
        payPal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        payPal.createContext("/v1/oauth2/token", exchange -> respond(exchange, "{\"access_token\":\"e2e-token\"}"));
        payPal.createContext("/v2/checkout/orders/PP-E2E/capture", exchange -> respond(exchange,
                "{\"purchase_units\":[{\"payments\":{\"captures\":[{\"id\":\"CAPTURE-E2E\",\"status\":\"COMPLETED\","
                        + "\"amount\":{\"currency_code\":\"CAD\",\"value\":\"20.00\"}}]}}]}"));
        payPal.createContext("/v2/checkout/orders", exchange -> respond(exchange,
                "{\"id\":\"PP-E2E\",\"links\":[{\"rel\":\"payer-action\",\"href\":\"https://paypal.test/approve?token=PP-E2E\"}]}"));
        payPal.start();
        registry.add("app.paypal.base-url", () -> "http://127.0.0.1:" + payPal.getAddress().getPort());
        registry.add("app.paypal.client-id", () -> "e2e-client");
        registry.add("app.paypal.client-secret", () -> "e2e-secret");
    }

    @AfterAll
    static void stopPayPal() {
        payPal.stop(0);
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RegistrationService registration;
    @Autowired private LoginService login;
    @Autowired private RestaurantRepository restaurants;
    @Autowired private MenuItemRepository menuItems;
    // H2 cannot parse the Postgres-only ON CONFLICT upsert; the profile row is seeded below instead.
    @MockitoSpyBean private CustomerProfileService customerProfiles;

    private Long customerId;
    private String token;
    private Long restaurantId;
    private Long menuItemId;

    // Restaurant and customer share a location: delivery 0.00, service 1.50, item 20.00 -> total 21.50 (215 tenders).
    @BeforeEach
    void setUp() {
        doNothing().when(customerProfiles).ensureExists(any());
        String email = "pay-" + UUID.randomUUID() + "@test.ca";
        customerId = registration.register(new RegisterRequest("Pay Tester", email, "secret123", "514-555-0199", Role.CUSTOMER)).id();
        token = login.login(new LoginRequest(email, "secret123")).token();
        jdbc.update("MERGE INTO customer_profiles (user_id, td_cash_balance, address, latitude, longitude) KEY (user_id) VALUES (?, ?, ?, ?, ?)",
                customerId, new BigDecimal("30.00"), "1 Test Street, Montreal", 45.5, -73.5);

        Restaurant restaurant = new Restaurant("Strategy Cafe", "open", 45.5, -73.5);
        restaurant.setAddress("1 Test Street");
        restaurantId = restaurants.saveAndFlush(restaurant).getId();
        jdbc.execute("CREATE TABLE IF NOT EXISTS menus (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, restaurant_id BIGINT NOT NULL)");
        jdbc.update("INSERT INTO menus (restaurant_id) VALUES (?)", restaurantId);
        Long menuId = jdbc.queryForObject("SELECT id FROM menus WHERE restaurant_id = ?", Long.class, restaurantId);
        menuItemId = menuItems.saveAndFlush(new MenuItem(null, menuId, new BigDecimal("20.00"), "available", null,
                "Strategy Bowl", null, null)).getId();
    }

    private String order(long tenders) {
        return "{\"restaurantId\":" + restaurantId + ",\"items\":[{\"menuItemId\":" + menuItemId
                + ",\"quantity\":1,\"selectedOptionIds\":[]}],\"tendersToUse\":" + tenders + "}";
    }

    private long orderCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE customer_id = ?", Long.class, customerId);
    }

    private Long tenderEntry(String reason) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(tenders), 0) FROM tender_cash_transactions WHERE customer_id = ? AND reason = ?",
                Long.class, customerId, reason);
    }

    @Test
    void tenderCashStrategyPlacesAFullyCoveredOrderOverHttp() throws Exception {
        mvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(order(215)))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.paymentReference").value("TENDER-CASH"))
                .andExpect(jsonPath("$.total").value(21.50));

        assertThat(orderCount()).isEqualTo(1);
        assertThat(tenderEntry("SPENT")).isEqualTo(-215L);
        assertThat(tenderEntry("EARNED")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bill WHERE customer_id = ?", Long.class, customerId)).isEqualTo(1);
    }

    @Test
    void cardStrategyIsRefusedByDefaultAndNothingIsSaved() throws Exception {
        mvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(order(100)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PayPal checkout")));

        assertThat(orderCount()).isZero();
        assertThat(tenderEntry("SPENT")).isZero();
    }

    @Test
    void payPalStrategyPlacesTheOrderAfterCreateAndCaptureOverHttp() throws Exception {
        String cart = "{\"orders\":[" + order(15) + "],\"returnUrl\":\"tender-mobile-app://customer/checkout/confirm-order\"}";
        mvc.perform(post("/api/paypal/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(cart))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.orderId").value("PP-E2E"));
        assertThat(orderCount()).isZero();

        mvc.perform(post("/api/paypal/orders/PP-E2E/capture").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$[0].paymentReference").value("PAYPAL-CAPTURE-E2E"))
                .andExpect(jsonPath("$[0].total").value(21.50));

        assertThat(orderCount()).isEqualTo(1);
        assertThat(tenderEntry("SPENT")).isEqualTo(-15L);
        assertThat(tenderEntry("EARNED")).isEqualTo(200L); // 20.00 CAD paid through PayPal
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
