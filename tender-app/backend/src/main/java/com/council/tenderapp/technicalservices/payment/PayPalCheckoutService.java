package com.council.tenderapp.technicalservices.payment;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.council.tenderapp.ord.dtos.OrderResponseDto;
import com.council.tenderapp.ord.dtos.PlaceOrderRequestDto;
import com.council.tenderapp.ord.services.CustomerOrderService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class PayPalCheckoutService {
    private final CustomerOrderService orders;
    private final ObjectMapper json;
    private final TenderCash tenderCash;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String clientId;
    private final String clientSecret;
    private final String baseUrl;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private static final class Pending {
        final Long customerId;
        final List<PlaceOrderRequestDto> requests;
        final List<BigDecimal> totals;
        final BigDecimal amount;
        String captureId;
        List<OrderResponseDto> receipts;

        Pending(Long customerId, List<PlaceOrderRequestDto> requests, List<BigDecimal> totals, BigDecimal amount) {
            this.customerId = customerId;
            this.requests = requests;
            this.totals = totals;
            this.amount = amount;
        }
    }

    public record CreatedOrder(String orderId, String approvalUrl) {}

    public PayPalCheckoutService(CustomerOrderService orders, ObjectMapper json, TenderCash tenderCash,
            @Value("${app.paypal.client-id:}") String clientId,
            @Value("${app.paypal.client-secret:}") String clientSecret,
            @Value("${app.paypal.base-url:https://api-m.sandbox.paypal.com}") String baseUrl) {
        this.orders = orders;
        this.json = json;
        this.tenderCash = tenderCash;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public boolean enabled() {
        return !clientId.isBlank() && !clientSecret.isBlank()
                && !clientId.startsWith("replace_") && !clientSecret.startsWith("replace_");
    }

    public String clientId() { return enabled() ? clientId : ""; }

    public CreatedOrder create(Long customerId, List<PlaceOrderRequestDto> requests, String returnUrl) {
        requireConfigured();
        if (requests == null || requests.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cart is empty");
        if (returnUrl != null && !returnUrl.startsWith("tender-mobile-app://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid PayPal return URL");
        }
        List<PlaceOrderRequestDto> snapshot = List.copyOf(requests);
        List<BigDecimal> totals = snapshot.stream().map(request -> orders.quoteOrder(customerId, request).total()).toList();
        long requested = snapshot.stream().mapToLong(PlaceOrderRequestDto::tenderAmount).sum();
        BigDecimal gross = totals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal amount = gross.subtract(BigDecimal.valueOf(requested, 1));
        if (requested > 0) tenderCash.discount(customerId, requested, gross);
        if (amount.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use Tender Cash checkout for a fully covered order");
        for (int index = 0; index < snapshot.size(); index++) {
            long tenders = snapshot.get(index).tenderAmount();
            if (tenders < 0 || BigDecimal.valueOf(tenders, 1).compareTo(totals.get(index)) > 0)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Tender Cash amount");
        }
        try {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("intent", "CAPTURE");
            payload.put("purchase_units", List.of(Map.of("amount", Map.of("currency_code", "CAD", "value", amount.toPlainString()))));
            if (returnUrl != null) payload.put("payment_source", Map.of("paypal", Map.of("experience_context",
                    Map.of("return_url", returnUrl, "cancel_url", returnUrl, "user_action", "PAY_NOW", "shipping_preference", "NO_SHIPPING"))));
            String body = json.writeValueAsString(payload);
            JsonNode result = send("POST", "/v2/checkout/orders", body);
            String id = result.path("id").asText();
            if (id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal did not create an order");
            String approvalUrl = "";
            for (JsonNode link : result.path("links")) {
                if ("payer-action".equals(link.path("rel").asText()) || "approve".equals(link.path("rel").asText())) {
                    approvalUrl = link.path("href").asText();
                    break;
                }
            }
            if (returnUrl != null && approvalUrl.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal approval link is missing");
            pending.put(id, new Pending(customerId, snapshot, totals, amount));
            return new CreatedOrder(id, approvalUrl);
        } catch (ResponseStatusException exception) { throw exception; }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not create PayPal order", exception); }
    }

    @Transactional
    public List<OrderResponseDto> capture(Long customerId, String paypalOrderId) {
        requireConfigured();
        Pending checkout = pending.get(paypalOrderId);
        if (checkout == null || !checkout.customerId.equals(customerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PayPal checkout not found");
        }
        synchronized (checkout) {
            if (checkout.receipts != null) return checkout.receipts;
            if (checkout.captureId == null) {
                tenderCash.lockCustomer(customerId);
                long tenders = checkout.requests.stream().mapToLong(PlaceOrderRequestDto::tenderAmount).sum();
                if (tenders > 0) tenderCash.discount(customerId, tenders,
                        checkout.amount.add(BigDecimal.valueOf(tenders, 1)));
                for (int index = 0; index < checkout.requests.size(); index++) {
                    if (orders.quoteOrder(customerId, checkout.requests.get(index)).total().compareTo(checkout.totals.get(index)) != 0) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Order price changed; start PayPal checkout again");
                    }
                }
                JsonNode result = send("POST", "/v2/checkout/orders/" + paypalOrderId + "/capture", "{}");
                JsonNode capture = result.path("purchase_units").path(0).path("payments").path("captures").path(0);
                String paid = capture.path("amount").path("value").asText();
                String currency = capture.path("amount").path("currency_code").asText();
                if (!"COMPLETED".equals(capture.path("status").asText()) || !"CAD".equals(currency)
                        || paid.isBlank() || new BigDecimal(paid).compareTo(checkout.amount) != 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal payment was not completed for the quoted CAD amount");
                }
                checkout.captureId = capture.path("id").asText();
            }
            if (checkout.captureId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal capture reference is missing");
            List<OrderResponseDto> receipts = java.util.stream.IntStream.range(0, checkout.requests.size())
                    .mapToObj(index -> orders.placeOrder(customerId, checkout.requests.get(index),
                            new PayPalPayment(checkout.captureId, checkout.totals.get(index)
                                    .subtract(BigDecimal.valueOf(checkout.requests.get(index).tenderAmount(), 1))))).toList();
            checkout.receipts = receipts;
            return receipts;
        }
    }

    private void requireConfigured() {
        if (!enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PayPal sandbox is not configured");
    }

    private JsonNode send(String method, String path, String body) {
        try {
            String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
            HttpRequest tokenRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/oauth2/token"))
                    .header("Authorization", "Basic " + basic)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                    .timeout(Duration.ofSeconds(20)).build();
            HttpResponse<String> tokenResponse = http.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
            if (tokenResponse.statusCode() / 100 != 2) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal authentication failed");
            String token = json.readTree(tokenResponse.body()).path("access_token").asText();
            if (token.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal access token is missing");
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(25))
                    .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal request failed (" + response.statusCode() + ")");
            return json.readTree(response.body());
        } catch (ResponseStatusException exception) { throw exception; }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal is unavailable", exception); }
    }
}
