# Operator backend integration

This document records the contracts for `feature/operator-backend`. Dashboard reads, restaurant status updates, and delivered-order refunds are implemented.
Courier reassignment and initial-state restoration are deferred outside the current Phase II scope. The dashboard now reads authenticated API data;
API helper tests mock HTTP responses and do not prove those routes exist.

## Existing endpoints to reuse

| Endpoint | Current behavior |
| --- | --- |
| `POST /api/auth/login` | Validates a predefined user's BCrypt password and creates a session. |
| `GET /api/auth/session` | Restores the account for a valid bearer token. |
| `GET /api/profile` | Returns the authenticated user's profile. |
| `PATCH /api/profile` | Updates name and phone; both fields are required. |
| `POST /api/orders` | Customer-only order placement. Operators must remain forbidden. |
| `POST /api/orders/{id}/cancel` | Customer-only cancellation of that customer's placed order. |

Profile update request:

```json
{ "name": "Olivia Operator", "phone": "+15145550108" }
```

Profile responses use `id`, `name`, `email`, `phone`, and an uppercase role
(`OPERATOR` for an operator). Restaurant metadata may also be present for staff.
The operator helper preserves the current name when changing the phone, sends
PATCH using the shared API client, and refuses to relabel other roles as operators.
Password changes are not supported by `UpdateProfileRequest` or `ProfileController`.
The frontend must not report them as successful or silently send an ignored field.

## Operator endpoint access

The security filter now requires the existing bearer session with
`ROLE_OPERATOR` for all `/api/operator/**` routes. Dashboard reads, restaurant status updates, and refunds are implemented; courier reassignment and restoration are deferred. No caller-supplied role or customer ID can grant access.
Return 401 for missing/invalid/expired tokens, 403 for other account roles,
400 for invalid inputs, 404 for missing targets, and 409 for state conflicts.
Reuse the existing error response conventions (`message`, optional `fieldErrors`).
Do not return password hashes or session tokens in dashboard data.

### Dashboard reads implemented

| Endpoint | Data |
| --- | --- |
| `GET /api/operator/summary` | Restaurant, order, customer, courier, and available-courier counts. |
| `GET /api/operator/restaurants` | Restaurant IDs, names, status, coordinates, and available contact details. |
| `GET /api/operator/orders` | All restaurants' order IDs, participant IDs, status, timestamps, and totals. |
| `GET /api/operator/customers` | Customer contact details, coordinates, and TD Cash balance when a profile exists. |
| `GET /api/operator/couriers` | Courier contact details, coordinates, and availability status. |

List endpoints use `page` (zero-based) and `size` (default 50, maximum 100),
with stable ID ordering and an envelope containing `items`, `total`, `page`, and
`size`. Summary counts cover the complete result sets, not just the loaded page.
Treat only `idle` couriers as available. Missing profile data must be shown as
unknown rather than fabricated from frontend fixtures.

Responses use camelCase DTO fields and lowercase status values. The operator frontend consumes these DTOs directly instead of using the
snake_case mock view models. Handle
nullable IDs/timestamps and every persisted order state, including `refunded`.
Order totals must follow the shared backend calculation, not mock order lines.

### Restaurant status implemented

Retain the teammate's helper contract:

```http
PUT /api/operator/restaurants/{restaurantId}/status
Authorization: Bearer <token>
Content-Type: application/json

{ "status": "paused" }
```

Accepts exactly `open`, `paused`, or `closed`. Returns 204 on success; the client
refreshes the list and summary. Repeating the same status is an idempotent success.
Missing/null/unsupported status values, malformed bodies, and nonpositive IDs
return 400. A missing restaurant returns 404. Operator access is enforced by both
the security filter and the transactional service.

The update changes only the status column, preserving other restaurant details
and all existing orders. After the update completes, the existing
`BrowseService.checkRestaurantOpen` check rejects new orders for paused or closed
restaurants. It does not cancel orders or retroactively stop requests that already
passed that check. Reopening does not add opening-hours scheduling behavior.

### Customer cancellation

`POST /api/orders/{id}/cancel` remains customer-only and ownership-checked.
Cancellation is limited to placed orders, locks the order row, records one event,
and notifies observers after commit. Operators cannot use this endpoint and have
no separate cancellation endpoint. The unused operator cancellation API and
eligibility helpers were removed from the frontend.

### Operator refund

