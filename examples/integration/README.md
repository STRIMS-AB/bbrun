# Implementing backend operations with the embedded engine

Start with **[order-service](order-service/README.md)**, a runnable Java service
whose endpoint logic is implemented in `.bbrun` scripts.

The Java host supplies an incoming `request` and trusted `context` for each
invocation. The scripts validate inputs with DRef, call dependencies, transform
and combine their data, then return a domain value directly or complete a side
effect. A script overrides the host's default HTTP mapping only when business
logic demands it, via `status(404)`/`header(...)`.

The separate [tester project](../testing/order-api/README.md) calls the public
API from the outside. It is not part of the service implementation.
