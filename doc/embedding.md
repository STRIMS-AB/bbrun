# Running bbrun scripts inside a Java service

The `bbrun` command runs scripts from a terminal. The same engine runs them inside a
Java service, where a script is the body of an operation: the service receives a request,
hands it to a script, and sends back what the script returns. The script holds the
orchestration: which services to call, in which order, and what to make of their answers.

The language itself is documented at [bbrun.org](https://bbrun.org/docs/language.html).
This page is the Java side.

## The dependency

```xml
<dependency>
    <groupId>org.bbrun</groupId>
    <artifactId>bbrun-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

With Gradle: `implementation 'org.bbrun:bbrun-core:1.0.0'`. To use a build of your own,
`./gradlew :bbrun-core:publishToMavenLocal` puts it in your local Maven repository.

## A script is a function

The host gives the script two values, `request` and `context`. The script ends with
`return`, and the value it returns is the result.

```bbrun
// operations/place-order.bbrun: POST /orders
if not (request.body matches PlaceOrderRequest) {
    status(400)
    return { "code": "INVALID_ORDER" }
}

catalogApi.get("/products/${request.body.productId}")
if response.status == 404 {
    status(404)
    return { "code": "PRODUCT_NOT_FOUND" }
}
Product product = response.body

// the price comes from the catalogue and the customer from the server, never from the client
let order = {
    "id": uuid(),
    "customerId": context.customerId,
    "productId": product.id,
    "quantity": request.body.quantity,
    "total": product.unitPrice * request.body.quantity,
    "currency": product.currency,
    "status": "confirmed"
}
validate order as Order
ordersApi.post("/records", order)
if response.status != 201 {
    status(502)
    return { "code": "ORDER_STORAGE_UNAVAILABLE" }
}

status(201)
header("Location", "/orders/" + order.id)
return order
```

| Name | Holds | Comes from |
| --- | --- | --- |
| `request` | what the caller sent: by convention `request.body`, `request.path.id`, `request.method` | the host, from the incoming request |
| `context` | what the server knows: the signed-in caller, a correlation id | the host, never the client |
| `response` | the answer of the last call the script made | bbrun |

The shape of `request` and `context` is the host's choice: they are the two maps it
passes. Keeping them apart is the point. What a client can write is in `request`; who the
client is, is in `context`.

What the script gives back:

- `return value` ends the script. The value is the result itself, an object, a list or a
  plain value, with nothing wrapped around it.
- A script that ends without `return` has no result. It still ran to its end before the
  host goes on: nothing happens in the background.
- `status(code)` and `header(name, value)` tell the host which status and headers to answer
  with, when the logic decides them. They do not change the result.
- A script that fails, by a false `assert`, a failed `validate`, an `abort` or an error,
  has no result but a failure.

## IntegrationEngine

`IntegrationEngine` is built once, when the service starts, and used for every request:

```java
IntegrationEngine engine = IntegrationEngine
        .fromConfig(Path.of("bbrun.yaml"))                        // resources, models, mappings
        .resource(IntegrationEngine.httpRequest("catalogApi", catalogUrl)
                .authBearer(catalogToken))                        // or declared in Java
        .build();
```

Each request is one call to `execute`:

```java
ExecutionResult result = engine.execute(
        Path.of("operations/place-order.bbrun"),
        Map.of("method", "POST", "path", pathParameters, "body", incomingBody),    // request
        Map.of("customerId", signedInCustomer, "correlationId", correlationId));   // context

if (!result.isSuccess()) {
    log.error(result.errorMessage());                 // for you: step, line, value, last response
    send(500, Map.of("message", "The order could not be placed"));
} else if (result.statusCode() != null) {
    send(result.statusCode(), result.responseHeaders(), result.value());
} else if (result.value() == null) {
    send(204);
} else {
    send(200, result.value());
}
```

| `ExecutionResult` | Holds |
| --- | --- |
| `isSuccess()` | whether the script ran to its end |
| `value()` | what it returned: maps, lists, strings, numbers and booleans, ready to write as JSON; `null` without a `return` |
| `statusCode()`, `responseHeaders()` | what `status` and `header` set; `null` when the script set none |
| `errorMessage()` | after a failure, everything about it: for whoever runs the service |
| `reason()` | after a failure, only the words the author wrote: `assert body.weightKg > 0 : "weightKg must be positive"` gives `weightKg must be positive`, which a host may show to its own callers |

How a result becomes an HTTP answer is the host's rule. The sample service uses the one
above: a value is 200, no value is 204, a failure is 500, and a script overrides it with
`status` when the logic calls for it.

The engine can be shared by all the threads of a service. Each call to `execute` has its
own variables and its own connections to the resources; nothing of one request is seen by
another.

## Resources declared in Java

Resources come from `bbrun.yaml`, from Java, or from both. One declared in Java replaces
the one of the same name in the file and keeps its mapping, which is how a service takes
its addresses from its own configuration:

```java
IntegrationEngine.create()
        .domain(Path.of("domain.json"))
        .resource(IntegrationEngine.httpRequest("paymentsApi", "https://api.example.com")
                .authOAuth2(tokenUrl, clientId, clientSecret, "payments.write"))
        .resource(IntegrationEngine.mockResource("ratesApi")
                .route(Set.of("get"), "/rates/{currency}", 200, Map.of("rate", 1.08)))
        .build();
```

An HTTP resource takes `header`, `authBearer`, `authBasic`, `authApiKey` or `authOAuth2`.
The engine runs in the default environment of the file it was given.

## Services of the host

A host can give scripts a service of its own, written in Java. In the script it is called
like any resource; it is one more black box.

```java
IntegrationEngine engine = IntegrationEngine.create()
        .service("kfig", (operation, args) -> {
            String key = (String) args.get(0);
            if (operation.equals("get")) {
                return settings.get(key);
            }
            if (operation.equals("set")) {
                settings.put(key, args.get(1));
                return Map.of("key", key, "value", args.get(1));
            }
            throw new ServiceHandler.ServiceException(404, "kfig cannot " + operation);
        })
        .build();
```

```bbrun
kfig.set("payments.limit", 500)
assert response.status == 200
let limit = kfig.get("payments.limit")
assert limit == 500
```

- The handler receives the name of the operation and its arguments.
- What it returns is `response.body`, with status 200, or 204 when it returns nothing.
- A `ServiceException(status, message)` answers that status with `response.body.message`.
  Any other exception answers 500. The script decides what to do with it, as with any
  answer.

## Following a run

A fourth argument of `execute` says where the lines of the log go while the script runs:

```java
LogCollector log = new LogCollector();
ExecutionResult result = engine.execute(script, request, context,
        ScriptOutput.both(log, new FileLog(Path.of("logs/run-42.log"))));
```

| Output | Where the lines go |
| --- | --- |
| a lambda `(level, message) -> …` | to your code, as they happen |
| `FileLog` | to a file, each line written at once |
| `LogCollector` | kept in memory: `entries()` gives them all, `after(seq, limit)` those after the last one you have, and `await(seq, timeout)` waits for the next, so a console can show a run live. It keeps the latest 10,000 lines. |
| `ScriptOutput.both(…)` | to several outputs |
| `ScriptOutput.NONE` | nowhere |

The levels are those of the command's log: `GOAL`, `STEP`, `INFO`, `WARN`, `CALL`, then
`DONE` or `ERROR`.

To stop a run, interrupt the thread that called `execute`. The script stops before its
next statement, its result is a failure that says it was cancelled, and the log ends with
that line.

## A whole service to read

[`examples/integration/order-service`](../examples/integration/order-service) is a small
HTTP service in plain Java. Its four operations are four scripts: read a product from a
catalogue that speaks another shape, place an order, read an order only if it is the
caller's, and send a confirmation. `OrderService.java` is the host: it finds the script
for a route, builds `request` and `context`, and writes the result as HTTP.

```console
$ ./gradlew :order-service-example:run --console=plain

$ curl -i -X POST http://127.0.0.1:8080/orders -H 'X-Customer-Id: customer-alice' -H 'Content-Type: application/json' -d '{"productId":"P100","quantity":2}'
HTTP/1.1 201 Created
Location: /orders/5e0cd33d-8e79-4695-9c30-628546bb2c75
Content-type: application/json

{"id":"5e0cd33d-8e79-4695-9c30-628546bb2c75","customerId":"customer-alice","productId":"P100","productName":"Travel mug","quantity":2.0,"total":25.0,"currency":"EUR","status":"confirmed"}
```

The test suite in [`examples/testing/order-api`](../examples/testing/order-api) calls the
same service from the outside, with the `bbrun` command.
