# Backend

Server-side application source code lives in `src/`.

## Run locally

From this directory, start the backend with:

```bash
./mvnw spring-boot:run
```

By default the backend connects to the PostgreSQL database configured by
`DB_URL`, `DB_USER`, and `DB_PASSWORD`.

To work offline, run with the `local` profile instead:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile uses an in-memory H2 database and seeds a demo
restaurant (`restaurantId=1`) with orders in `PLACED`, `ACCEPTED`, and
`PREPARING` states. Data (including accounts) is reset when the backend restarts.

## Demo Data

The `demo` Spring profile automatically loads `src/main/resources/demo/seed.sql` into PostgreSQL at startup.

**Warning:** Every demo startup clears and reseeds the 20 tables listed in the seed. Use a dedicated demo database/branch. Normal startup preserves existing data.

### Setup

1. Copy `backend/.env.example` to `backend/.env` and configure the database credentials.
2. Ensure the database schema already exists.
3. From `tender-app/backend`, run:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

Alternatively, add `--spring.profiles.active=demo` to your IDE's run configuration.

### Seeded Data

Includes 9 users, 4 restaurants, 7 menu items, and 8 orders, covering REG/ORD scenarios such as restaurant availability, sold out items, order lifecycles, and preparation queues.

**Demo accounts:**

| Role | Email |
-----------------------------
| Customer | alice@demo.test |
| Staff | sam@demo.test |
| Courier | leo@demo.test |
| Operator | operator@demo.test |

**Password:** `DemoPass123!` (verify BCrypt compatibility when implementing authentication).

**Remove the `demo` profile to run without resetting the database.**

## Restaurant order queues

`GET /api/restaurants/{restaurantId}/orders/preparation` returns that restaurant's
orders in `ACCEPTED` or `PREPARING` status, ordered by `acceptedAt` from oldest
to newest.

`GET /api/restaurants/{restaurantId}/orders/incoming` returns that restaurant's
`PLACED` orders, ordered by `created_at` from oldest to newest. Each order
includes its ID, status, placement time, order lines with menu item names,
quantities, and unit prices, and a total calculated from those lines. An empty
queue is returned as an empty JSON array. Hibernate validates the existing
schema and does not create or update tables.

`GET /api/restaurants/{restaurantId}/orders/pickup` returns `READY` orders that
are awaiting courier or customer pickup, ordered by placement time.
The all-orders response also includes the courier's name once an offer has
been accepted; pending offers are shown without a courier name.

Restaurant dashboard actions are `POST /api/restaurants/{restaurantId}/orders/{orderId}/accept`,
`/reject` (JSON body `{"reason":"..."}`), `/cancel`, `/prepare`, `/ready`, and
`/refund`. The restaurant prepares an order through `READY`; the courier
confirms pickup and delivery. The lifecycle is
`PLACED → ACCEPTED → PREPARING → READY → PICKED_UP → DELIVERED`.
`READY`, `PICKED_UP`, or `DELIVERED` orders may transition to `REFUNDED`.
`PLACED → REJECTED|CANCELLED` are terminal alternatives. Accepting records the
acceptance time. The incoming queue is ordered by placement time, while the
preparation queue (accepted and preparing orders) is ordered by acceptance time.
Rejections require a nonblank reason. Every successful action is logged to the
application console with the order ID and, for rejections, the reason.

Order status changes are published as `OrderStatusEvent` notifications to
registered `OrderObserver` implementations. `OrderRecord` preserves the
existing console event log, while `DashboardObserver` streams changes to
subscribed web dashboards through
`GET /api/restaurants/{restaurantId}/orders/events` (Server-Sent Events). The
dashboard refreshes its selected queue when it receives an event; manual
refresh and the existing lifecycle actions remain available.

### Design patterns in the restaurant order flow

- **Command:** Each restaurant action is captured as an immutable request object
  (`OrderCommands.Accept`, `Reject`, `Cancel`, `StartPreparation`, `MarkReady`,
  or `Refund`). `RestaurantOrderController`
  submits these commands to `OrderCommandInvoker`, which dispatches them to the
  existing `OrderService`. The service remains responsible for authorization
  checks, transactions, persistence, and publishing status changes. Queue reads
  remain ordinary queries; commands represent the requested state-changing
  actions.
- **State:** `Order` delegates status changes to `OrderStatus`, whose
  state-specific `transitionTo` rules allow or reject each transition. Commands
  call the same service operations as before, so this state machine continues to
  govern every action.
- **Observer:** After a status change commits, `OrderStatusPublisher` creates an
  `OrderStatusEvent` and notifies each `OrderObserver`. `OrderRecord` logs the
  event and `DashboardObserver` sends it to subscribed dashboards over SSE.

The patterns have separate responsibilities: commands carry and dispatch
requests, the state machine validates lifecycle transitions, and observers
react to committed status changes. The restaurant command flow ends when the
order is marked ready; courier-only routes own pickup and delivery.

### Design decision: courier dispatch queue (R-DLV-04)