`POST /api/operator/orders/{orderId}/refund` is operator-only. It locks the order
and accepts only a delivered order; a repeat refund or any other status is
rejected. The operation credits the customer in Tender Cash (not PayPal),
persists an `order_refunded` order event with its generated database event ID,
and creates a separate `Refund` ledger entry without changing the original
`Payment` entry. Older orders without a bill receive a zero-fee bill based on
their stored order total so the refund remains ledgered.

The operator orders list shows the refund action only for delivered orders. On
success it updates the displayed status to Refunded; the customer's order
history receives the same status from the order record.

## Phase II boundary and deferred features

- Operator login and restored sessions now route to `/operator/dashboard`. The
  operator layout waits for session restoration and redirects other roles or
  signed-out users. Dashboard identity comes from the authenticated account.
  Public registration rejects operator accounts; predefined accounts still log
  in through the existing BCrypt/session flow. Dashboard lists now use the authenticated read endpoints.
- Restaurant preparation and delivery endpoints must remain unavailable to
  operators. Existing restaurant order routes require a matching staff account.
- Courier reassignment needs an offline current courier, an eligible replacement,
  a defined eligible order state, and transaction/locking rules. Order already
  maps `courier_id`; courier profiles currently have no dedicated backend entity
  on this branch. Define the request/response after inspecting that model.
- Payment goes through `PaymentStrategy` implementations (`TenderCashPayment`,
  `MockCardPayment`, `PayPalPayment`); the mock card is disabled by default.
  Order charges and refunds are recorded in the ledger; delivered-order refunds
  are credited in Tender Cash and do not initiate PayPal transactions.
- Restoration needs an explicit initial-state definition, table/dependency scope,
  environment restrictions, and confirmation behavior before exposing an endpoint.
  It must be tested only in a disposable database. Do not execute the demo seed
  or any restoration against the configured Supabase database during development.

## Implementation checkpoint

1. Complete branch setup and dependency/test checks (done).
2. Align existing profile/cancellation helper contracts and record missing APIs.
3. Add operator backend authorization and login routing (done).
4. Implement dashboard read endpoints and connect the frontend without mocks (done).
5. Implement restaurant pause/reopen and connect frontend controls (done).
6. Customer cancellation remains supported; operator cancellation was removed from this Phase II scope.
Courier reassignment and initial-state restoration are not next steps for this
Phase II checkpoint. The Phase II instructions limit
implementation to REG and ORD and explicitly exclude DLV and the full role-scoped
command surface. Verify the referenced Moodle requirements before expanding scope.

The read, restaurant status, and refund UI integrations are complete. Command
and error logging is provided by the request filter; live demo verification
remains.
See [operator-checkpoint.md](operator-checkpoint.md) for the current scope and state.


### Read response examples

`GET /api/operator/summary` returns:

```json
{
  "restaurantCount": 4,
  "orderCount": 8,
  "customerCount": 3,
  "courierCount": 2,
  "availableCourierCount": 1
}
```

`GET /api/operator/restaurants?page=0&size=50` returns:

```json
{
  "items": [
    {
      "id": 1,
      "name": "Maple & Main",
      "status": "open",
      "latitude": 45.51,
      "longitude": -73.57,
      "address": null,
      "phone": null
    }
  ],
  "total": 1,
  "page": 0,
  "size": 50
}
```

Customer items contain `id`, `name`, `email`, `phone`, `tdCashBalance`, `latitude`,
and `longitude`. Courier items contain `id`, `name`, `email`, `phone`, `status`,
`latitude`, and `longitude`. Accounts without profile rows remain listed with
null profile fields. Summary account counts include those accounts.

Order items contain `id`, `customerId`, `restaurantId`, `courierId`, `status`,
`dateOrdered`, `acceptedAt`, `rejectionReason`, and `totalPrice`. Dates are UTC ISO
strings or null. Totals follow `Order.getTotalPrice`: the stored total takes precedence because it includes add-ons; otherwise use
line snapshot quantities multiplied by unit prices, or zero without lines. This is an order total, not a payment or refund ledger.

Queries use existing PostgreSQL tables without migrations or new entity mappings.
The configured database needs `users`, `restaurants`, `customer_profiles`,
`courier_profiles`, `orders`, and `order_lines`, including the existing entity
columns `restaurants.address`, `restaurants.phone`, and `orders.created_at`.
The minimal local H2 startup script does not create customer/courier profile tables;
these endpoints require them. Repository tests create their own isolated H2 schema
and never run against the configured Supabase database.
