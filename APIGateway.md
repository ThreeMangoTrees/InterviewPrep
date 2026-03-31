# API Gateway

## What is an API Gateway?

An API Gateway is a server that acts as the **single entry point** for all client requests into a backend system. It sits between the client (browser, mobile app, third-party service) and the collection of backend services, routing requests to the appropriate service and returning the response.

Think of it as a **reverse proxy with superpowers** — it does far more than just forwarding requests.

```
Client → API Gateway → [Service A, Service B, Service C, ...]
```

---

## What Does an API Gateway Do?

### Core Functions

| Function | Description |
|---|---|
| **Request Routing** | Routes incoming requests to the correct microservice based on path, method, or headers |
| **Authentication & Authorization** | Validates JWT tokens, API keys, OAuth tokens before request reaches backend |
| **Rate Limiting & Throttling** | Prevents abuse by capping requests per user/IP/plan |
| **Load Balancing** | Distributes traffic across multiple instances of a service |
| **SSL/TLS Termination** | Handles HTTPS decryption so backend services communicate over plain HTTP internally |
| **Request/Response Transformation** | Translates between protocols (REST ↔ gRPC), reshapes payloads, adds/removes headers |
| **Caching** | Stores responses to reduce backend load for repeated identical requests |
| **Logging & Monitoring** | Centralized observability — every request passes through, making it easy to trace |
| **Circuit Breaking** | Stops forwarding requests to a failing service to prevent cascade failures |
| **API Composition / Aggregation** | Merges responses from multiple services into a single response for the client |

### Request Lifecycle Through an API Gateway

```
1. Client sends request
2. Gateway receives request
3. SSL termination (decrypt HTTPS)
4. Authentication check (validate token)
5. Rate limit check (is this client within quota?)
6. Route resolution (which service handles this?)
7. Request transformation (modify headers, format)
8. Forward to backend service
9. Receive response
10. Response transformation
11. Cache response (if applicable)
12. Return to client
```

---

## Why Is an API Gateway Necessary?

### Problem: Direct Client-to-Service Communication

Without a gateway, clients would need to:
- Know the address of every microservice
- Handle auth for every service separately
- Make multiple network calls to aggregate data
- Deal with different protocols per service

### Solution: API Gateway as Facade

```
WITHOUT gateway:
  Mobile App → Service A (auth)
  Mobile App → Service B (auth)
  Mobile App → Service C (auth)
  (3 round trips, 3 auth checks, client knows internal topology)

WITH gateway:
  Mobile App → API Gateway → Service A
                           → Service B
                           → Service C
  (1 round trip for client, auth once, topology hidden)
```

### Key Benefits

1. **Decoupling** — Clients are insulated from backend topology changes. You can split/merge services without changing client code.
2. **Security** — Single enforcement point for auth, IP whitelisting, input validation. No service is directly exposed.
3. **Cross-cutting concerns** — Auth, logging, tracing handled once in gateway, not duplicated in every service.
4. **Protocol translation** — Mobile clients use REST; internal services can use gRPC or message queues.
5. **Versioning** — Route `/v1/` and `/v2/` to different service versions without client changes.
6. **Developer experience** — External developers (third-party integrations) get a clean, stable API surface.

---

## How API Gateway Components Vary by System Type

The *same* concept of an API Gateway applies universally, but the **emphasis on specific components shifts** dramatically depending on the domain's read/write patterns, data size, and latency requirements.

---

### 1. Streaming Service (Netflix, Spotify, YouTube)

**Characteristics:** High read volume, large binary payloads, personalized content, CDN-heavy.

```
Client → API Gateway → Catalog Service
                     → Recommendation Service
                     → User Profile Service
                     → Playback/Token Service → CDN (actual video bytes bypass gateway)
```

**Gateway priorities:**

| Component | Emphasis | Reason |
|---|---|---|
| **Auth / DRM token issuance** | Critical | Must validate subscription tier before issuing playback tokens |
| **Caching** | Heavy | Catalog, thumbnails, metadata are highly cacheable |
| **Rate limiting** | Moderate | Prevent scraping of catalog; less critical for actual playback |
| **Request aggregation** | High | "Load homepage" = recommendations + continue-watching + trending in one call |
| **Protocol** | HTTP/2, WebSocket | Efficient multiplexing for multiple parallel streams on page load |
| **CDN bypass** | Essential | Video bytes go directly CDN → client; gateway only handles metadata/tokens |

**Key difference:** The gateway handles *control plane* (auth, metadata, personalization) but video bytes flow outside the gateway through CDN edge nodes. Routing for actual media is done at the CDN layer, not the API Gateway.

---

### 2. Social Media Service (Twitter/X, Instagram, Facebook)

**Characteristics:** High read AND write volume, fan-out writes, real-time feeds, user-generated content uploads.

```
Client → API Gateway → Timeline/Feed Service
                     → Post/Tweet Service
                     → User Graph Service (follows)
                     → Media Upload Service
                     → Notification Service
                     → Search Service
```

**Gateway priorities:**

| Component | Emphasis | Reason |
|---|---|---|
| **Rate limiting** | Very High | Prevent spam, bot activity, API abuse at post/like endpoints |
| **Auth** | Critical | Session tokens, OAuth for third-party apps |
| **Request routing by content type** | High | JSON for posts, multipart for media uploads routed differently |
| **Websocket / SSE support** | High | Real-time notifications, live feeds require persistent connections |
| **Write path separation** | High | Read (feed) and write (post) paths may hit entirely different clusters |
| **Aggregation** | High | Feed = posts + user data + like counts merged into one response |
| **Idempotency keys** | Important | Prevent duplicate posts on network retry |