The dispatch queue is derived from persisted orders in `READY` status with no
`courier_id`; it is not an in-memory collection, so waiting orders survive page
refreshes and are cleared when the underlying orders are reset. `status_changed_at`
is not used for dispatch ordering. Instead, a persisted `order_ready` event in
`order_events` records when an order became ready; waiting orders are considered
in that event-time FIFO order, with order ID as a tie-breaker. Existing ready
orders without a corresponding event use their creation time as a fallback.

The Observer pattern connects committed order and courier state changes to
dispatch: the order-status observer retries after an order becomes ready, and
the courier duty service retries after a courier goes on duty or finishes a
delivery. For each idle courier, dispatch creates an `OFFERED` assignment, sets
the order's `courier_id`, and reserves the courier as `ASSIGNED`. Accepting changes
the assignment to `ACCEPTED`; pickup requires that acceptance. The nearest idle courier receives the oldest eligible order. A courier
with an outstanding offer is not offered another one.

Courier actions use `POST /api/courier/duty/on` and
`POST /api/courier/duty/off` for the duty toggle,
`POST /api/courier/duty/orders/{orderId}/accept` to accept an offer, and
`POST /api/courier/duty/orders/{orderId}/decline` to decline it and go offline. Declining clears the
order's `courier_id`, putting it back in the FIFO queue, then retries dispatch
for another idle courier. Going off duty while an offer is pending also
withdraws it. After acceptance,
`POST /api/courier/duty/orders/{orderId}/picked-up` changes the order to
`PICKED_UP` and the courier from `ASSIGNED` to `CARRYING`; the courier can
then call `POST /api/courier/duty/orders/{orderId}/delivered`, which changes the order to `DELIVERED`,
returns the courier to `IDLE`, and retries the queue. The API verifies that the
order is offered to or assigned to the authenticated courier. Waiting, offer,
acceptance, decline, pickup, and delivery events are logged to the application
console.
Courier acceptance is recorded in the assignment and as a `courier_accepted`
row in `order_events`. Restaurant responses show the current courier's name
only after that assignment is accepted, including after delivery.

`POST /api/courier/orders/{orderId}/pickup` lets the assigned courier confirm pickup
(Task 72, US.14). It requires the `COURIER` role and moves the order from `READY` to
`PICKED_UP`, which moves the courier from `ASSIGNED` to `CARRYING`. The call is refused
with `404` if the order does not exist, `403` if the authenticated courier is not the
order's assigned courier or the caller is not a courier, `409` if the order is not
`READY`, and `401` without a session. On success it returns
`{"orderId": 12, "orderStatus": "picked_up", "courierStatus": "carrying"}`. The
transition reuses `OrderService.markPickedUpByCourier`, which also sets the order's
`pickup_date` and records an `order_picked_up` row in `order_events` (`ready` to
`picked_up`) in the same transaction. The older
`POST /api/courier/duty/orders/{orderId}/picked-up` route shares that method, so it
records the same event.

`POST /api/courier/orders/{orderId}/delivery` lets the assigned courier confirm delivery
(Task 73, US.14). It requires the `COURIER` role and moves the order from `PICKED_UP` to
`DELIVERED`. The call is refused with `404` if the order does not exist, `403` if the
authenticated courier is not the order's assigned courier or the caller is not a courier,
`409` if the order is not `PICKED_UP`, and `401` without a session. On success it returns
`{"orderId": 12, "orderStatus": "delivered", "courierStatus": "idle"}`. The transition
reuses `OrderService.markDeliveredByCourier`, which also sets the order's `date_delivered`
and records an `order_delivered` row in `order_events` (`picked_up` to `delivered`) in the
same transaction. After the change commits, `CourierReleaseObserver` returns the courier
from `CARRYING` to `IDLE` and retries the dispatch queue, so the courier is immediately
offered the oldest waiting order. The older
`POST /api/courier/duty/orders/{orderId}/delivered` route shares that method, so it
records the same event. The order summary and ledger entry are separate tasks (PRC).

The current project has no authentication/authorization integration. The local
restaurant profile is a demo context, not an authenticated account. Actions
verify that the order belongs to the restaurant ID in the URL, but this is not
an authenticated restaurant identity check; secure restaurant-only access
requires the project's authentication system to be added separately.
## Existing database upgrade

Before starting this merged version against an existing PostgreSQL database,
apply [`docs/order-lifecycle-merge.sql`](docs/order-lifecycle-merge.sql). It
allows the `refunded` lifecycle status without changing order rows or timestamps.
Apply [`docs/restaurant-profile.sql`](docs/restaurant-profile.sql) before deploying
restaurant contact profiles to an existing database.
The test suite uses an in-memory H2 database and does not apply this script to
your configured database.

### Menu photos

The seven demo photos are bundled in `src/main/resources/static/menu-items-imgs`,
so they are included in local and Docker builds. Public URLs such as
`/menu-items-imgs/truffle.jpg` are stored in the seed; the frontend resolves them
against `EXPO_PUBLIC_API_URL`. Restaurant covers use the same bundled photos.
The backend must be reachable from the browser or device to serve these URLs.

