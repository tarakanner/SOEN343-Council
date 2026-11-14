package com.council.tenderapp.ord.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.council.tenderapp.ord.dtos.OrderItemRequestDto;
import com.council.tenderapp.ord.dtos.OrderResponseDto;
import com.council.tenderapp.ord.dtos.OrderStatusResponseDto;
import com.council.tenderapp.ord.dtos.PlaceOrderRequestDto;
import com.council.tenderapp.ord.entities.MenuItem;
import com.council.tenderapp.ord.entities.MenuItemOption;
import com.council.tenderapp.ord.entities.Order;
import com.council.tenderapp.ord.entities.OrderStatus;
import com.council.tenderapp.ord.exceptions.InvalidOrderException;
import com.council.tenderapp.ord.exceptions.InvalidOrderStateException;
import com.council.tenderapp.ord.exceptions.MenuItemUnavailableException;
import com.council.tenderapp.ord.exceptions.OrderAccessDeniedException;
import com.council.tenderapp.ord.exceptions.OrderNotFoundException;
import com.council.tenderapp.ord.exceptions.RestaurantClosedException;
import com.council.tenderapp.ord.repositories.MenuItemOptionRepository;
import com.council.tenderapp.ord.repositories.MenuItemRepository;
import com.council.tenderapp.ord.repositories.OrderRepository;
import com.council.tenderapp.reg.services.CustomerProfileService;
import com.council.tenderapp.prc.service.PricingService;
import com.council.tenderapp.technicalservices.payment.PaymentDeclinedException;
import com.council.tenderapp.technicalservices.logging.AppLogService;
import com.council.tenderapp.technicalservices.payment.MockCardPayment;
import com.council.tenderapp.technicalservices.payment.PayPalPayment;
import com.council.tenderapp.technicalservices.payment.PaymentResult;
import com.council.tenderapp.technicalservices.payment.TenderCash;
import com.council.tenderapp.technicalservices.payment.TenderCashPayment;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Long CUSTOMER_ID = 1L;
    private static final Long RESTAURANT_ID = 1L;

    @Mock
    private BrowseService browseService;
    @Mock
    private MenuItemRepository menuItemRepository;
    @Mock
    private MenuItemOptionRepository menuItemOptionRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private MockCardPayment cardPayment;
    @Mock
    private PreparationQueueService preparationQueueService;
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private CustomerProfileService customerProfileService;
    @Mock
    private PricingService pricingService;
    @Mock
    private TenderCash tenderCash;

    @Mock
    private OrderStatusPublisher publisher;

    private CustomerOrderService service;

    @BeforeEach
    void setUp() {
        service = new CustomerOrderService(browseService, menuItemRepository, menuItemOptionRepository, orderRepository, preparationQueueService, jdbc, customerProfileService,
                new OrderCancellationService(orderRepository, preparationQueueService, publisher, tenderCash), pricingService,
                org.mockito.Mockito.mock(com.council.tenderapp.prc.service.LedgerService.class), tenderCash, new TenderCashPayment(), cardPayment);
        lenient().when(customerProfileService.deliveryAddress(CUSTOMER_ID)).thenReturn(Optional.of(
                new CustomerProfileService.DeliveryAddress("1250 Rue Peel, Montreal, Canada", 45.508, -73.571)));
        lenient().when(pricingService.quote(any(), any(), any(), any())).thenAnswer(inv -> {
            BigDecimal subtotal = inv.getArgument(0);
            return new PricingService.Quote(subtotal, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, subtotal);
        });
    }

    private static MenuItem item(Long id, String name, String price, String status) {
        return new MenuItem(id, 1L, new BigDecimal(price), status, null, name, null, null);
    }

    private void menu(MenuItem... items) {
        when(menuItemRepository.findMenuItemsByRestaurantId(RESTAURANT_ID)).thenReturn(List.of(items));
    }

    private static PlaceOrderRequestDto request(OrderItemRequestDto... items) {
        return new PlaceOrderRequestDto(RESTAURANT_ID, List.of(items));
    }

    private void savesOrder() {
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void directOrderCannotUseMockPaymentByDefault() {
        menu(item(1L, "Lunch", "12.00", "available"));
        service = new CustomerOrderService(browseService, menuItemRepository, menuItemOptionRepository, orderRepository,
                preparationQueueService, jdbc, customerProfileService,
                new OrderCancellationService(orderRepository, preparationQueueService, publisher, tenderCash), pricingService,
                org.mockito.Mockito.mock(com.council.tenderapp.prc.service.LedgerService.class), tenderCash,
                new TenderCashPayment(), new MockCardPayment(org.mockito.Mockito.mock(AppLogService.class), false));
        assertThatThrownBy(() -> service.placeOrder(CUSTOMER_ID, request(new OrderItemRequestDto(1L, 1))))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("PayPal checkout");
        verifyNoInteractions(orderRepository);
    }

    @Test
    void fullyTenderCoveredOrderUsesTenderCashStrategyWithoutCharging() {
        menu(item(1L, "Lunch", "12.00", "available"));
        when(tenderCash.discount(CUSTOMER_ID, 120L, new BigDecimal("12.00"))).thenReturn(new BigDecimal("12.0"));
        savesOrder();

        OrderResponseDto response = service.placeOrder(CUSTOMER_ID, new PlaceOrderRequestDto(RESTAURANT_ID,
                List.of(new OrderItemRequestDto(1L, 1)), null, null, null, 120L));

        assertThat(response.paymentReference()).isEqualTo("TENDER-CASH");
        verifyNoInteractions(cardPayment);
        verify(tenderCash).placed(any(Order.class), eq(120L), eq(new BigDecimal("0.00")));
    }

    @Test
    void partlyTenderCoveredOrderChargesTheRemainderToTheCardStrategy() {
        menu(item(1L, "Lunch", "12.00", "available"));
        when(tenderCash.discount(CUSTOMER_ID, 50L, new BigDecimal("12.00"))).thenReturn(new BigDecimal("5.0"));
        when(cardPayment.pay(CUSTOMER_ID, new BigDecimal("7.00"))).thenReturn(PaymentResult.approved("MOCK-2"));
        savesOrder();

        OrderResponseDto response = service.placeOrder(CUSTOMER_ID, new PlaceOrderRequestDto(RESTAURANT_ID,
                List.of(new OrderItemRequestDto(1L, 1)), null, null, null, 50L));

        assertThat(response.paymentReference()).isEqualTo("MOCK-2");
        verify(tenderCash).placed(any(Order.class), eq(50L), eq(new BigDecimal("7.00")));
    }

    @Test
    void callerChosenPayPalStrategyRecordsTheCaptureReference() {
        menu(item(1L, "Lunch", "12.00", "available"));
        savesOrder();

        OrderResponseDto response = service.placeOrder(CUSTOMER_ID, request(new OrderItemRequestDto(1L, 1)),
                new PayPalPayment("CAPTURE-9", new BigDecimal("12.00")));

        assertThat(response.paymentReference()).isEqualTo("PAYPAL-CAPTURE-9");
        verifyNoInteractions(cardPayment);
    }

    @Test
    void payPalStrategyRejectsAPriceThatChangedAfterCapture() {
        menu(item(1L, "Lunch", "12.00", "available"));

        assertThatThrownBy(() -> service.placeOrder(CUSTOMER_ID, request(new OrderItemRequestDto(1L, 1)),
                new PayPalPayment("CAPTURE-9", new BigDecimal("10.00"))))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("price changed");
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(cardPayment, preparationQueueService);
    }

    @Test
    void placesOrderComputesTotalAndQueuesIt() {
        menu(item(1L, "Margherita Pizza", "14.00", "available"), item(4L, "Sparkling Water", "3.00", "available"));
        when(cardPayment.pay(CUSTOMER_ID, new BigDecimal("34.00"))).thenReturn(PaymentResult.approved("MOCK-1"));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 42L);
            return order;
        });

        OrderResponseDto response = service.placeOrder(CUSTOMER_ID,
                request(new OrderItemRequestDto(1L, 2), new OrderItemRequestDto(4L, 2)));

        assertThat(response.orderId()).isEqualTo(42L);
        assertThat(response.status()).isEqualTo("placed");
        assertThat(response.total()).isEqualByComparingTo("34.00");
        assertThat(response.paymentReference()).isEqualTo("MOCK-1");
        assertThat(response.lines()).hasSize(2);
        assertThat(response.lines().get(0).lineTotal()).isEqualByComparingTo("28.00");

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue().getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(saved.getValue().getRestaurantId()).isEqualTo(RESTAURANT_ID);
        assertThat(saved.getValue().getTotalPrice()).isEqualByComparingTo("34.00");
        verify(preparationQueueService).enqueue(eq(saved.getValue()), any(Instant.class));
        verify(customerProfileService).ensureExists(CUSTOMER_ID);
    }

    @Test
    void chargesAndStoresThePricedTotal() {
        menu(item(1L, "Lunch", "12.00", "available"));
        PricingService.Quote quote = new PricingService.Quote(new BigDecimal("12.00"), new BigDecimal("2.00"),
                new BigDecimal("1.50"), new BigDecimal("1.60"), new BigDecimal("2.00"), new BigDecimal("17.10"));
        when(pricingService.quote(any(), any(), any(), any())).thenReturn(quote);
        when(cardPayment.pay(CUSTOMER_ID, new BigDecimal("17.10"))).thenReturn(PaymentResult.approved("MOCK-1"));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 55L);
            return order;
        });

        OrderResponseDto response = service.placeOrder(CUSTOMER_ID, request(new OrderItemRequestDto(1L, 1)));

        assertThat(response.total()).isEqualByComparingTo("17.10");
        verify(pricingService).saveBill(any(Order.class), eq(quote));
        verify(cardPayment).pay(CUSTOMER_ID, new BigDecimal("17.10"));
    }

    @Test
    void quotesTheSamePriceWithoutChargingOrSavingAnOrder() {
        menu(item(1L, "Lunch", "12.00", "available"));
        PricingService.Quote quote = new PricingService.Quote(new BigDecimal("12.00"), new BigDecimal("2.00"),
                new BigDecimal("1.50"), new BigDecimal("1.60"), new BigDecimal("2.00"), new BigDecimal("17.10"));
        when(pricingService.quote(any(), any(), any(), any())).thenReturn(quote);

        assertThat(service.quoteOrder(CUSTOMER_ID, request(new OrderItemRequestDto(1L, 1))).total())
                .isEqualByComparingTo("17.10");
        verifyNoInteractions(cardPayment, orderRepository);
    }

    @Test
    void copiesTheCustomersAddressOntoTheOrder() {
        menu(item(2L, "Pepperoni Pizza", "17.00", "available"));
        when(cardPayment.pay(any(), any())).thenReturn(PaymentResult.approved("MOCK-1"));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(customerProfileService.deliveryAddress(CUSTOMER_ID)).thenReturn(Optional.of(
                new CustomerProfileService.DeliveryAddress("1250 Rue Peel, Montreal, Canada", 45.508, -73.571)));

        service.placeOrder(CUSTOMER_ID, request(new OrderItemRequestDto(2L, 1)));

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue().getDeliveryAddress()).isEqualTo("1250 Rue Peel, Montreal, Canada");
        assertThat(saved.getValue().getDeliveryLatitude()).isEqualTo(45.508);
        assertThat(saved.getValue().getDeliveryLongitude()).isEqualTo(-73.571);
    }

    @Test
    void storesMenuPriceSnapshotOnEachLine() {
        menu(item(2L, "Pepperoni Pizza", "17.00", "available"));
        when(cardPayment.pay(any(), any())).thenReturn(PaymentResult.approved("MOCK-1"));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        service.placeOrder(CUSTOMER_ID, request(new OrderItemRequestDto(2L, 3)));

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue().getOrderLines()).singleElement().satisfies(line -> {
            assertThat(line.getMenuItemId()).isEqualTo(2L);
            assertThat(line.getQuantity()).isEqualTo(3);
            assertThat(line.getUnitPrice()).isEqualByComparingTo("17.00");
        });
    }

    @Test
    void rejectsClosedRestaurant() {
        doThrow(new RestaurantClosedException("closed")).when(browseService).checkRestaurantOpen(RESTAURANT_ID);

        assertThatThrownBy(() -> service.placeOrder(CUSTOMER_ID, request(new OrderItemRequestDto(1L, 1))))
                .isInstanceOf(RestaurantClosedException.class);
        verifyNoInteractions(cardPayment, orderRepository, preparationQueueService);
    }

    @Test
    void rejectsItemFromAnotherRestaurant() {
        menu(item(1L, "Margherita Pizza", "14.00", "available"));

        assertThatThrownBy(() -> service.placeOrder(CUSTOMER_ID, request(new OrderItemRequestDto(5L, 1))))
                .isInstanceOf(InvalidOrderException.class);
        verifyNoInteractions(cardPayment, orderRepository, preparationQueueService);
    }

    @Test
    void rejectsSoldOutItem() {
        menu(item(3L, "Truffle Pizza", "22.00", "sold_out"));

        assertThatThrownBy(() -> service.placeOrder(CUSTOMER_ID, request(new OrderItemRequestDto(3L, 1))))
                .isInstanceOf(MenuItemUnavailableException.class);
        verifyNoInteractions(cardPayment, orderRepository, preparationQueueService);
    }

    @Test
    void declinedPaymentCreatesNoOrder() {
        menu(item(1L, "Margherita Pizza", "14.00", "available"));
        when(cardPayment.pay(any(), any())).thenReturn(PaymentResult.declined("Insufficient funds"));

        assertThatThrownBy(() -> service.placeOrder(CUSTOMER_ID, request(new OrderItemRequestDto(1L, 1))))
                .isInstanceOf(PaymentDeclinedException.class);
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(preparationQueueService);
    }

    private Order existingOrder(Long customerId, String status) {
        Order order = new Order(customerId, RESTAURANT_ID, OrderStatus.valueOf(status.toUpperCase()), new BigDecimal("14.00"));
        ReflectionTestUtils.setField(order, "id", 42L);
        when(orderRepository.findWithLockById(42L)).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    void cancelsPlacedOrderAndRemovesItFromQueue() {
        Order order = existingOrder(CUSTOMER_ID, "placed");

        OrderStatusResponseDto response = service.cancelOrder(CUSTOMER_ID, 42L);

        assertThat(response.orderId()).isEqualTo(42L);
        assertThat(response.previousStatus()).isEqualTo("placed");
        assertThat(response.status()).isEqualTo("cancelled");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderRepository).save(order);
        verify(preparationQueueService).remove(eq(order), eq("placed"), any(Instant.class));
    }

    @Test
    void cancellingUnknownOrderIsNotFound() {
        when(orderRepository.findWithLockById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelOrder(CUSTOMER_ID, 99L))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void cannotCancelAnotherCustomersOrder() {
        Order order = existingOrder(2L, "placed");

        assertThatThrownBy(() -> service.cancelOrder(CUSTOMER_ID, 42L))
                .isInstanceOf(OrderAccessDeniedException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(preparationQueueService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "preparing", "ready", "picked_up", "delivered", "rejected", "cancelled"})
    void cannotCancelOrderThatIsNotPlaced(String status) {
        Order order = existingOrder(CUSTOMER_ID, status);

        assertThatThrownBy(() -> service.cancelOrder(CUSTOMER_ID, 42L))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.valueOf(status.toUpperCase()));
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(preparationQueueService);
    }

    @Test
    void chargesSelectedOptionsAndRecordsThemPerLine() {
        menu(item(1L, "Margherita Pizza", "14.00", "available"));
        when(menuItemOptionRepository.findByMenuItemIdIn(List.of(1L))).thenReturn(List.of(
                new MenuItemOption(7L, 1L, new BigDecimal("2.00"), "Extra cheese"),
                new MenuItemOption(8L, 1L, new BigDecimal("1.50"), "Extra basil")));
        when(cardPayment.pay(CUSTOMER_ID, new BigDecimal("35.00"))).thenReturn(PaymentResult.approved("MOCK-1"));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponseDto response = service.placeOrder(CUSTOMER_ID,
                request(new OrderItemRequestDto(1L, 2, List.of(7L, 8L, 7L))));

        assertThat(response.total()).isEqualByComparingTo("35.00");
        assertThat(response.lines().get(0).unitPrice()).isEqualByComparingTo("17.50");
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue().getOrderLines().get(0).getUnitPrice()).isEqualByComparingTo("14.00");
        // Lines must be flushed so they have ids before their add-ons are inserted.
        InOrder writes = inOrder(orderRepository, jdbc);
        writes.verify(orderRepository).flush();
        writes.verify(jdbc).update(any(String.class), any(), eq(1L), eq(7L), eq(new BigDecimal("2.00")));
        verify(jdbc).update(any(String.class), any(), eq(1L), eq(8L), eq(new BigDecimal("1.50")));
    }

    @Test
    void rejectsOptionThatBelongsToAnotherItem() {
        menu(item(1L, "Margherita Pizza", "14.00", "available"), item(4L, "Sparkling Water", "3.00", "available"));
        when(menuItemOptionRepository.findByMenuItemIdIn(List.of(4L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.placeOrder(CUSTOMER_ID,
                request(new OrderItemRequestDto(4L, 1, List.of(7L)))))
                .isInstanceOf(InvalidOrderException.class);
        verifyNoInteractions(cardPayment, orderRepository, jdbc);
    }
}