**Key difference:** Write amplification is the core challenge. A single post by a celebrity fan-outs to millions of feeds. The gateway must quickly accept the write and hand off async fan-out to background workers — it cannot block on fan-out. Rate limiting is especially strict on write endpoints.

---

### 3. Map / Navigation Service (Google Maps, Apple Maps, Uber)

**Characteristics:** Geospatial queries, real-time location updates, tile serving, route computation.

```
Client → API Gateway → Geocoding Service (address → coordinates)
                     → Routing/Directions Service
                     → Map Tile Service → CDN (tiles)
                     → Places/Search Service
                     → Real-time Location Service (for ride-sharing)
                     → ETA Service
```

**Gateway priorities:**

| Component | Emphasis | Reason |
|---|---|---|
| **API key management** | Critical | Billing is per-API-call; key validates quota and identity |
| **Rate limiting per key** | Very High | Tier-based throttling (free vs paid developers) |
| **Request routing by endpoint type** | High | Tile requests → CDN, geocoding → compute cluster, routing → graph service |
| **Caching** | Heavy for tiles | Map tiles are static and cacheable; routing responses less so |
| **Geo-based routing** | Important | Route to nearest regional cluster for low-latency geospatial queries |
| **WebSocket / gRPC streaming** | High | Real-time location tracking (driver position updates) needs persistent streams |
| **Payload validation** | Important | Validate coordinates are in valid range before hitting expensive routing engine |

**Key difference:** The billing/quota model is front and center. API keys are not just auth — they represent a pricing contract. The gateway must track per-key usage for metering. Tile serving is offloaded to CDN like video in streaming; the gateway handles the metadata/compute requests.

---

### 4. E-Commerce Service (Amazon, Shopify)

**Characteristics:** Mixed read/write, strong consistency for inventory/orders, payment flows.

```
Client → API Gateway → Product Catalog Service
                     → Inventory Service
                     → Cart Service
                     → Order Service
                     → Payment Service
                     → Recommendation Service
                     → Shipping/Fulfillment Service
```

**Gateway priorities:**

| Component | Emphasis | Reason |
|---|---|---|
| **Auth (session + guest)** | High | Both logged-in and anonymous users must be handled |
| **Idempotency** | Critical | Payment and order submissions must not duplicate on retry |
| **Rate limiting** | Moderate | Flash sales cause traffic spikes; protect inventory service |
| **Caching** | Selective | Catalog/product pages cacheable; cart and inventory are NOT |
| **Circuit breaking** | High | Payment gateway failures must not bring down entire checkout flow |
| **Request aggregation** | High | Product page = product details + inventory + reviews + recommendations |
| **PCI compliance routing** | Critical | Payment data must be routed only to PCI-compliant services; must not be logged |

**Key difference:** Data sensitivity and compliance requirements drive gateway design. Payment card data must be handled specially — not logged, not cached, routed to isolated services. Circuit breaking is critical because checkout failure = direct revenue loss.

---

### 5. Ride-Sharing / Real-Time Matching Service (Uber, Lyft)

**Characteristics:** Real-time bidirectional communication, geospatial matching, surge pricing.

```
Client → API Gateway → Location Service (continuous GPS updates)
                     → Matching Service (driver ↔ rider)
                     → Pricing/Surge Service
                     → Trip Service
                     → Payment Service
                     → Notification Service
```

**Gateway priorities:**

| Component | Emphasis | Reason |
|---|---|---|
| **WebSocket / long-polling** | Critical | Continuous location streams from drivers; real-time updates to riders |
| **Connection management** | High | Gateway must efficiently handle thousands of persistent connections per node |
| **Auth per connection** | High | Each WS connection must validate token |
| **Geo-aware routing** | High | Route to regional cluster closest to the ride event |
| **Low-latency path** | Critical | Matching latency directly affects user experience; no heavy processing in gateway |
| **Event streaming bridge** | High | Location updates may be forwarded to Kafka/message bus, not just HTTP services |

**Key difference:** The gateway here is less of a request-response router and more of a **connection manager and event router**. Long-lived connections with bidirectional messages require a different gateway architecture (often based on WebSocket or gRPC streaming) compared to stateless REST gateways.

---

## Summary Comparison Table

| Feature | Streaming | Social Media | Maps | E-Commerce | Ride-Sharing |
|---|---|---|---|---|---|
| Auth model | Subscription tier | OAuth / session | API key | Session + guest | JWT per connection |
| Caching | Heavy (metadata) | Feed edges only | Heavy (tiles) | Catalog only | Minimal |
| Rate limiting | Moderate | Very High | Per-key quota | Moderate | Low |
| Real-time / WS | Low | Medium | Medium | Low | Very High |
| Aggregation | High | High | Low | High | Low |
| Protocol focus | HTTP/2 + CDN | REST + WS | REST + CDN | REST | WS / gRPC |
| CDN offload | Video bytes | Media assets | Map tiles | Product images | None |
| Idempotency | Low | Medium | Low | Critical | High |
| Circuit breaking | Medium | Medium | Medium | Critical | High |

---

## Common API Gateway Products

| Product | Type | Best For |
|---|---|---|
| **AWS API Gateway** | Managed cloud | AWS-native microservices |
| **Kong** | Open source / enterprise | Self-hosted, plugin-rich |
| **NGINX** | Reverse proxy + gateway | High performance, custom logic |
| **Envoy** | Proxy / service mesh | Service-to-service (sidecar pattern) |
| **Traefik** | Cloud-native | Kubernetes, automatic discovery |
| **Apigee (Google)** | Enterprise managed | API monetization, analytics |
| **Azure API Management** | Managed cloud | Azure-native services |
