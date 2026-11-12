# Two uses of BBRun, two different execution contexts

Both examples use the BBRun language and DRef validation. They show opposite
sides of an HTTP boundary:

```text
App-like test scripts                 Java Order Service
(bbrun-cli)                          (embedded IntegrationEngine)
       |                                      |
       +-- HTTP --> ONE public Order API -----+
                                              |
                              request + caller context -> operation script
                                              |
                              catalog / order storage / notifications
```

## I want to test a backend API

Start with **[testing/order-api](testing/order-api/README.md)**.

The tester configures one resource, `shopApi`. The tests simulate app behavior:
browse a product, submit an order, retrieve that same order, request a
confirmation, and check invalid-input and not-found responses. They assert on
the public wire contract and validate it with DRef. There are no mappings,
upstream services, or service implementation details in this suite.

The API under test can be implemented in any language or framework.

## I want to implement a backend operation

Start with **[integration/order-service](integration/order-service/README.md)**.

A runnable Java HTTP host receives a request, supplies caller/context data to
`IntegrationEngine`, and runs the operation's `.bbrun` script. The script
validates input, calls dependencies, transforms their data, applies business
rules, and builds the result. This is the service implementation, not a test.

The example includes local HTTP stand-ins for the dependencies so you can run
it without external infrastructure. Its public API is the same API exercised
by the tester example above; the two examples can therefore be run together.

## Try both

From the repository root, in terminal 1:

```bash
./gradlew :order-service-example:run --console=plain
```

In terminal 2:

```bash
./gradlew :bbrun-cli:installDist
./bbrun-cli/build/install/bbrun/bin/bbrun \
  examples/testing/order-api/tests/01-app-checkout.bbrun \
  -c examples/testing/order-api/bbrun.yaml
```

The backend listens on `127.0.0.1:8080`. Stop it with Ctrl+C.
To verify both examples automatically, including error handling, caller
isolation and notification side effects:

```bash
./gradlew :order-service-example:test
```

Earlier language/handler demonstrations are preserved under
[`reference/`](reference/README.md); these are not the starting point for either use case.
