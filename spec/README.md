# BBRun Script Language Specification

**BBRun** is a domain-specific programming language for testing REST APIs and gRPC services.

## Design Goals

- **Readable**: Scripts read like executable documentation
- **Programmable**: Full control flow (if/else, loops) for complex scenarios
- **Journey-oriented**: One script can simulate an entire user journey
- **Assertive**: Built-in assertions for response validation
- **Performance-aware**: SLA checks with timing assertions

## File Extension

`.bbrun`

## Language Features

| Feature | Description |
|---------|-------------|
| Variables | Store and interpolate values |
| HTTP Requests | GET, POST, PUT, DELETE, PATCH |
| gRPC Calls | Unary and streaming RPC support |
| Assertions | `assert` (error) and `warn` (warning) |
| Timing/SLA | Response time checks with `response.time` |
| Control Flow | `if`/`else`, `repeat` loops |
| Macros | `run` scripts, `macro` blocks |
| Load Testing | `parallel` blocks, CLI flags |
| OAuth/Auth | Built-in OAuth flows, implicit bearer tokens |
| JSON Fixtures | `load` files, compare responses |

## Examples

See the `examples/` directory for progressive examples:

1. [Basic GET](examples/01-basic-get.bbrun)
2. [POST with Body](examples/02-post-with-body.bbrun)
3. [Headers and Auth](examples/03-headers-and-auth.bbrun)
4. [Variables](examples/04-variables.bbrun)
5. [Assertions](examples/05-assertions.bbrun)
6. [Conditionals](examples/06-conditionals.bbrun)
7. [User Journey](examples/07-user-journey.bbrun)
8. [gRPC](examples/08-grpc.bbrun)
9. [Timing & SLA](examples/09-timing-sla.bbrun)
10. [Macros & Composition](examples/10-macros.bbrun)
11. [Load Testing](examples/11-load-testing.bbrun)
12. [OAuth & Auth](examples/12-oauth.bbrun)
13. [JSON Fixtures](examples/13-json-fixtures.bbrun)
