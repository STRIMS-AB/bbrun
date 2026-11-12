# BBRun Scripting Language

BBRun is a small, domain-specific scripting language for integration testing and
automation against resource abstractions with optional DRef model validation.
A script is a sequence of statements. Semicolons are optional; newlines are not
significant.

```bbrun
// comments use // or /* ... */

let id = "cus_" + randomString(6)                // variable declaration
print "creating customer " + id
```

## Running a script

```bash
bbrun flow.bbrun --env local -c bbrun.yaml        # run a script
bbrun run scenarios/ -c scenarios/bbrun.yaml      # run every .bbrun file of a folder, as a suite
bbrun run a.bbrun b.bbrun other/                  # several files and folders
bbrun flow.bbrun --env local --verbose            # more output
bbrun flow.bbrun --env local --json               # machine-readable result
bbrun run scenarios/ --log-dir logs               # also one log file per script, in logs/
bbrun models -c bbrun.yaml                        # list DRef models
bbrun envs -c bbrun.yaml                          # list environments/resources
```

Every script reports a `SUCCESS`/`FAILURE` summary with assertion counts, the
number of requests performed and its steps. `print` output goes to the console.

Given folders, or more than one file, `bbrun run` runs a suite: each script in
name order (in a folder, files and folders whose name starts with `_` are left
out: keep included files there; so is a folder named `mocks`, whose scripts answer
a mock's routes), each in a fresh context, then a summary with one
`PASS`/`FAIL` line per script (its steps passed, its first failure) and the totals.
The exit status is 0 only when every script passed. Without `-c`, the config is
`bbrun.yaml` (or `.yml`, `.json`) in the current folder, else next to the first
script or in a folder above it.

### The log

Every run writes a log, line by line as it happens:

| Level | Line |
| --- | --- |
| `GOAL` | a `goal` |
| `STEP` | a `step` starts (its name) |
| `INFO` | a `print` |
| `WARN` | a failed `warn`; a cleanup that failed after the script had failed |
| `CALL` | a resource or host service call and its answer: `shopApi.post /orders → 201 (12 ms)` |
| `DONE` | last, when the script passed: `passed in 120 ms · 12 assertions · 5 requests` |
| `ERROR` | last, when it failed: the failure with its diagnostics (step, line, value found, last response) |

The console shows the goals, steps, prints and warnings (`CALL` lines with
`--verbose`). To keep the whole log, name a folder in the config:

```yaml
logging:
  dir: logs        # relative to the config file
```

or pass `--log-dir <folder>` (which wins over the config). The CLI then writes one
file per script there, `2026-11-06_11-30-02_checkout.log`, each line
`2026-11-06 11:30:02.114 CALL  shopApi.post /orders → 201 (12 ms)`, flushed as
it is written (`tail -f` follows a run), and prints its path after the result.

A host that embeds bbrun chooses where the log goes: it passes a `ScriptOutput`
to `execute` — a lambda, `FileLog` (a file), `LogCollector` (kept in memory) or
`ScriptOutput.both(...)` to send to several. A `LogCollector` writes no file: the
host reads `entries()` for its report when the script ends, and while it runs,
`after(seq, limit)` for the lines after the last one it has shown (each line has
its `seq`, from 1, and its time), so a dashboard console can show the latest
lines live; `await(seq, timeout)` waits for the next one. It keeps the 10,000
latest lines (`dropped()` counts the older ones).

## Values

| Value     | Examples                         |
| --------- | -------------------------------- |
| Number    | `42`, `-1`, `3.14` (integers and doubles) |
| String    | `"double quoted"`, `'single quoted'`; escapes `\n`, `\t`, `\r`, `\"`, `\'`, `\\` |
| Boolean   | `true`, `false`                  |
| Null      | `null`                           |
| Object    | `{ "id": "pi_1", "amount": 100 }` |
| Array     | `["a", "b", "c"]`, `[1, 2, 3]`    |
| Response  | result of any resource call, see [Response](#response) |

Numbers are stored as Java `long` when whole and `double` otherwise; arithmetic
follows Java semantics (`5 / 2` is `2.5`, `5 == 5.0` is `true`).

Objects and arrays are JSON-ish: object keys are quoted strings (`"key"`), values
are expressions. Trailing commas are allowed.

## Variables

Declared with `let`; names are `[a-zA-Z_][a-zA-Z0-9_]*`. There is **no assignment
operator** outside the initial `=`; a `let` of a name that exists declares it anew,
which is how a loop keeps what it found:

```bbrun
let found = null
for user in users {
    if user.email == "ana@example.com" {
        let found = user
        break
    }
}
```

```bbrun
let name = "Sarah"
let count = 3
let meta = { "tags": ["a", "b"], "nested": { "x": 1 } }
```

There is no scope: variables are visible from the point of declaration onward.

## Operators & expressions

Precedence, highest first:

| Precedence | Operands / operators                        |
| ---------- | ------------------------------------------- |
| primary    | literals, identifiers, `(expr)`, object/array literals |
| call       | `resource.get("path")`, `fn(...)`, `obj.method(...)` |
| member     | `obj.member`, `obj[index]`                  |
| `*` `/`    | multiplication, division                    |
| `+` `-`    | addition / string or list concatenation, subtraction |
| relational | `== != < > <= >=`                          |
| `matches`  | `expr matches ModelName`                    |
| contains   | `expr contains item`, `expr not contains item` |
| `not`      | unary `not expr`                            |
| `and` `or` | boolean operators                           |

#### Joining lists

`list + list` is a new list with both, in order: `let seen = seen + [id]` keeps a list
growing in a loop. `+` adds numbers and joins strings (a string on either side) or
lists; anything else (`[1] + 2`, `null + 1`) fails the script with what it was given.

#### String concatenation

```bbrun
print "customer " + id + " has " + orderCount + " orders"
```

Numbers compare numerically; `==`/`!=` compare values (`1 == 1.0` is `true`), lists
element by element and objects key by key, so a JSON answer compares with a literal:
`assert response.body.ids == [1, 2]`. `contains` finds an element in a list (by the same
equality), a key in an object, or a part of a string.
`<`/`>`/`<=`/`>=` also work on strings (lexicographic).

Truthiness: `false`, `null`, `0`, empty string, and empty collections are falsy;
everything else is truthy. `not`, `and`, `or` and condition contexts use it.

### Member access & indexing

```bbrun
print employee.name                // object key
print employees.length             // array/list size
print employees[0]                 // element at index
print "hello".length               // 5, the same as "hello".length()
print "HELLO".toLowerCase()        // "hello"
print "  x  ".trim()               // "x"
```

- `obj.member` reads a key on an object, `length`/`size` on a list, or `length`
  on a string.
- `obj[index]` indexes a list by integer or an object by key.
- String methods: `.toLowerCase()`, `.toUpperCase()`, `.trim()`, `.length()`,
  `.startsWith(s)`, `.endsWith(s)`, `.contains(s)`, `.indexOf(s)`, `.replace(a, b)`,
  `.split(separator)`, `.substring(from [, to])`. Another method is an error.

### Built-in functions

| Function | Description |
| -------- | ----------- |
| `uuid()` | random UUID string |
| `now()` | current epoch millis |
| `random()` | random `double` in `[0,1)` |
| `random(max)` | random integer in `[0, max)` |
| `randomString(len)` | random alphanumeric string of `len` chars (default 8) |
| `env(name)` / `env(name, default)` | environment variable or default |
| `sleep(ms)` | waits; with `for` and `break`, polls until something happened |
| `authenticate(resource, auth)` | sends credentials with every later call to `resource` (see below) |
| `headers({...})` | headers for one call, passed as an argument of the call |
| `form({...})` | a URL-encoded form body (`application/x-www-form-urlencoded`) |
| `file(path [, contentType])` | a text file as the body, its content type from its extension |
| `readText(path)`, `readJson(path)`, `readCsv(path)` | a file's text, its JSON, or its CSV rows (lists of string cells; quoted cells may hold commas, quotes and line breaks) |
| `parseJson(text)`, `toJson(value)` | JSON text to a value, and back |
| `keys(object)` | an object's keys |
| `pluck(list, field)` | the `field` of each object of a list |
| `find(list, field, value)` | the first object of a list whose `field` equals `value`, or `null` |
| `urlParam(url, name)` | a query parameter of a URL, decoded (`urlParam(response.headers.location, "code")`) |
| `urlEncode(text)` | `text` encoded for a URL path or query |
| `base64Encode(text)`, `base64Decode(text)` | Base64 (either alphabet, padding optional, when decoding) |
| `jwtClaims(token)` | the claims of a JWT, not verified |

Paths given to `file`, `readText`, `readJson`, `readCsv` and `include` are relative
to the folder of the script that names them. An unknown function is an error.
| `respond(status, body)` / `respond(status, body, headers)` | **mock handler scripts only**: sets the mock response (see below); `return <expr>` is the simpler status-200 form |

## Statements

### Resource dispatch

Dot form targets an explicit resource by name — every resource call requires a
resource:

```bbrun
customersApi.post("/customers", customer)
paymentsApi.get("/payments/${payment.id}")
assert response.status == 201
```

On **HTTP/mock** resources the operation is a REST verb: `get`, `post`, `put`,
`patch`, `delete`. Operation names are **not reserved words** — a method call on
a configured resource dispatches to that resource's handler, so each resource
type can define its own vocabulary (see "Non-HTTP resources" below).

Bare verb requests target a resource explicitly (an optional `to` clause carries
the body for `post`/`put`/`patch`):

```bbrun
post bulk to customersApi."/customers/bulk"
get customersApi."/customers/${id}"
delete ordersApi."/orders/o_y"
```

Paths are real wire paths. Quote them and use `${expr}` for interpolation:

```bbrun
paymentsApi.get("/services/partners/${partnerId}/balances/${currency}")
```

#### Non-HTTP resources

`file`, `sql`, `queue` and `email` resources exist only in-process. All
arguments are evaluated (string arguments get `${expr}` interpolation); all
operations return a `response` whose body you can inspect or assert on.

```bbrun
// file — root directory comes from the config `dir`
let doc = fileStore.get("reports/daily.json")     // {exists, content, error}
fileStore.put("reports/daily.json", { "day": "…" }) // persists JSON (or raw text)
assert fileStore.list("reports").length == 1       // array of {name, path, size}
fileStore.delete("reports/daily.json")             // status 200 / 404

// sql — in-memory, seeded from the config `data` map (table -> rows)
let rows = ordersDb.query("select * from orders where status = 'pending'")
let byTotal = ordersDb.query("select * from orders order by total desc limit 2")
let res = ordersDb.execute("insert into orders (id, status) values ('ord_9', 'pending')")
assert res.affectedRows == 1
assert res.lastInsertedId != null

// queue — in-memory FIFO, shared per resource name for a run
jobsQueue.send({ "job": "invoice_1550", "amount": 1550 })
let job = jobsQueue.receive()      // {status: 200, body: <payload>} or status 204 when empty
assert job.status == 200
jobsQueue.peek()                   // next head without consuming

// email — in-memory outbox sink, `from` defaults to the config `from`
let ack = mailSender.send({ to: "ops@example.com", subject: "Daily report ready", body: "…" })
assert ack.accepted == true
assert mailSender.outbox()[0].subject == "Daily report ready"
```

The `sql` handler supports a small, self-contained SQL subset over named
tables: `create table`, `insert into <t> [(cols)] values (...)`, `select
[<cols> | *] from <t> [where …] [order by <col> asc|desc] [limit <n>]`, `update
<t> set <col> = <expr>, … [where …]`, `delete from <t> [where …]`. The `where`
clause combines conditions with `and`/`or` and supports `=`, `!=`, `<>`, `<`,
`>`, `<=`, `>=`, `in (…)`, `like 'p%'`, and `is [not] null`. Unknown operations
and unknown table `select`s fail loudly.

### Typed declaration

Assigns the result of an expression to a typed variable. When the right-hand
side is a call to a resource that declares a mapping for that model, the engine
dispatches, maps the raw response into the model, validates it, and binds the
variable — all implicitly. The statement fails if mapping or validation fails.

```bbrun
PaymentIntent created = paymentsApi.post("/payments", foreignPayment)
PaymentIntent[] payments = paymentsApi.get("/payments")     // array of mapped models
```

### `map`

Explicitly maps a value using a named DRef mapping:

```bbrun
map response.body using "Stripe PaymentIntent" as paymentIntent
```

### `validate` and `matches`

`validate` halts execution with a detailed error when DRef constraint checking
fails; `matches` is the boolean expression form for branching. Violations of a
`warnOnly` constraint are reported but never fail a validation.

```bbrun
validate newCustomer as Customer
let ok = paymentIntent matches PaymentIntent
if response.body matches PaymentIntent {
    print "valid"
}
```

For a resource that declares a mapping to a model, validating/matching its
**raw** `response.body` against that model applies the mapping first (implicit
mapping).

### `assert` and `warn`

```bbrun
assert response.status == 201                    // hard failure if false
assert response.body.amount > 0 : "amount must be positive"
warn  response.body.amount < 10000               // warning if false, script continues
```

A failed assertion says what failed: its message (or `Assertion failed`), the line
and the expression as written, the value found for a comparison, and the last
response: `amount must be positive (line 2: response.body.amount > 0); got -5; last
response 201 {...}`.

### Scenarios: `goal`, `step`, `cleanup`, `include`

A script written as a scenario states its goal, then its flow as named steps:

```bbrun
goal "A user uploads a file and reads its content through an API"
goal "Noah uploads stores.csv, maps it to a type, publishes it; the portal reads it with its key"

include "../_lib/sign-in.bbrun"                 // another script, run here, in the same context

step "Noah uploads the file" {
    files.post("/files", { "name": "stores.csv", "rows": readCsv("fixtures/stores.csv") })
    let fileId = response.body.id
    cleanup {                                   // runs when the script ends, passed or failed
        files.delete("/files/${fileId}")
    }
    assert response.status == 201
}
step "The portal reads it" {
    portal.get("/stores?City=Berlin")
    assert response.body.total == 1
}
```

- `goal "text"` records what the script is for (any number; general, then detailed).
  Goals are printed and kept in the result.
- `step "name" { ... }` runs a block as one named part of the flow. A failure inside
  fails the script as `Step "name" failed: <why>`, and the steps that ran are listed
  in the result, passed or failed. Variables declared in a step stay visible after it.
- `cleanup { ... }` registers a block that runs when the script ends, whether it passed
  or failed, the last registered first. It runs with the variables (and the
  credentials set by `authenticate`) of the moment it was registered, so register it
  right after making what it removes. A failed cleanup fails a script that passed; after
  a failure it is only a warning.
- `include "path"` runs another script file here, in the same context: its variables,
  credentials and cleanups become the script's. The path is relative to the including
  file; an included file's own paths are relative to it.

### Credentials and request options

`authenticate(resource, auth)` sends credentials with every later call to that
resource, in place of the configured `auth`, until another `authenticate`. `auth` has
the shape of the configuration's: `{"type": "bearer", "token": t}`, `{"type": "apikey",
"headerName": "X-API-Key", "headerValue": k}`, `{"type": "basic", "username": u,
"password": p}`, `{"type": "headers", "headers": {...}}`, or `{"type": "none"}`. A
script that signs in can so use the token it got:

```bbrun
signIn.post("/login", form({ "email": "ana@example.com", "password": env("PASSWORD") }))
assert response.status == 302                    // redirects are answered, not followed
api.post("/token", { "code": urlParam(response.headers.location, "code") })
authenticate(api, { "type": "bearer", "token": response.body.accessToken })
api.get("/me")
```

Arguments of an HTTP call after the path are its body and its options, in any order:
`headers({...})` adds headers to that call only (they win over the resource's), and a
body made by `form({...})` or `file(path)` is sent as it is, with its content type.
Any other body is sent as JSON.

```bbrun
assets.post("/upload?key=logo.svg", file("fixtures/logo.svg"))
assets.get("/files/logo.svg", headers({ "Accept-Language": "de", "If-None-Match": etag }))
```

Response header names are case-insensitive: `response.headers.location`,
`response.headers["content-type"]`.

### `print`

```bbrun
print "sandbox ready in " + latency + "ms"
```

### Conditionals

```bbrun
if response.status == 200 {
    print "ok: " + response.body.id
} else if response.status == 201 {
    print "created"
} else {
    warn true : "unexpected status"
}
```

Conditions use truthiness. Blocks are `{ ... }` and contain any statements.

### Loops

```bbrun
for i in 1..10 {                    // range, INCLUSIVE of 10
    print "attempt " + i
}

for c in customers {                // for-each over a list/collection
    print c.name
}
```

- `for i in start..end` yields each integer from `start` to `end` inclusive.
- `for x in coll` iterates a list's elements (or an object's values).
- The loop variable is bound per iteration and remains in scope afterwards.
- `break` exits the innermost loop immediately:

```bbrun
for payment in payments {
    if payment.status == "canceled" {
        break
    }
}
```

There is no `continue`; there is no `while` — use `for` ranges.

### `return`

Ends the flow. Inside a **mock handler script** it produces the response — the
simple form of `respond(200, expr)`:

```bbrun
let entry = ledger[params.id]
if entry == null {
    respond(404, { "error": "payment not found" })   // custom status: use respond
} else {
    return entry                                      // status 200, body = entry
}
```

At the **top level** of a script it ends the flow and yields the value to the
caller. A flow without `return` has no result payload; execution is still
synchronous. In an embedded service, the host supplies request/context data:

```bbrun
let customer = customersApi.get("/customers/${request.path.id}")
let payments = paymentsApi.get("/payments?customer=${customer.id}")
return { "customer": customer, "payments": payments }
```

The returned value is exactly that value — no response envelope. A Java host
mapping it to HTTP applies its own convention: a non-null value means 200,
`null` means 204, and a script failure is the host's 5xx. Scripts only override
those defaults when business logic demands it:

- `status(404)` sets the HTTP status code for the host to send; it does not
  change the returned value's shape.
- `header("Location", "/orders/" + id)` adds a response header for the host.

```bbrun
ordersApi.get("/records/${request.path.id}")
if response.status == 404 {
    status(404)
    return { "code": "ORDER_NOT_FOUND" }
}
Order order = response.body
status(201)
header("Location", "/orders/" + order.id)
return order
```

Both arrive in the engine result as `ExecutionResult.statusCode()` and
`ExecutionResult.responseHeaders()`; without them a host falls back to its
default mapping. These are integration-flow signals for embedded hosts, not
mock-handler response controls.

A failed script leaves two messages in the result: `ExecutionResult.errorMessage()`
holds the diagnostics (step, line, expression, the value found, the last
response), for whoever runs the script; `ExecutionResult.reason()` holds only
the author's words (`assert body.weightKg > 0 : "weightKg must be positive"`
gives `weightKg must be positive`), for a host to show its own callers.

Variables are not reassignable, so express lookups as reads
(`ledger[params.id]`) rather than accumulate-and-return.

### Embedded invocation inputs

`IntegrationEngine.execute(script, requestMap, contextMap)` binds `request`
and `context` for that invocation. Their shape is a host convention: for example,
`request.body`, `request.path.id`, `context.customerId`, and
`context.correlationId`. The host should supply caller identity separately from
the client body. These inputs are distinct from `response`, which always
describes the last resource call. Each invocation uses a fresh execution context.

See [the Java host and operation scripts](../examples/integration/order-service/README.md).
The separate [API test suite](../examples/testing/order-api/README.md) runs as a
client through the CLI and checks the public wire contract.

### `abort`

Throws an error and ends the execution prematurely. The reason is optional and,
when given, is any expression:

```bbrun
abort                                          // "Aborted at line N"
abort "payment not found"                      // "Aborted at line N: payment not found"
abort "failed for " + params.id                // reasons can be computed
```

## Response

Every resource dispatch updates the implicit `response` variable:

| Field     | Meaning |
| --------- | ------- |
| `response.status` | HTTP status code (or `201`/`200` for mocks) |
| `response.body`   | decoded payload (object, array, string, number, or `null`) |
| `response.headers`| response headers as an object |
| `response.time`   | request duration in ms |

A mock resource answers from optional configured `routes` (see `README.md` §5,
"Mock Routes"): `verb + path` — with `{param}` placeholders — mapped to a response
from `data`, `file`, `expr` or `script`:

- `data` / `file` are static (literal YAML or a JSON fixture).
- `expr` is a BBRun expression evaluated with `body`, `params`, `query`, `verb`,
  `path`, `headers` bound; its value is the response body.
- `script` is a `.bbrun` handler. It runs **without** the project's domain
  models (mocks simulate the other side of the wire): typed declarations and
  `validate` fail inside mock scripts. The script produces the response with
  `return <expr>` (status 200) or, when status/headers matter,
  `respond(status, body)`; `params` carries path captures and `body` the
  request payload.

Requests that match no route keep the generic lifecycle: `post` appends to a
per-path collection and `get` on that path returns the array. On the path of one
object (the collection's path, then the object's `id`), `get` reads it, `put`
replaces it (or adds it when it is new), `patch` changes the fields it gives and
`delete` removes it (204); the array follows each of them.
See `examples/reference/testing/_reference/tests/08-mock-configuration.bbrun`.

A mock does not have to emulate HTTP: `type: mock` + `mock: <kind>` in
`bbrun.yaml` makes it a mock of a non-HTTP resource kind (`sql`, `queue`,
`email`, `file`). Its routes match by `op` (the dispatched operation) plus the
same `when` conditions — no lifecycle fallback, unmatched operations fail —
and the bindings are `op`, `args`, `headers` and `body` (the last argument).
See `examples/reference/testing/_reference/tests/10-mocks-of-non-http.bbrun`.

## Mappings & resources

See `README.md` §5 for the full concept. In short:

- A resource is bound to a model by naming the mapping that translates its inbound
  shape into **our domain models** — models live in domains (e.g. the multi-model
  `Commerce` domain: `Customer`, `Product`, `Order`, `OrderItem`,
  `PaymentIntent`); `authority` on a model records its origin (e.g. `external` for
  gateway-fed instances). There is no mappings directory and no per-resource
  `mappings` map: a resource declares one `mapping: "<name>"`, naming an entry
  some model declares.
- A *mapping definition* is DRef's: DRef owns its vocabulary, parses it, and
  decides whether it is valid. Its keys are `name`, `provider`, `version`,
  `description` and `fields`; each field is a bare property name, a
  `{"from", "to"}` rename, a `{"from": [...], "to": ...}` fusion of whichever
  listed source fields are present, and/or a `transform` chain.
  Chains are executed by DRef's own mapper, so the whole catalog is available:
  `lowerCase`, `upperCase`, `trim`, `prefix`, `suffix`, `stripPrefix`,
  `stripSuffix`, `replace`, `substring`, `join`, `multiply`, `divide`, `abs`,
  `round`, `roundTo`, `toNumber`, `toString`, `toBoolean`, `formatDate`,
  `default`, `constant`, `custom`.
- A mapping is declared inside the model it imports onto, in that model's
  `mapsFrom`, so **the enclosing model is what it produces**. Declaring the mapping
  on the model is what keeps a mapping and the shape of its target from drifting
  apart: there is only one place either is written.
- A malformed mapping is rejected when the domain document is read, not when a
  response arrives: an unknown transform, a transform whose `param` has the wrong
  type, a field with no `from`, and a duplicate `from` are all reported by DRef at
  startup, and bbrun registers no half-built mapping in their place.
- Typed declarations, and `validate`/`matches` against a mapping resource's raw
  `response.body`, both map implicitly — no explicit `map` needed.
- The raw payload is always retained (`response.body.amount` stays in cents).

## Grammar summary

```antlr
script          : statement* EOF ;

statement       : typedDeclaration | variableDecl
                | verbRequestStatement | mapStatement
                | validateStatement | assertStatement | warnStatement
                | printStatement | ifStatement | forStatement | returnStatement
                | breakStatement | abortStatement
                | goalStatement | stepStatement | cleanupStatement | includeStatement
                | expressionStatement | ';' ;

goalStatement   : 'goal' expression ;
stepStatement   : 'step' expression block ;
cleanupStatement: 'cleanup' block ;
includeStatement: 'include' STRING ;

typedDeclaration: IDENTIFIER IDENTIFIER '=' expression
                | IDENTIFIER '[' ']' IDENTIFIER '=' expression ;
variableDecl    : 'let' IDENTIFIER '=' expression ;
mapStatement    : 'map' expression 'using' STRING ('as' IDENTIFIER)? ;
validateStatement: 'validate' expression 'as' IDENTIFIER (':' STRING)? ;
ifStatement     : 'if' expression block ('else' 'if' expression block)* ('else' block)? ;
forStatement    : 'for' IDENTIFIER 'in' expression '..' expression block   // range
                | 'for' IDENTIFIER 'in' expression block ;                 // for-each
breakStatement  : 'break' ;
returnStatement : 'return' expression ;        // mock handlers: respond(200, expr); top level: flow value
abortStatement  : 'abort' expression? ;
block           : '{' statement* '}' ;

expression      : primary
                | expression '.' (IDENTIFIER | httpVerb | keyword) '(' argumentList? ')'  // method call (resource ops, builtins)
                | expression '.' (IDENTIFIER | keyword)            // member access
                | expression '[' expression ']' | IDENTIFIER '(' argumentList? ')'
                | expression ('*'|'/') expression | expression ('+'|'-') expression
                | expression ('=='|'!='|'<'|'>'|'<='|'>=') expression
                | expression 'matches' IDENTIFIER | expression 'contains' expression
                | expression 'not' 'contains' expression | 'not' expression
                | expression 'and' expression | expression 'or' expression ;
```

Reserved words: `use`, `let`, `map`, `validate`, `as`, `assert`, `warn`,
`print`, `if`, `else`, `for`, `in`, `break`, `return`, `abort`, `true`, `false`,
`null`, `matches`, `contains`, `not`, `and`, `or`, `get`, `post`, `put`,
`patch`, `delete`, `to`, `goal`, `step`, `cleanup`, `include`.

Operation names such as `query`, `execute`, `send`, `receive`, `peek`, `list`,
`outbox` are **not** reserved: expression `.` + keyword is only matched in
method/member position, and reserved words must simply be written normally in
that position (`jobsQueue.receive()`, `{ to: "x" }`). A member or method name
that collides with a keyword is still allowed (`email.to`, `send({ to: .. })`).
