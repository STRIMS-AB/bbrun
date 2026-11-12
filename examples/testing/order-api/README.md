# Cover one backend API as an app client

**Audience:** a tester covering the Order API. Every call goes to `shopApi`.
`/products` and `/orders` are endpoints of that same backend, not separately
configured APIs. Tests know only its public contract.

## What is covered?

| Script | App behavior / expected outcome |
| --- | --- |
| `tests/01-app-checkout.bbrun` | Browse product → place order → reopen order details → request confirmation; verify price, identity, persistence, and 204 with no body |
| `tests/02-invalid-input.bbrun` | Missing fields, zero/negative quantity → 400 with a useful error; a subsequent valid submission still succeeds |
| `tests/03-not-found.bbrun` | Missing product, order, or confirmation target → documented 404 error |

`domain.json` describes the **public response contract**. Validation happens
on the response as received. There is no `mappings/` directory or upstream
resource configuration in this project.

## Run against the supplied demo backend

From the repository root, start the backend in one terminal:

```bash
./gradlew :order-service-example:run --console=plain
```

In another terminal, build the CLI and run a scenario:

```bash
./gradlew :bbrun-cli:installDist
./bbrun-cli/build/install/bbrun/bin/bbrun \
  examples/testing/order-api/tests/01-app-checkout.bbrun \
  -c examples/testing/order-api/bbrun.yaml
```

Run all scenarios, retaining a nonzero exit status if any fail:

```bash
failed=0
for script in examples/testing/order-api/tests/*.bbrun; do
  ./bbrun-cli/build/install/bbrun/bin/bbrun "$script" \
    -c examples/testing/order-api/bbrun.yaml || failed=1
done
test "$failed" -eq 0
```

The demo supplies product `P100` (Travel mug, EUR 12.50) and caller
`customer-alice` via the configured `X-Customer-Id` demo header. Created orders
get unique IDs; scenarios can be rerun and don't depend on one another's IDs.
The demo's in-memory orders reset when its Java process stops.

## Point the same coverage at a deployed API

Set `ORDER_API_URL` to another deployment of this contract, then run the same
scripts. Configure that deployment's authentication in `bbrun.yaml`, replacing
the demo caller header. Provision the documented test product/account there.
The backend does not need to use BBRun internally.

For a different API, change the single resource URL/auth, replace the public
DRef models, and write scenarios around what its app actually does.