For an already reset database, apply
[docs/migrations/2026-11-13-demo-image-urls.sql](docs/migrations/2026-11-13-demo-image-urls.sql)
to restore missing demo covers and normalize old localhost photo URLs without
reseeding. Existing custom cover URLs are preserved. Uploaded images removed by
a reset must be uploaded again; the seed restores only the bundled demo photos.

The seed matches the public Supabase schema inspected on November 13, 2026:
menus have no name column, restaurants use `opening_hour` and `closing_hour`,
and `operator_profiles.user_id` references `users.id`. Olivia's operator profile
links to demo user 8. No table creation or schema migration is performed by the seed.
Maple & Main and Little Italy Pizza open at 09:00 and close at 22:00; Green Bowl
Kitchen and Café Nord open at 09:00 and close at 17:00. These stored hours do not
implement automatic opening/closing behavior.

The reset list also includes `order_items` because its foreign key references
orders, and `auth_sessions` to remove stale persisted demo sessions. Those two
tables are cleared but not populated. Reset also clears assignments, uploaded menu
photos, and Tender Cash transactions. Bills and ledger entries are rebuilt for
the eight demo orders; balances return to the seeded customer balances. Menu
categories, descriptions, opening hours, and order snapshots are populated.
The baseline restores the original demo. The `health_check` table is preserved.
The current schema snapshot is recorded in [docs/schema.txt](docs/schema.txt).
The backend maps its Java `dateOrdered` field to `orders.created_at`, preserving
the existing API field name. Dashboard queries also read `created_at`, so there
is no duplicate `date_ordered` column. The lifecycle migration expands the status
constraint to include `refunded`; it does not perform refunds or reseed data.

The demo seed retains open, paused, and closed restaurant scenarios, so its
restaurant statuses and generated IDs can differ from the frontend mock state.

### Tender Cash

Tender Cash uses 10 whole tenders per CAD dollar. The existing
`customer_profiles.td_cash_balance` is the starting CAD balance; order credits
and debits are recorded in `tender_cash_transactions`. Apply
`docs/migrations/2026-11-11-tender-cash.sql` to an existing database before
starting the updated backend. A purchase earns 10 tenders per CAD dollar paid
after redemption, with fractional tenders dropped. A refund, rejection, or cancellation reverses those earned
tenders, returns any tenders spent, and credits the cash portion as Tender Cash,
rounded up to the next tender. These are store credits; no PayPal refund is sent.
The direct order endpoint accepts a fully Tender Cash funded order; a remaining
cash amount must go through PayPal. Set `app.payments.allow-mock=true` only for
tests that intentionally exercise the mock card payment.

Payment uses the Strategy pattern. `CustomerOrderService` (the context) prices
the order, applies Tender Cash, then hands the amount due to a `PaymentStrategy`:
`TenderCashPayment` (order fully covered), `MockCardPayment` (simulated card,
off by default), or `PayPalPayment` (a capture already verified by
`PayPalCheckoutService`).

## Operator actions

- `POST /api/operator/orders/{orderId}/reassign`: operator-only reassignment.
- `POST /api/operator/reset`: operator-only reset after `{ "confirmation": "RESET" }`.
- Reset is enabled by default for both local backend runs and Docker Compose. Set
  `APP_OPERATOR_RESET_ENABLED=false` to disable it. Normal startup does not seed.
- Successful reset clears all sessions; sign in again with a demo account.
- The baseline defaults to
  `classpath:demo/seed.sql`; override it with `app.operator.reset-seed`.
- The demo-profile alias `POST /api/operator/reset-seed` uses the same reset guard,
  transaction, and `{ "confirmation": "RESET" }` request as `/api/operator/reset`.
- Tests verify the actual seed against an isolated PostgreSQL schema, including
  repeated resets and rollback on failure.

## Repair existing stale assignments

Apply [docs/migrations/2026-11-13-close-stale-assignments.sql](docs/migrations/2026-11-13-close-stale-assignments.sql)
after deploying the delivery/refund cleanup fix. It closes active assignments
for finished orders and releases affected couriers only when they have no
other active assignment or delivery. It preserves order and assignment history,
and can be run again safely. No database reset is needed.

## Courier offers

Apply [docs/assignments.sql](docs/assignments.sql) before using courier offers.
Ready orders are dispatched automatically. Couriers accept offers from their
dashboard through `POST /api/courier/offers/{assignmentId}/accept`.

### Verify the reset seed locally

The PostgreSQL seed regression tests are opt-in and accept only a localhost
connection. They create and remove an isolated schema using `docs/schema.txt`;
no Supabase database is used. With a disposable local PostgreSQL instance:

```sh
RESET_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55439/postgres \
RESET_TEST_DATABASE_USER=tender_reset_test \
./mvnw -Dtest=OperatorResetSeedIntegrationTest test
```

They execute the actual seed twice, check the new foreign-key tables and order
snapshots, and verify rollback and session preservation if the seed fails.
