# Quickstart: a script that needs nothing else

Three files, no service to start. `shopApi` is a mock in the environment `local`, so the
script runs with only the `bbrun` command:

```bash
cd examples/quickstart
bbrun checkout.bbrun
```

| File | What |
| --- | --- |
| `checkout.bbrun` | the scenario: look at a product, order it, find the order again |
| `bbrun.yaml` | what `shopApi` is: a mock in `local`, a real service in `test` |
| `mocks/shopApi.yaml` | what the mock answers |

The same script against a real service that answers these calls:

```bash
SHOP_URL=https://shop.example.com bbrun checkout.bbrun --env test
```

The sample order service of this repository is one
(`./gradlew :order-service-example:run`, then `--env test` with no `SHOP_URL`).

The guide at [bbrun.org](https://bbrun.org/docs/) walks through these files.
