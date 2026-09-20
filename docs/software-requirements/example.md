# Cart & Order Feature — Software Requirements Specification

| Field | Value |
|---|---|
| **Project** | ShopeeFood Clone |
| **Module** | Cart (Redis) & Order Management |
| **File** | `cart-and-order-spec.md` |
| **Version** | 1.0.0 |
| **Date** | 2026-08-04 |
| **Status** | Draft |

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Overall Description](#2-overall-description)
3. [Data Models](#3-data-models)
4. [Functional Requirements](#4-functional-requirements)
5. [Flow Diagrams](#5-flow-diagrams)
6. [Business Rules](#6-business-rules)
7. [Error Catalogue](#7-error-catalogue)
8. [API Specification](#8-api-specification)
9. [Infrastructure Architecture](#9-infrastructure-architecture)
10. [Non-Functional Requirements](#10-non-functional-requirements)

---

## 1. Introduction

### 1.1 Purpose

This document defines the software requirements for the **Cart** and **Order** modules of the ShopeeFood Clone backend. It serves as the single source of truth for developers, testers, and stakeholders regarding capabilities, constraints, data contracts, and API definitions for both features.

### 1.2 Scope

| Sub-feature | Responsibility |
|---|---|
| **Cart (Redis)** | Ephemeral, user-scoped shopping cart stored in Redis; add/update/remove items; single-restaurant constraint enforcement |
| **Order Creation** | Convert an active cart to a persisted order; pricing computation; delivery fee calculation by geo-distance; payment processing; idempotency lock |
| **Order Management** | List orders (paginated, RSQL-filterable); get a single order; advance order status; cancel; delete |
| **Payment** | Create a payment record associated with the order; auto-capture gateway payments |
| **Kafka Events** | Publish `order.placed` event after successful creation |

### 1.3 Technology Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21 |
| Framework | Spring Boot 4.1.0 |
| Cart persistence | Redis via `StringRedisTemplate` (`RedisCacheService`) |
| Order persistence | PostgreSQL (Flyway migrations V6) |
| ORM | Spring Data JPA / Hibernate |
| Filtering | `io.github.perplexhub:rsql-jpa-spring-boot-starter:7.0.2` |
| Serialisation | Jackson `ObjectMapper` (Cart JSON ↔ Redis) |
| Messaging | Apache Kafka (`KafkaEventPublisher`) |
| Auth | JWT Bearer token (Spring Security) |
| Geo | Haversine formula (server-side) |
| Idempotency | Redis lock key per user (`order:lock:{userId}`) |

---

## 2. Overall Description

### 2.1 High-Level Flow

```
[Browse Menu] → [Add Items to Cart (Redis)] → [Place Order] → [Order Created (PostgreSQL)]
                                                    |
                             +----------------------+---------------------+
                             |                      |                     |
                     [Pricing Computed]    [Payment Processed]   [Kafka Event Published]
                             |
                     [Cart Cleared (Redis)]
```

### 2.2 Cart Lifecycle

```
Empty Cart (Redis miss / null JSON)
  └── addItem()    → Cart created / item appended (30-min TTL reset)
  └── updateQty()  → Item quantity set; if qty <= 0, item removed
  └── removeItem() → Item deleted; if last item, restaurantId/Name nulled
  └── clearCart()  → Redis key deleted
  └── placeOrder() → Cart read → Order created → clearCart() called
```

### 2.3 Order Lifecycle (Status Machine)

```
PENDING → CONFIRMED → PREPARING → READY → PICKED_UP → DELIVERED
    \________________________________________________/
                         |
                      CANCELLED  (only from non-terminal states via explicit cancel)
```

### 2.4 Response Envelope

All endpoints return the standard project envelope:

```json
{
  "status": 200,
  "message": "Human-readable result message",
  "data": { ... }
}
```

For paginated list responses, `data` is a `PageResponse`:

```json
{
  "data": {
    "content": [ ... ],
    "pageNumber": 0,
    "pageSize": 20,
    "totalElements": 42,
    "totalPages": 3,
    "first": true,
    "last": false
  }
}
```

### 2.5 Public vs Protected Endpoints

All Cart and Order endpoints require a valid `Authorization: Bearer <access_token>` header. There are no public endpoints in these modules.

---

## 3. Data Models

### 3.1 Cart (Redis — Ephemeral)

The cart is stored as a JSON-serialised value at Redis key `cart:{userId}` with a **30-minute rolling TTL**. It is **not** persisted to PostgreSQL.

#### Cart Object

| Field | Type | Notes |
|---|---|---|
| `cartId` | `String` | Equal to the authenticated user's UUID string |
| `restaurantId` | `String` (UUID) | Set from the first item added; null when cart is empty |
| `restaurantName` | `String` | Populated from `MenuItem.category.restaurant.name` |
| `items` | `List<CartItem>` | Ordered list of cart line-items |
| `totalAmount` | `BigDecimal` | Sum of `item.price × item.quantity` for all items |

#### CartItem Object

| Field | Type | Notes |
|---|---|---|
| `itemId` | `String` (UUID) | References `menu_items.id` |
| `itemName` | `String` | Snapshot of `MenuItem.name` at add time |
| `quantity` | `int` | Current quantity in cart |
| `price` | `BigDecimal` | Snapshot of `MenuItem.price` at add time |

> [!NOTE]
> Cart item prices are **snapshots**. If the menu item price changes after the item was added to the cart, the cart price is NOT automatically updated. Price is re-validated from the live menu when `OrderService.resolveItems()` is called at checkout.

#### Redis Key Format

```
cart:{userId}   →  JSON string (Cart object)   TTL: 1800 seconds (30 min)
```

---

### 3.2 Order (PostgreSQL — `orders` table)

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | UUID | PK, auto | `gen_random_uuid()` |
| `user_id` | UUID | NOT NULL, FK → `users` | Authenticated user |
| `restaurant_id` | UUID | NOT NULL, FK → `restaurants` | Validated at creation |
| `delivery_address_id` | UUID | nullable, FK → `addresses` | Required when `delivery_method = DELIVERY` |
| `delivery_method` | VARCHAR(20) | NOT NULL | `DELIVERY` or `PICKUP` |
| `status` | VARCHAR(30) | NOT NULL, default `PENDING` | See status machine §2.3 |
| `special_instructions` | TEXT | nullable | Free-text notes to restaurant |
| `created_at` | TIMESTAMPTZ | NOT NULL, immutable | Auto-set by DB |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Auto-updated by DB |

---

### 3.3 OrderPricing (PostgreSQL — `order_pricings` table)

One-to-one with `orders`. Cascades on delete.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | UUID | PK, auto | |
| `order_id` | UUID | NOT NULL, FK → `orders`, UNIQUE | One pricing per order |
| `subtotal` | DECIMAL(10,2) | NOT NULL | Sum of `unitPrice × quantity` for all items |
| `delivery_fee` | DECIMAL(10,2) | default `0.00` | Computed from geo-distance (see §6 BR-09) |
| `platform_fee` | DECIMAL(10,2) | default `0.00` | Fixed fee of **₫2,000** per order |
| `discount_amount` | DECIMAL(10,2) | default `0.00` | Reserved for future promotion integration |
| `total_amount` | DECIMAL(10,2) | NOT NULL | `subtotal + delivery_fee + platform_fee - discount_amount` |
| `created_at` | TIMESTAMPTZ | NOT NULL | |
| `updated_at` | TIMESTAMPTZ | NOT NULL | |

---

### 3.4 OrderItem (PostgreSQL — `order_items` table)

One-to-many with `orders`. Cascades on delete.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | UUID | PK, auto | |
| `order_id` | UUID | NOT NULL, FK → `orders` | |
| `menu_item_id` | UUID | NOT NULL, FK → `menu_items` | Live reference to menu item |
| `quantity` | INT | NOT NULL | |
| `unit_price` | DECIMAL(10,2) | NOT NULL | Price **at time of order** (re-read from DB) |
| `special_notes` | TEXT | nullable | Per-item instructions |
| `created_at` | TIMESTAMPTZ | NOT NULL | |

---

### 3.5 Payment (PostgreSQL — `payments` table)

One-to-one with `orders`. Cascades on delete.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | UUID | PK, auto | |
| `order_id` | UUID | NOT NULL, FK → `orders`, UNIQUE | One payment per order |
| `amount` | DECIMAL(10,2) | NOT NULL | Equal to `order_pricing.total_amount` |
| `currency` | VARCHAR(3) | NOT NULL, default `VND` | |
| `method_type` | VARCHAR(30) | nullable | `COD` or `TRANSFER` |
| `gateway_token` | VARCHAR(255) | nullable | Non-null = gateway payment; triggers auto-capture |
| `party_name` | VARCHAR(50) | nullable | `MOMO` or `VNPAY` |
| `status` | VARCHAR(20) | NOT NULL, default `PENDING` | `PENDING`, `AUTHORIZED`, `CAPTURED`, `FAILED`, or `REFUNDED` |
| `paid_at` | TIMESTAMPTZ | nullable | Set when `status = CAPTURED` |
| `created_at` | TIMESTAMPTZ | NOT NULL | |

---

### 3.6 Database Indexes

| Index | Table | Column(s) | Purpose |
|---|---|---|---|
| `idx_orders_user_id` | `orders` | `user_id` | Filter orders by user |
| `idx_orders_restaurant_id` | `orders` | `restaurant_id` | Filter orders by restaurant |
| `idx_orders_delivery_address_id` | `orders` | `delivery_address_id` | Join with addresses |
| `idx_order_pricings_order_id` | `order_pricings` | `order_id` | Order → pricing lookup |
| `idx_order_items_order_id` | `order_items` | `order_id` | Order → items lookup |
| `idx_order_items_menu_item_id` | `order_items` | `menu_item_id` | Menu item reference |
| `idx_payments_order_id` | `payments` | `order_id` | Order → payment lookup |

---

## 4. Functional Requirements

### 4.1 Cart — Get Cart

| ID | Requirement |
|---|---|
| CT-01 | The system **shall** return the current cart for the authenticated user. |
| CT-02 | If no cart exists in Redis, the system **shall** return an empty cart object (with an empty items list and `totalAmount = 0`). |
| CT-03 | Fetching a cart **shall not** reset its TTL. |

---

### 4.2 Cart — Add Item

| ID | Requirement |
|---|---|
| CT-04 | The system **shall** validate that the `itemId` references an existing, available `MenuItem`. |
| CT-05 | The system **shall** reject the request if the menu item's `isAvailable` flag is `false` (error `40033 OUT_OF_STOCK`). |
| CT-06 | If the cart already contains items from a **different** restaurant, the system **shall** reject the add (error `40044 DIFFERENT_RESTAURANT_IN_CART`). |
| CT-07 | If the cart already contains the same `itemId`, the system **shall** increment its quantity by the requested amount. |
| CT-08 | If the item is new, a `CartItem` **shall** be appended with the live `MenuItem.name` and `MenuItem.price` as snapshots. |
| CT-09 | `totalAmount` **shall** be recalculated as `Σ(price × quantity)` after every mutation. |
| CT-10 | The updated cart **shall** be saved to Redis with a rolling 30-minute TTL. |

---

### 4.3 Cart — Update Item Quantity

| ID | Requirement |
|---|---|
| CT-11 | The system **shall** find the cart item by `itemId` and set its quantity to the supplied value. |
| CT-12 | If the supplied quantity is `<= 0`, the item **shall** be removed from the cart. |
| CT-13 | If the updated cart becomes empty, `restaurantId` and `restaurantName` **shall** be set to `null`. |
| CT-14 | If the `itemId` is not found in the cart, the system **shall** throw `MENU_ITEM_NOT_FOUND`. |

---

### 4.4 Cart — Remove Item

| ID | Requirement |
|---|---|
| CT-15 | The system **shall** remove the specified `itemId` from the cart. |
| CT-16 | If the `itemId` is not in the cart, the system **shall** throw `MENU_ITEM_NOT_FOUND`. |
| CT-17 | If the cart becomes empty after removal, `restaurantId` and `restaurantName` **shall** be set to `null`. |

---

### 4.5 Cart — Clear Cart

| ID | Requirement |
|---|---|
| CT-18 | The system **shall** delete the Redis key `cart:{userId}`, effectively clearing the cart. |
| CT-19 | Clearing a non-existent cart **shall** be a no-op (idempotent). |

---

### 4.6 Order — Place Order

| ID | Requirement |
|---|---|
| O-01 | The system **shall** acquire a Redis idempotency lock (`order:lock:{userId}`) with a 10-second TTL before processing. |
| O-02 | If the lock key already exists, the system **shall** reject with error `40000 INVALID_INPUT` (order in progress). |
| O-03 | The system **shall** validate that the referenced restaurant exists and that `isOpen == true`. |
| O-04 | If `deliveryMethod = DELIVERY`, the system **shall** require a non-null `deliveryAddressId` and verify the address belongs to the authenticated user. |
| O-05 | The system **shall** load the user's active cart from Redis. |
| O-06 | If the cart is empty, the system **shall** reject with `40040 CART_IS_EMPTY`. |
| O-07 | The system **shall** verify that the cart's `restaurantId` matches the order's `restaurantId`. If they differ, reject with `40044 DIFFERENT_RESTAURANT_IN_CART`. |
| O-08 | For each `CartItem`, the system **shall** load the live `MenuItem` from PostgreSQL to obtain the authoritative `unitPrice`. |
| O-09 | The system **shall** reject order creation if any cart item references an unavailable (`isAvailable = false`) menu item (`40033 OUT_OF_STOCK`). |
| O-10 | The system **shall** reject order creation if any cart item's menu item does not belong to the target restaurant (`40000 INVALID_INPUT`). |
| O-11 | The system **shall** compute the delivery fee using the Haversine formula (see §6 BR-09). |
| O-12 | The system **shall** build an `OrderPricing` record with `subtotal`, `deliveryFee`, `platformFee` (fixed ₫2,000), `discountAmount` (0 for now), and `totalAmount`. |
| O-13 | The system **shall** persist the `Order`, `OrderPricing`, and `OrderItem` records in a single `@Transactional` block. |
| O-14 | The system **shall** create a `Payment` record associated with the order. |
| O-15 | If `paymentMethod.gatewayToken` is non-null/non-blank, the system **shall** set `Payment.status = CAPTURED`, `Payment.paidAt = now()`, and advance `Order.status` to `CONFIRMED`. |
| O-16 | If `gatewayToken` is null (COD), `Payment.status` **shall** remain `PENDING` and `Order.status` stays `PENDING`. |
| O-17 | After successful persistence, the system **shall** call `cartService.clearCart()` to delete the Redis cart key. |
| O-18 | After cart clearance, the system **shall** publish an `OrderPlacedEvent` Kafka event via `KafkaEventPublisher`. |
| O-19 | The Redis idempotency lock **shall** be released in a `finally` block regardless of success or failure. |
| O-20 | The system **shall** return the persisted `OrderResponse` with HTTP 201 Created. |

---

### 4.7 Order — List Orders

| ID | Requirement |
|---|---|
| O-21 | The system **shall** return a paginated list of all orders. |
| O-22 | The list **shall** support RSQL-based filtering via an optional `filter` query parameter. |
| O-23 | Page size **shall** be server-bounded (default `5`, max `20` — enforced by `PaginationUtils`). |
| O-24 | Default sort is by `createdAt` ascending (overridable via `sort` parameter). |

---

### 4.8 Order — Get by ID

| ID | Requirement |
|---|---|
| O-25 | The system **shall** return a single order by UUID, including its `pricing` and `items` detail. |
| O-26 | If the order does not exist, the system **shall** return `40041 ORDER_NOT_FOUND`. |

---

### 4.9 Order — Update Status

| ID | Requirement |
|---|---|
| O-27 | The system **shall** accept a `PUT /api/orders/{id}` request with a `status` field. |
| O-28 | If the new status is `CANCELLED` and the order is **not** already cancelled, the system **shall** throw `40042 CANNOT_CANCEL_ORDER`. |
| O-29 | If the new status is `DELIVERED`, the system **shall** set `Payment.status = CAPTURED` and `Payment.paidAt = now()` if the payment is not already captured. |
| O-30 | Status transitions are caller-controlled — the API does not enforce strict state-machine ordering beyond the cancel guard in O-28. |

> [!WARNING]
> The current cancel guard (O-28) raises an exception when the status is `CANCELLED` regardless of the current state, which means the only valid caller use-case for the `CANCELLED` transition is already-cancelled (no-op). This behaviour may need revision in future iterations to correctly support cancellation from valid non-terminal states.

---

### 4.10 Order — Delete

| ID | Requirement |
|---|---|
| O-31 | The system **shall** hard-delete an order by UUID. |
| O-32 | Deletion cascades to `order_items`, `order_pricings`, and `payments` (via DB `ON DELETE CASCADE`). |
| O-33 | If the order does not exist, the system **shall** return `40041 ORDER_NOT_FOUND`. |

---

## 5. Flow Diagrams

### 5.1 Cart — Add Item Flow

```
Client                          CartService                    Redis          DB (MenuItems)
  |                                  |                           |                 |
  |-- POST /api/cart/items --------> |                           |                 |
  |   { itemId, quantity }           |                           |                 |
  |                                  |-- get(cart:{userId}) ---> |                 |
  |                                  |<-- JSON / null -----------|                 |
  |                                  |-- findById(itemId) --------------------------->|
  |                                  |<-- MenuItem (name, price, isAvailable) --------|
  |                                  |-- isAvailable == false? -> throw OUT_OF_STOCK  |
  |                                  |-- cart.restaurantId != item.restaurantId?       |
  |                                  |      -> throw DIFFERENT_RESTAURANT_IN_CART      |
  |                                  |-- itemExists in cart?                           |
  |                                  |   +-- Yes: increment quantity                   |
  |                                  |   +-- No:  append CartItem(snapshot)            |
  |                                  |-- recalculate totalAmount                       |
  |                                  |-- set(cart:{userId}, JSON, 1800s) -----------> |
  |<-- 200 { Cart } ---------------- |                           |                 |
```

---

### 5.2 Order — Place Order Flow

```
Client               OrderService               Redis              DB              Kafka
  |                       |                        |                |                |
  |-- POST /api/orders --> |                        |                |                |
  |                        |-- hasKey(lock) ------> |                |                |
  |                        |  YES -> throw INVALID_INPUT (40000)     |                |
  |                        |-- set(lock, LOCKED, 10s) -> |           |                |
  |                        |                        |                |                |
  |                        |-- findById(restaurantId) ------------>  |                |
  |                        |   isOpen? No -> throw RESTAURANT_CLOSED |                |
  |                        |-- resolveDeliveryAddress() -----------> |                |
  |                        |   owner check -> throw FORBIDDEN if not user's           |
  |                        |-- getCart(userId) -------> |            |                |
  |                        |   empty? -> throw CART_IS_EMPTY         |                |
  |                        |   restaurantId mismatch? -> throw DIFFERENT_RESTAURANT   |
  |                        |                        |                |                |
  |                        | FOR EACH CartItem:     |                |                |
  |                        |   findById(itemId) --------------------------->           |
  |                        |   isAvailable? No -> throw OUT_OF_STOCK                  |
  |                        |   belongs to restaurant? No -> throw INVALID_INPUT       |
  |                        |   unitPrice = menuItem.getPrice()       |                |
  |                        |                        |                |                |
  |                        |-- calculateDeliveryFee() (Haversine)   |                |
  |                        |-- buildPricing()        |                |                |
  |                        |-- orderRepository.save() --------->     |                |
  |                        |-- paymentRepository.save() -------->    |                |
  |                        |   gatewayToken present? -> CAPTURED + CONFIRMED          |
  |                        |                        |                |                |
  |                        |-- cartService.clearCart() (delete) --> |                 |
  |                        |-- publishOrderPlaced(order) --------------------------> |
  |                        |-- delete(lock) -------> |               |                |
  |<-- 201 { OrderResponse }|                        |               |                |
```

---

### 5.3 Delivery Fee Calculation

```
calculateDeliveryFee(request, deliveryAddress, restaurant)
  |
  +-- deliveryMethod != DELIVERY  -> return ₫0 (PICKUP)
  |
  +-- restaurant.address == null OR lat/lng == null
  |       -> return BASE_DELIVERY_FEE (₫15,000)
  |
  +-- deliveryAddress.lat/lng == null
  |       -> return BASE_DELIVERY_FEE (₫15,000)
  |
  +-- distance = haversine(restLat, restLng, addrLat, addrLng)   [Earth R = 6,371,000 m]
  |
  +-- distance > 15,000 m  -> throw OUT_OF_DELIVERY_AREA (40052)
  |
  +-- distance > 2,000 m
  |       extraKm = ceil((distance - 2,000) / 1,000)
  |       -> return ₫15,000 + (extraKm × ₫5,000)
  |
  +-- distance <= 2,000 m  -> return BASE_DELIVERY_FEE (₫15,000)
```

### 5.4 Order Status Update Decision Tree

```
PUT /api/orders/{id}  { status: <NEW_STATUS> }
  |
  +-- findOrThrow(id)
  |
  +-- NEW_STATUS == CANCELLED AND order.status != CANCELLED
  |     -> throw CANNOT_CANCEL_ORDER (40042)
  |
  +-- order.setStatus(NEW_STATUS)
  |
  +-- NEW_STATUS == DELIVERED?
  |     +-- payment.status != CAPTURED
  |           -> payment.status = CAPTURED
  |           -> payment.paidAt = now()
  |           -> paymentRepository.save(payment)
  |
  +-- orderRepository.save(order)
  +-- return OrderResponse
```

---

## 6. Business Rules

### Cart Rules

| ID | Rule |
|---|---|
| BR-01 | A cart belongs exclusively to a single authenticated user and is keyed by their UUID. |
| BR-02 | A cart can only contain items from a **single** restaurant. Mixing items from different restaurants is forbidden. |
| BR-03 | Cart item quantities must be positive integers (≥ 1) when adding. Setting quantity ≤ 0 during an update removes the item. |
| BR-04 | Item prices and names in the cart are **snapshots** taken at add-time from the live `MenuItem`. They are NOT automatically updated if the menu changes. |
| BR-05 | A cart that hasn't been mutated for **30 minutes** expires automatically in Redis. The TTL is reset on every write. |
| BR-06 | The cart is authoritative on **quantity only**; the order service re-reads all prices from the database during checkout to ensure pricing correctness. |

### Order Creation Rules

| ID | Rule |
|---|---|
| BR-07 | Only one order can be placed per user at a time. A Redis lock (`order:lock:{userId}`, TTL 10 s) prevents duplicate concurrent submissions. |
| BR-08 | An order can only be placed against an **open** restaurant (`isOpen == true`). |
| BR-09 | **Delivery fee tiers** (Haversine straight-line distance): |

```
PICKUP orders:              fee = ₫0
≤ 2 km:                     fee = ₫15,000 (base)
> 2 km and ≤ 15 km:         fee = ₫15,000 + ⌈(distance_m - 2,000) / 1,000⌉ × ₫5,000
> 15 km:                    ORDER REJECTED (OUT_OF_DELIVERY_AREA)
Coordinates missing:        fee = ₫15,000 (fallback, no rejection)
```

| ID | Rule |
|---|---|
| BR-10 | A platform fee of **₫2,000** is charged on every order regardless of delivery method or total value. |
| BR-11 | Discount/voucher application is reserved for future implementation; `discountAmount = 0` for all current orders. |
| BR-12 | A `deliveryAddressId` is **mandatory** for `DELIVERY` orders and **must** belong to the authenticated user. |
| BR-13 | For `PICKUP` orders, `deliveryAddressId` may be null and no delivery fee is applied. |
| BR-14 | All item unit prices used in the order are sourced from live `MenuItem.price` in PostgreSQL at checkout time, not from the cart snapshot. |
| BR-15 | If a `gatewayToken` is provided in the payment method (online payment), the payment is immediately **CAPTURED** and the order advances to **CONFIRMED**. |
| BR-16 | COD orders (`gatewayToken = null/blank`) start with `Payment.status = PENDING` and `Order.status = PENDING`. |
| BR-17 | After a successful order creation, the user's Redis cart **is always cleared** regardless of payment method. |
| BR-18 | An `OrderPlacedEvent` Kafka event is published after every successful order creation. The message key is `userId` to ensure partition ordering per user. |

### Order Management Rules

| ID | Rule |
|---|---|
| BR-19 | Setting status to `DELIVERED` automatically captures any pending COD payment. |
| BR-20 | Order deletion is a hard-delete and cascades to `order_items`, `order_pricings`, and `payments`. |
| BR-21 | `totalAmount = subtotal + deliveryFee + platformFee - discountAmount`. All values are `DECIMAL(10,2)` in VND. |

---

## 7. Error Catalogue

### Cart Errors

| Error Code | HTTP | Constant | Trigger |
|---|---|---|---|
| `40031` | 400 | `MENU_ITEM_NOT_FOUND` | `itemId` in add/update/remove does not exist, or item not found in cart |
| `40033` | 400 | `OUT_OF_STOCK` | Menu item's `isAvailable` is `false` when adding to cart |
| `40044` | 400 | `DIFFERENT_RESTAURANT_IN_CART` | Attempting to add item from a different restaurant than items already in cart |
| `40000` | 400 | `INVALID_INPUT` | Cart JSON serialisation failure |
| `40100` | 401 | `UNAUTHENTICATED` | No valid Bearer token provided |

### Order Errors

| Error Code | HTTP | Constant | Trigger |
|---|---|---|---|
| `40000` | 400 | `INVALID_INPUT` | Redis lock key exists (duplicate order in-flight); item belongs to wrong restaurant |
| `40030` | 400 | `RESTAURANT_NOT_FOUND` | `restaurantId` does not reference an existing restaurant |
| `40031` | 400 | `MENU_ITEM_NOT_FOUND` | Cart item's `menuItemId` not found in DB at checkout time |
| `40032` | 400 | `RESTAURANT_CLOSED` | Target restaurant's `isOpen == false` |
| `40033` | 400 | `OUT_OF_STOCK` | Menu item's `isAvailable == false` at checkout time |
| `40040` | 400 | `CART_IS_EMPTY` | User's cart is empty or null when placing order |
| `40041` | 400 | `ORDER_NOT_FOUND` | `orderId` in GET/PUT/DELETE does not exist |
| `40042` | 400 | `CANNOT_CANCEL_ORDER` | Attempting to set status to `CANCELLED` when current status is not `CANCELLED` |
| `40044` | 400 | `DIFFERENT_RESTAURANT_IN_CART` | Cart's `restaurantId` ≠ order's `restaurantId` |
| `40051` | 400 | `DELIVERY_ADDRESS_NOT_FOUND` | `deliveryMethod = DELIVERY` but `deliveryAddressId` is null |
| `40052` | 400 | `OUT_OF_DELIVERY_AREA` | Haversine distance between restaurant and delivery address > 15 km |
| `40053` | 400 | `ADDRESS_NOT_FOUND` | `deliveryAddressId` not found in DB |
| `40300` | 403 | `FORBIDDEN` | Delivery address belongs to a different user |
| `40100` | 401 | `UNAUTHENTICATED` | No valid Bearer token provided |

---

## 8. API Specification

### Conventions

- **Base URL:** `http://localhost:8080`
- **Auth:** `Authorization: Bearer <jwt>` (required for all endpoints)
- **Content-Type:** `application/json`
- **ID type:** UUID (e.g. `550e8400-e29b-41d4-a716-446655440000`)
- **Currency:** Vietnamese Đồng (VND), numeric `BigDecimal` values

---

### 8.1 Cart Endpoints

---

#### `GET /api/cart` — Get Current Cart

Returns the current user's cart. Returns an empty cart if none exists.

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Cart retrieved successfully",
  "data": {
    "cartId": "7f3e2c1b-...",
    "restaurantId": "a1b2c3d4-...",
    "restaurantName": "KFC Hoàng Văn Thụ",
    "items": [
      {
        "itemId": "d4e5f6a7-...",
        "itemName": "Crispy Fried Chicken Combo",
        "quantity": 2,
        "price": 50000.00
      }
    ],
    "totalAmount": 100000.00
  }
}
```

---

#### `POST /api/cart/items` — Add Item to Cart

**Request Body**

```json
{
  "itemId": "uuid (required)",
  "quantity": "integer (required, >= 1)"
}
```

**Validation**

| Field | Rule |
|---|---|
| `itemId` | Not blank; valid UUID format; must reference an existing, available menu item |
| `quantity` | Not null; must be ≥ 1 |

**Response `200 OK`** — Returns the updated `Cart` object (same shape as GET /api/cart).

**Error Responses**

| Condition | Error Code | HTTP |
|---|---|---|
| Menu item not found | `40031` | 400 |
| Item is out of stock | `40033` | 400 |
| Item from different restaurant | `40044` | 400 |

---

#### `PUT /api/cart/items/{itemId}` — Update Item Quantity

**Path Parameters**

| Parameter | Type | Description |
|---|---|---|
| `itemId` | String (UUID) | The item to update (matches `CartItem.itemId`) |

**Request Body**

```json
{
  "quantity": "integer (required, >= 0)"
}
```

> [!NOTE]
> Setting `quantity = 0` removes the item from the cart entirely.

**Response `200 OK`** — Returns the updated `Cart` object.

**Error Responses**

| Condition | Error Code | HTTP |
|---|---|---|
| Item not found in cart | `40031` | 400 |

---

#### `DELETE /api/cart/items/{itemId}` — Remove Item from Cart

**Path Parameters**

| Parameter | Type | Description |
|---|---|---|
| `itemId` | String (UUID) | The item to remove |

**Response `200 OK`** — Returns the updated `Cart` object.

**Error Responses**

| Condition | Error Code | HTTP |
|---|---|---|
| Item not found in cart | `40031` | 400 |

---

#### `DELETE /api/cart` — Clear Cart

Removes all items from the cart by deleting the Redis key.

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Cart cleared successfully"
}
```

---

### 8.2 Order Endpoints

---

#### `POST /api/orders` — Place Order

Converts the authenticated user's active cart into a persisted order.

**Request Body**

```json
{
  "restaurantId": "uuid (required)",
  "deliveryAddressId": "uuid (required if deliveryMethod = DELIVERY, else nullable)",
  "deliveryMethod": "DELIVERY | PICKUP (required)",
  "specialInstructions": "string (optional, max TEXT)",
  "paymentMethod": {
    "method": "COD | TRANSFER (required)",
    "provider": "MOMO | VNPAY (required if method = TRANSFER)"
  }
}
```

**Validation**

| Field | Rule |
|---|---|
| `restaurantId` | Not null; must reference an open, existing restaurant |
| `deliveryMethod` | Not null; one of `DELIVERY`, `PICKUP` |
| `deliveryAddressId` | Required and user-owned when `deliveryMethod = DELIVERY` |
| `paymentMethod` | Not null; nested object with own validation |
| `paymentMethod.method` | Not null; one of `COD`, `TRANSFER` |
| `paymentMethod.provider` | Optional when `method = COD`; one of `MOMO`, `VNPAY` |

**Response `201 Created`**

```json
{
  "status": 201,
  "message": "Order placed successfully",
  "data": {
    "order": {
      "id": "uuid",
      "userId": "uuid",
      "restaurantId": "uuid",
      "restaurantName": "KFC Hoàng Văn Thụ",
      "deliveryAddressId": "uuid",
      "deliveryAddressLine": "123 Nguyễn Trãi, Quận 1, Hồ Chí Minh",
      "deliveryMethod": "DELIVERY",
      "status": "PENDING",
      "specialInstructions": "No napkins please",
      "pricing": {
        "id": "uuid",
        "subtotal": 100000.00,
        "deliveryFee": 15000.00,
        "platformFee": 2000.00,
        "discountAmount": 0.00,
        "totalAmount": 117000.00
      },
      "items": [
        {
          "id": "uuid",
          "menuItemId": "uuid",
          "itemName": "Crispy Fried Chicken Combo",
          "quantity": 2,
          "unitPrice": 50000.00,
          "specialNotes": null
        }
      ],
      "createdAt": "2026-08-04T14:00:00+07:00",
      "updatedAt": "2026-08-04T14:00:00+07:00"
    },
    "paymentUrl": "https://payment-gateway.com/pay/checkout-token"
  }
}
```

**Error Responses**

| Condition | Error Code | HTTP |
|---|---|---|
| Duplicate order in-flight | `40000` | 400 |
| Restaurant not found | `40030` | 400 |
| Restaurant is closed | `40032` | 400 |
| Cart is empty | `40040` | 400 |
| Cart restaurant ≠ order restaurant | `40044` | 400 |
| Item out of stock | `40033` | 400 |
| Item not found at checkout | `40031` | 400 |
| Delivery address missing (DELIVERY) | `40051` | 400 |
| Address not found | `40053` | 400 |
| Address belongs to another user | `40300` | 403 |
| Delivery distance > 15 km | `40052` | 400 |

---

#### `GET /api/orders` — List Orders

Returns a paginated list of orders with optional RSQL filtering.

**Query Parameters**

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `filter` | string | No | — | RSQL filter expression |
| `page` | int | No | `0` | Zero-based page index |
| `size` | int | No | `5` | Page size (max 20) |
| `sort` | string | No | `createdAt,asc` | Sort field and direction |

**Common Filter Examples**

| Goal | RSQL Expression |
|---|---|
| Orders in PENDING status | `filter=status==PENDING` |
| Orders for specific restaurant | `filter=restaurant.id=={uuid}` |
| DELIVERY orders only | `filter=deliveryMethod==DELIVERY` |
| Orders placed today | `filter=createdAt=ge=2026-08-04T00:00:00+07:00` |

**Response `200 OK`** — Returns `PageResponse<OrderResponse>`.

---

#### `GET /api/orders/{id}` — Get Order by ID

**Path Parameters**

| Parameter | Type | Description |
|---|---|---|
| `id` | UUID | Order identifier |

**Response `200 OK`** — Returns a single `OrderResponse` (same shape as POST /api/orders response data).

**Error Responses**

| Condition | Error Code | HTTP |
|---|---|---|
| Order not found | `40041` | 400 |

---

#### `PUT /api/orders/{id}` — Update Order Status

**Path Parameters**

| Parameter | Type | Description |
|---|---|---|
| `id` | UUID | Order identifier |

**Request Body**

```json
{
  "status": "PENDING | CONFIRMED | PREPARING | READY | PICKED_UP | DELIVERED | CANCELLED (required)"
}
```

**Response `200 OK`** — Returns the updated `OrderResponse`.

**Error Responses**

| Condition | Error Code | HTTP |
|---|---|---|
| Order not found | `40041` | 400 |
| Attempt to cancel a non-cancelled order | `40042` | 400 |

---

#### `DELETE /api/orders/{id}` — Delete Order

Hard-deletes the order and cascades to items, pricing, and payment.

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Order deleted successfully"
}
```

**Error Responses**

| Condition | Error Code | HTTP |
|---|---|---|
| Order not found | `40041` | 400 |

---

## 9. Infrastructure Architecture

### 9.1 Redis — Cart & Lock Storage

```
Implementation:     RedisCacheService implements CacheService
Spring client:      StringRedisTemplate
Config:             spring.data.redis.host / .port (from application-common.yaml)
Connect timeout:    1,000 ms
Read timeout:       1,000 ms
```

**CacheService Interface Methods Used**

| Method | Signature | Usage |
|---|---|---|
| `set` | `(key, value, durationSeconds)` | Save cart JSON; acquire idempotency lock |
| `get` | `(key) → String` | Load cart JSON |
| `delete` | `(key)` | Clear cart; release lock |
| `hasKey` | `(key) → boolean` | Check idempotency lock before order placement |

**Redis Keys**

| Key Pattern | Value | TTL | Owner |
|---|---|---|---|
| `cart:{userId}` | JSON string (`Cart`) | 1,800 s (30 min), rolling | `CartService` |
| `order:lock:{userId}` | `"LOCKED"` | 10 s | `OrderService` |
| `otp:register:{email}` | 6-digit OTP string | 300 s (5 min) | `UserOtpService` (Auth module) |

> [!NOTE]
> `RedisCacheService.hasKey()` catches `RedisConnectionFailureException` and returns `true` (safety-first: treats Redis unreachability as lock held, preventing order submission during Redis outage).

---

### 9.2 PostgreSQL — Order Domain

Introduced via **Flyway migration V6** (`V6__create_orders_tables.sql`):

```
orders            (1)
  ├── order_pricings  (1:1, ON DELETE CASCADE)
  ├── order_items     (1:many, ON DELETE CASCADE)
  └── payments        (1:1, ON DELETE CASCADE)
```

All primary keys use `gen_random_uuid()`. All timestamps use `TIMESTAMPTZ`.

---

### 9.3 Kafka — Order Event Publishing

```
Topic:         order.placed   (configured in KafkaTopicConfig.TOPIC_ORDER_PLACED)
Event POJO:    OrderPlacedEvent
Payload:       { correlationId, orderId, userId, restaurantId, totalAmount, placedAt }
Message key:   userId (UUID string) — ensures per-user ordering within a partition
Publisher:     KafkaEventPublisher.publishOrderPlaced(Order)
Delivery:      Fire-and-forget with async success/error callbacks (CompletableFuture)
Producer:      acks=all, retries=3, enable.idempotence=true
```

---

### 9.4 Pricing Constants

| Constant | Value | Source |
|---|---|---|
| `BASE_DELIVERY_FEE` | ₫15,000 | `OrderService` static field |
| `PLATFORM_FEE` | ₫2,000 | `OrderService` static field |
| `MAX_DELIVERY_DISTANCE` | 15,000 m | `OrderService` static field |
| `BASE_DISTANCE_M` | 2,000 m | `OrderService` static field |
| `EXTRA_FEE_PER_KM` | ₫5,000 | `OrderService` static field |

> [!TIP]
> These are currently hardcoded constants. Consider externalising them to `application.yaml` (e.g. `app.delivery.base-fee`) for easier operational tuning without a redeploy.

---

## 10. Non-Functional Requirements

| ID | Category | Requirement |
|---|---|---|
| NFR-01 | Performance | Cart reads and writes **should** complete in < 10 ms (Redis round-trip). |
| NFR-02 | Performance | Order creation **should** complete end-to-end in < 500 ms under normal load (excluding Kafka publish time). |
| NFR-03 | Reliability | Redis connection failure in `hasKey()` **must** default to `true` (lock-held) to prevent duplicate orders during Redis outage. |
| NFR-04 | Reliability | The idempotency lock **must** be released in a `finally` block to prevent permanent lock-out on order service exceptions. |
| NFR-05 | Consistency | Cart prices are **snapshots**; the checkout process **must** re-validate all prices and availability from the live DB before persisting. |
| NFR-06 | Security | All cart and order endpoints **must** require a valid JWT access token; user ID is extracted from `SecurityContext` — never trusted from request body. |
| NFR-07 | Scalability | The 30-minute rolling TTL on cart keys ensures Redis memory is self-managing for idle sessions. |
| NFR-08 | Correctness | Delivery fee calculation **must** use the Haversine formula with Earth radius = 6,371,000 m. These are straight-line distances, not road distances. |
| NFR-09 | Correctness | Extra delivery km **must** be calculated using `Math.ceil()` — partial kilometres are rounded up. |
| NFR-10 | Observability | Cart JSON deserialisation failures **should** be logged at ERROR level; the service recovers by returning an empty cart (silent degradation). |
| NFR-11 | Extensibility | `discountAmount` and `DISCOUNT_AMOUNT = 0` are reserved in the schema and service layer for future promo/voucher integration without a schema migration. |
| NFR-12 | Decoupling | Kafka event publishing (`order.placed`) **must** occur after the order is persisted and the cart is cleared. A Kafka send failure **should** be logged but **must not** roll back the order. |
| NFR-13 | Correctness | The `deliveryAddressLine` in `OrderResponse` is computed by `OrderMapper` as `line1 + line2 (if present) + city` — consumers should treat this as a display string only. |
