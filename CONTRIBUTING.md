# Contributing to bbrun

Bug reports, proposals and pull requests are welcome.

## How bbrun is put together

| Folder | What |
| --- | --- |
| `bbrun-core` | the language: the grammar (`src/main/antlr/BBRun.g4`), the parser, the interpreter, the resources a script calls, and `IntegrationEngine`, which runs scripts inside a Java service |
| `bbrun-cli` | the `bbrun` command: run, suites, load tests, the console and JSON reports |
| `examples` | a quickstart on a mock, a Java service whose operations are scripts with a test suite for it, and one short script for each feature |
| `dist` | the scripts that build the native executable, the Homebrew formula and the Maven Central bundle |
| `libs/repo` | iconn, regl and DRef as last vendored, for a build without them installed |

The resources of an environment (HTTP, database, queue, email, files) are opened by
[iconn](https://iconn.dev); validation and mapping are [DRef](https://dref.dev)'s. bbrun
does not reimplement either: a change to how a resource connects belongs in iconn, a change
to a rule or a transformation in DRef.

## A change to the language

A statement, a function or a behaviour of the language is one change in four places:

1. the grammar or the interpreter, with a test (`bbrun-core/src/test`);
2. `doc/scripting-language.md`;
3. the documentation at [bbrun.org](https://bbrun.org);
4. when it adds a kind of resource or a library, the native executable's configuration:
   run the examples under GraalVM's tracing agent, as `dist/native.sh` says, and check
   with `dist/native.sh --check`.

## Build and test

Java 21 or later. Gradle comes with the repository.

```bash
./gradlew test :bbrun-cli:installDist
B=bbrun-cli/build/install/bbrun/bin/bbrun
$B run examples/quickstart
$B run examples/reference/testing/_reference/tests
$B run examples/reference/integration
```

The order service example and its suite run together:

```bash
./gradlew :order-service-example:run --console=plain        # one terminal
$B run examples/testing/order-api/tests                      # another
```

`.github/workflows/ci.yml` runs the tests and the examples that need no service on every
push.

## Pull requests

- One subject per pull request, with the tests that show it.
- An example in the documents is a script that runs.

## Releasing

See [Releasing](README.md#releasing) in the README.

## Security

See [SECURITY.md](SECURITY.md).
