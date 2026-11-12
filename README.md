<p>
  <a href="https://bbrun.org"><img src="doc/bbrun-logo.svg" alt="bbrun" width="210"></a>
</p>

**Scripts that call services as black boxes. The same script runs everywhere.**

![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)
![Java](https://img.shields.io/badge/java-21%2B-orange.svg)

bbrun is a small scripting language for calling services. A script says *what* it calls
(`shopApi`) and never *where* that is: each environment says so, in one configuration file.
So one script runs on mocks on your laptop and on the real services in staging, and it is
either of two things:

- **a test scenario**: the `bbrun` command uses your API the way an app does and checks
  what it answers;
- **a business function**: inside a Java service, a script receives a request, calls other
  services, and returns a result.

**Documentation: [bbrun.org](https://bbrun.org)**

## A script

```bbrun
goal "A customer buys two travel mugs and finds the order afterwards"

step "Look at the product" {
    shopApi.get("/products/P100")
    assert response.status == 200
    let product = response.body
}

step "Place the order" {
    shopApi.post("/orders", { "productId": product.id, "quantity": 2 })
    assert response.status == 201
    let order = response.body
    assert order.total == product.unitPrice * 2
}

step "Find it again" {
    shopApi.get("/orders/${order.id}")
    assert response.body.status == "confirmed"
}
```

`bbrun.yaml` says what `shopApi` is, once for each environment:

```yaml
defaultEnvironment: local

environments:
  local:                      # nothing to start: the shop is mocked
    resources:
      shopApi:
        type: mock

  test:                       # the real service
    resources:
      shopApi:
        baseUrl: "${SHOP_URL:-http://127.0.0.1:8080}"
        auth:
          type: bearer
          token: "${SHOP_TOKEN}"
```

```bash
bbrun checkout.bbrun                 # on the mock
bbrun checkout.bbrun --env test      # the same script, on the real service
```

```text
  goal  A customer buys two travel mugs and finds the order afterwards
  step  Look at the product
  step  Place the order
  step  Find it again

✔ SUCCESS (128ms)
  Assertions: 4 passed, 0 failed
  Requests:   3
```

## bb is for black box

bbrun began as a tool for black box testing: checking a service only through what it shows
on the outside. The idea stayed and grew. In a script, everything the script calls is a
black box, something you use without needing to know what is inside: an HTTP API, a
database, a queue, a mail server, or a mock of any of them. It is a name and its
operations. What stands behind the name changes with the environment; the script does not.

## Two uses

### A test scenario

`bbrun` is a client of your services. A scenario has a goal, named steps and clean-up; a
folder of scenarios is a suite; any script is also a load test.

```bash
bbrun run scenarios/                              # a suite, with a PASS/FAIL summary
bbrun checkout.bbrun --env staging --verbose      # one script, showing each call
bbrun load checkout.bbrun --users 20 --duration 30s
```

→ [Scenarios](https://bbrun.org/docs/scenarios.html) and [The command line](https://bbrun.org/docs/cli.html)

### A business function

`IntegrationEngine` runs a script inside a Java service. The host passes the request and
what it knows of the caller; the script orchestrates the calls and returns the result.

```bbrun
// operations/get-order.bbrun
ordersApi.get("/records/${request.path.id}")
if response.status == 404 {
    status(404)
    return { "code": "ORDER_NOT_FOUND" }
}
Order order = response.body
if order.customerId != context.customerId {
    status(404)
    return { "code": "ORDER_NOT_FOUND" }
}
return order
```

```java
IntegrationEngine engine = IntegrationEngine.fromConfig(Path.of("bbrun.yaml")).build();

ExecutionResult result = engine.execute(
        Path.of("operations/get-order.bbrun"),
        Map.of("path", Map.of("id", orderId)),               // request
        Map.of("customerId", signedInCustomer));             // context

Object order = result.value();          // maps, lists and plain values, ready to write as JSON
```

→ [Running scripts inside a Java service](doc/embedding.md)

## What a script can call

| Resource | In `bbrun.yaml` | Operations |
| --- | --- | --- |
| HTTP API | `baseUrl: "https://…"`, with bearer, basic, API key or OAuth2 credentials | `get` `post` `put` `patch` `delete` |
| Mock | `type: mock`, routes in `mocks/<name>.yaml` | those of what it stands for |
| Database | `type: sql` | `query` `execute` |
| Queue | `type: queue` | `send` `receive` `peek` |
| Email | `type: email` | `send` `outbox` |
| Files | `type: file` | `get` `put` `list` `delete` |
| A service of the host | Java: `IntegrationEngine.create().service("kfig", handler)` | whatever the host answers |

→ [Environments and resources](https://bbrun.org/docs/configuration.html)

## Built on iconn and DRef

- **[iconn](https://iconn.dev)** opens the resources. bbrun hands the resources of the
  environment to iconn, which connects to the real service or stands in for it (an embedded
  database, a queue in memory, a mailbox, a folder). → [bbrun and iconn](https://bbrun.org/docs/iconn.html)
- **[DRef](https://dref.dev)** says what the data must look like. `validate order as Order`
  checks a value against your model, and a mapping declared on a model turns a provider's
  answer into your own shape: `Product product = response.body`.
  → [Models and mappings](https://bbrun.org/docs/models.html)

## Build and try

bbrun needs **Java 21 or later**.

```bash
git clone https://github.com/STRIMS-AB/bbrun.git
cd bbrun
./gradlew :bbrun-cli:installDist
./bbrun-cli/build/install/bbrun/bin/bbrun --version
```

bbrun is built on iconn, regl and DRef, which are not on Maven Central yet. The build
takes them from your local Maven repository when they are installed there, and otherwise
from `libs/repo`, a copy kept in this repository: a fresh clone builds as it is.

### One executable

`dist/native.sh` builds `build/native/bbrun` with GraalVM's native-image: one file for the
machine it is built on, which starts in a few milliseconds and needs no Java to run.

```bash
sdk install java 25.5.5.0+3-graalce      # any GraalVM with native-image; sdkman is one way
dist/native.sh --check                   # builds, then runs the examples with the executable
```

What the executable needs to know about reflection and resources is in
`bbrun-cli/src/main/resources/META-INF/native-image/`. It was recorded by running the
examples under GraalVM's tracing agent; a new kind of resource or library is recorded the
same way.

### The examples

The repository holds one small shop seen from both sides. Start the sample service, whose
operations are bbrun scripts:

```bash
./gradlew :order-service-example:run --console=plain     # listens on 127.0.0.1:8080
```

In another terminal, run the test suite that uses it as an app would:

```bash
./bbrun-cli/build/install/bbrun/bin/bbrun run examples/testing/order-api/tests
```

```text
Summary
  PASS examples/testing/order-api/tests/01-app-checkout.bbrun  92ms
  PASS examples/testing/order-api/tests/02-invalid-input.bbrun  23ms
  PASS examples/testing/order-api/tests/03-not-found.bbrun  20ms

✔ 3 passed, 0 failed, 3 scripts
```

A script that needs no service at all is in [`examples/quickstart`](examples/quickstart):

```bash
cd examples/quickstart
../../bbrun-cli/build/install/bbrun/bin/bbrun checkout.bbrun
```

→ [Get started](https://bbrun.org/docs/) walks through it.

## The command

| Command | Does |
| --- | --- |
| `bbrun <script>` | runs a script in the default environment; `--env <name>` chooses another |
| `bbrun run <files or folders>` | runs a suite, prints a summary, exits 1 when a script failed |
| `bbrun load <script> --users <n> --iterations <n>` | runs the script as a load test (`--duration`, `--ramp-up`) |
| `bbrun validate <script>` | reads a script without running it |
| `bbrun envs` | lists the environments and their resources |
| `bbrun models` | lists the models of the DRef domain document |

Options of a run: `-c <config>`, `-v` (show each call), `-q`, `--json`, `--log-dir <folder>`,
`--no-color`.

## Use it from Java

The engine is the module `bbrun-core`: `IntegrationEngine` runs a script with a request
and a caller's context and gives back its result, lets the host offer its own services to
scripts, and sends the log where the host wants it.

```xml
<dependency>
    <groupId>org.bbrun</groupId>
    <artifactId>bbrun-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

→ [Running scripts inside a Java service](doc/embedding.md)

## In this repository

| Path | What |
| --- | --- |
| `bbrun-core` | the language (an ANTLR grammar, `src/main/antlr/BBRun.g4`), the interpreter, the resources, `IntegrationEngine` |
| `bbrun-cli` | the `bbrun` command |
| `dist/native.sh` | builds the native executable |
| `dist/vendor.sh`, `libs/repo` | iconn, regl and DRef as last vendored, for a build without them installed |
| `dist/formula.sh` | writes the Homebrew formula of a release |
| `dist/central.sh` | builds the Maven Central bundle of `bbrun-core`, and uploads it |
| `.github/workflows` | `ci.yml` tests every push; a `v*` tag runs `release.yml` (the executables) and `publish.yml` (Maven Central) |
| `examples/quickstart` | one script on a mock: runs with the command alone |
| `examples/integration/order-service` | a Java service whose operations are scripts |
| `examples/testing/order-api` | a test suite for that service |
| `examples/reference` | short scripts, one for each feature |
| `doc/embedding.md` | running scripts inside a Java service |
| `doc/scripting-language.md` | the language reference of this version of the source |

## Releasing

A release is the executables (macOS and Linux, arm64 and x64, with a Homebrew formula)
and `org.bbrun:bbrun-core` on Maven Central. One tag starts both.

1. **Once**: the namespace `org.bbrun` is verified in the
   [Central Portal](https://central.sonatype.com/) with a TXT record on `bbrun.org`, and
   the repository has the secrets `CENTRAL_TOKEN_USER`, `CENTRAL_TOKEN_PASSWORD` (a user
   token of the Portal), `MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE` (the signing
   key).
2. iconn, regl and DRef are on Maven Central in the versions of `gradle.properties`. If
   one of them changed, install it (`mvn install`) and run `dist/vendor.sh`: it copies them
   into `libs/repo`, which is what GitHub builds the executables with.
3. Set the version (`version` in `build.gradle`, and the one the command reports in
   `bbrun-cli/.../Main.java`), commit, and push a tag of the same version:
   `git tag v1.0.0 && git push origin v1.0.0`.
4. `.github/workflows/release.yml` builds the executable on each platform, runs the
   examples with it (`dist/native.sh --check`), and publishes the GitHub release with
   `bbrun-<version>-<platform>.tar.gz`, `checksums.txt` and `bbrun.rb`. It stops if the
   executable does not report the version of the tag.
5. `.github/workflows/publish.yml` signs `bbrun-core` with its sources and javadoc and
   uploads them to Maven Central (`dist/central.sh --upload`).
6. Copy the release's `bbrun.rb` into the tap, `STRIMS-AB/homebrew-tap`, and push it.
   `brew install STRIMS-AB/tap/bbrun` then installs that version.

See [CONTRIBUTING.md](CONTRIBUTING.md) to work on bbrun and [SECURITY.md](SECURITY.md) to
report a vulnerability.

## Documentation

The language and the command are documented at **[bbrun.org](https://bbrun.org)**; what
concerns the source and Java is here.

| | |
| --- | --- |
| [Get started](https://bbrun.org/docs/) | install, a first script, the same script on a real service |
| [The language](https://bbrun.org/docs/language.html) | values, expressions, statements, built-in functions, grammar |
| [Environments and resources](https://bbrun.org/docs/configuration.html) | `bbrun.yaml`, credentials, mocks, local resources |
| [Scenarios](https://bbrun.org/docs/scenarios.html) | goals, steps, clean-up, shared scripts |
| [The command line](https://bbrun.org/docs/cli.html) | suites, options, the log, load tests |
| [Models and mappings](https://bbrun.org/docs/models.html) | DRef validation, mapping a provider's answer |
| [bbrun and iconn](https://bbrun.org/docs/iconn.html) | what opens the resources |
| [Running scripts inside a Java service](doc/embedding.md) | `IntegrationEngine`, host services, following a run |

## License

Apache-2.0 © 2026 [STRIMS AB](https://strims.se). See [LICENSE](LICENSE) and [NOTICE](NOTICE).
