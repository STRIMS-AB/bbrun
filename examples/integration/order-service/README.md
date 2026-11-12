# Implement the Order Service in Java + BBRun

**Audience:** a backend developer implementing HTTP operations. The scripts in
`operations/` are the production-shaped integration logic, not test scenarios.
They accept invocation data instead of inventing a test payload inside a flow.

## Execution boundary

```text
HTTP request
    -> OrderService.java: route + caller/context extraction
    -> IntegrationEngine.execute(operation, request, context)
    -> .bbrun: validate -> call upstreams -> transform/combine -> return
    -> OrderService.java: serialize the returned value as HTTP
```

The host and engine reuse the same compiled Java application across requests;
each execution gets its own script variables and resource context.

| Binding | Example | Owned by |
| --- | --- | --- |
| `request.method` | `POST` | HTTP adapter |
| `request.path.id` | Order/product identifier captured by routing | HTTP adapter |
| `request.body` | `{ "productId": "P100", "quantity": 2 }` | Incoming client |
| `context.customerId` | Authenticated caller's customer ID | Server |
| `context.correlationId` | Server-generated request ID | Server |
| `response` | Last upstream call's status/headers/body | Interpreter |

In particular, `response` is **not** the incoming request or the service's final
HTTP response. Save its body in a variable before another upstream call.

The demo takes caller identity from `X-Customer-Id` so it can run locally. A real
host obtains it from its authenticated principal. A `customerId` or `total`
supplied in the client body cannot override server identity or catalog pricing.

## Operations implemented by scripts

| Public operation | Implementation |
| --- | --- |
| `GET /products/{id}` | Call catalog; map provider `sku/label/price_cents` into `Product`; validate; return public product |
| `POST /orders` | Validate request; fetch product; calculate total; combine caller identity, request quantity and catalog data; validate and persist order; return 201 |
| `GET /orders/{id}` | Retrieve order; validate it; enforce caller ownership; return order or 404 |
| `POST /orders/{id}/confirmation` | Check order ownership; send notification with correlation context; complete without a value |

`domain.json` describes service inputs/internal models. The catalog provider's
shape is translated by the "Catalog product" entry in `Product`'s `mapsFrom` —
DRef declares a mapping on the model it imports onto, so the model is the target —
and `bbrun.yaml` binds `catalogApi` to it by name. The tester project's DRef
document independently describes the public contract it expects to receive; it
needs no mappings, because there is no upstream provider behind it.

The script returns the **domain value directly** — `return order`, `return product`
— never a status envelope. `ExecutionResult.value()` contains JSON-compatible
maps/lists/scalars; it is not automatically an instance of a generated Java DTO.

Status and headers are separate signals: a script calls `status(404)` or
`header("Location", ...)` when a business decision requires a non-default status
or an extra header, and the host reads `ExecutionResult.statusCode()` /
`ExecutionResult.responseHeaders()`. Without such calls `OrderService.java`
applies its default mapping: returned value → 200, `null` → 204, script failure
→ 500.

No `return` on successful notification dispatch produces a null value; this
host translates that into 204. This is a no-payload side-effect operation:
execution is synchronous, not a background-job guarantee. Upstream HTTP failures
are handled in the scripts with explicit `status(502)`/`status(404)` signals;
unexpected failures (including invalid provider data rejected by DRef) become a
host-level 500.

## Embedding call

```java
IntegrationEngine engine = IntegrationEngine
        .fromConfig(Path.of("examples/integration/order-service/bbrun.yaml"))
        .resource(IntegrationEngine.httpRequest("catalogApi", catalogBaseUrl))
        .resource(IntegrationEngine.httpRequest("ordersApi", ordersBaseUrl))
        .resource(IntegrationEngine.httpRequest("notificationsApi", notificationsBaseUrl))
        .build();

ExecutionResult result = engine.execute(
        Path.of("examples/integration/order-service/operations/place-order.bbrun"),
        Map.of("method", "POST", "path", Map.of(),
               "body", Map.of("productId", "P100", "quantity", 2)),
        Map.of("customerId", authenticatedCustomerId, "correlationId", correlationId));
// Check result.isSuccess(), then serialize result.value() using your framework.
```

`OrderService.java` contains the complete HTTP adapter. It imports the YAML's
domain/mapping paths and overrides resource URLs programmatically, showing both
configuration styles without changing scripts.

## Run the local service

From the repository root:

```bash
./gradlew :order-service-example:run --console=plain
```

This exposes the Order API on port 8080 and starts `DemoUpstreams.java` on a
separate ephemeral loopback port. Those upstream stand-ins provide catalog
data, in-memory order storage, and a notification inbox. They are demo fixtures;
the public operation logic remains in the BBRun scripts. No external credentials
or infrastructure are needed. For another public port, pass `--args='8081'`.

Call it directly:

```bash
curl -i http://127.0.0.1:8080/orders \
  -H 'X-Customer-Id: customer-alice' -H 'Content-Type: application/json' \
  -d '{"productId":"P100","quantity":2}'
```

Or run the independent [app-like coverage](../../testing/order-api/README.md)
through the CLI.

## Verify the implementation

```bash
./gradlew :order-service-example:test
```

Tests start the service on random ports and run the tester scripts over real
HTTP. Backend-specific tests additionally check identity isolation, concurrent
requests, notification side effects, catalog unavailability, and DRef rejection
of malformed provider data before any order is persisted.
