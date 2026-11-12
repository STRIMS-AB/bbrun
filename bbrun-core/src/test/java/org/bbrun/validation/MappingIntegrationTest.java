package org.bbrun.validation;

import org.bbrun.BBRun;
import org.bbrun.config.BBRunConfig;
import org.bbrun.config.EnvironmentConfig;
import org.bbrun.config.ResourceDefinition;
import org.bbrun.interpreter.ExecutionResult;
import dev.dref.DomainModel;
import dev.dref.FieldType;
import dev.dref.ModelAuthority;
import dev.dref.Property;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MappingIntegrationTest {

    private DrefRegistry registry;
    private MappingRegistry mappings;

    @BeforeEach
    void setUp() {
        // The mapping is declared on the model it imports onto, so the model IS
        // the target and the resource only has to name the mapping.
        registry = new DrefRegistry();
        registry.parseAndRegister("""
                {
                  "name": "commerce",
                  "models": [
                    {
                      "name": "PaymentIntent",
                      "authority": "external",
                      "properties": [
                        { "name": "id", "type": "String", "required": true, "key": true },
                        { "name": "amount", "type": "Number", "required": true },
                        { "name": "currency", "type": "String", "required": true,
                          "rules": [ { "type": "uppercase" } ] },
                        { "name": "status", "type": "String", "required": true,
                          "rules": [ { "type": "enum", "param": [ "succeeded", "canceled" ] } ] }
                      ],
                      "mapsFrom": [
                        {
                          "name": "Stripe PaymentIntent",
                          "provider": "Stripe",
                          "fields": [
                            "id",
                            { "from": "customer", "to": "customerId" },
                            { "from": "amount", "transform": [{ "type": "divide", "param": 100 }] },
                            { "from": "currency", "transform": ["upperCase"] },
                            "status"
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        mappings = new MappingRegistry();
        mappings.setDrefRegistry(registry);
        mappings.registerInlineMappings();
    }

    private BBRun build(ResourceDefinition res, String env) {
        EnvironmentConfig envConfig = new EnvironmentConfig(env, Map.of(res.name(), res));
        BBRunConfig config = new BBRunConfig("1.0", env, Map.of(env, envConfig), null);
        return BBRun.builder()
                .withConfig(config)
                .withEnvironment(env)
                .withDrefRegistry(registry)
                .withMappingRegistry(mappings)
                .build();
    }

    @Test
    void explicitMapStatementBindsMappedInstance() {
        ResourceDefinition payments = new ResourceDefinition("paymentsApi", "mock", null, null, null);
        BBRun bbrun = build(payments, "test");

        String script = """
                let foreign = {
                    "id": "pi_1",
                    "customer": "cus_9001",
                    "amount": 34999,
                    "currency": "usd",
                    "status": "succeeded"
                }

                paymentsApi.post("/payments", foreign)
                assert response.status == 201

                map response.body using "stripe-payment-intent" as paymentIntent

                assert paymentIntent.amount == 349.99
                assert paymentIntent.currency == "USD"
                assert paymentIntent.customerId == "cus_9001"
                validate paymentIntent as PaymentIntent
                """;

        ExecutionResult res = bbrun.execute(script);
        assertTrue(res.isSuccess(), "Execution failed: " + res.errorMessage());
        assertEquals(5, res.passedAssertions());
        assertEquals(0, res.failedAssertions());
    }

    @Test
    void implicitMappingAppliesWhenValidatingExternalResponse() {
        ResourceDefinition payments = new ResourceDefinition(
                "paymentsApi", "mock", null, "Stripe PaymentIntent"
        );
        BBRun bbrun = build(payments, "test");

        String script = """
                let foreign = {
                    "id": "pi_2",
                    "customer": "cus_9002",
                    "amount": 4999,
                    "currency": "usd",
                    "status": "succeeded"
                }

                paymentsApi.post("/payments", foreign)
                assert response.status == 201

                assert response.body.amount == 4999
                validate response.body as PaymentIntent
                assert response.body matches PaymentIntent
                """;

        ExecutionResult res = bbrun.execute(script);
        assertTrue(res.isSuccess(), "Execution failed: " + res.errorMessage());
        assertEquals(4, res.passedAssertions());
        assertEquals(0, res.failedAssertions());
    }

    @Test
    void noImplicitMappingForNonExternalResource() {
        ResourceDefinition orders = new ResourceDefinition("ordersApi", "mock", null, null, null);
        BBRun bbrun = build(orders, "test");

        String script = """
                let foreign = {
                    "id": "pi_3",
                    "customer": "cus_9003",
                    "amount": 4999,
                    "currency": "usd",
                    "status": "succeeded"
                }

                ordersApi.post("/orders", foreign)
                assert response.status == 201
                validate response.body as PaymentIntent
                """;

        ExecutionResult res = bbrun.execute(script);
        assertFalse(res.isSuccess());
    }

    @Test
    void typedDeclarationMapsAndValidates() {
        ResourceDefinition payments = new ResourceDefinition(
                "paymentsApi", "mock", null, "Stripe PaymentIntent"
        );
        BBRun bbrun = build(payments, "test");

        String script = """
                let foreign = {
                    "id": "pi_4",
                    "customer": "cus_9004",
                    "amount": 39999,
                    "currency": "usd",
                    "status": "succeeded"
                }

                PaymentIntent created = paymentsApi.post("/payments", foreign)
                assert created.amount == 399.99
                assert created.currency == "USD"
                assert created.customerId == "cus_9004"
                """;

        ExecutionResult res = bbrun.execute(script);
        assertTrue(res.isSuccess(), "Execution failed: " + res.errorMessage());
        assertEquals(3, res.passedAssertions());
        assertEquals(0, res.failedAssertions());
    }

    @Test
    void typedArrayDeclarationMapsEachElement() {
        ResourceDefinition payments = new ResourceDefinition(
                "paymentsApi", "mock", null, "Stripe PaymentIntent"
        );
        BBRun bbrun = build(payments, "test");

        String script = """
                let foreign = {
                    "id": "pi_5",
                    "customer": "cus_9005",
                    "amount": 49999,
                    "currency": "usd",
                    "status": "succeeded"
                }

                PaymentIntent created = paymentsApi.post("/payments", foreign)
                PaymentIntent[] list = paymentsApi.get("/payments")
                assert list.length == 1
                assert list[0].amount == 499.99
                assert list[0] matches PaymentIntent
                """;

        ExecutionResult res = bbrun.execute(script);
        assertTrue(res.isSuccess(), "Execution failed: " + res.errorMessage());
        assertEquals(3, res.passedAssertions());
        assertEquals(0, res.failedAssertions());
    }

    @Test
    void typedDeclarationFailsOnUnknownModel() {
        ResourceDefinition payments = new ResourceDefinition(
                "paymentsApi", "mock", null, "Stripe PaymentIntent"
        );
        BBRun bbrun = build(payments, "test");

        String script = """
                let foreign = {
                    "id": "pi_6",
                    "customer": "cus_9006",
                    "amount": 1,
                    "currency": "usd",
                    "status": "succeeded"
                }

                UnknownModel x = paymentsApi.post("/payments", foreign)
                """;

        ExecutionResult res = bbrun.execute(script);
        assertFalse(res.isSuccess());
        assertTrue(res.errorMessage().contains("Unknown model type 'UnknownModel'"));
    }

    @Test
    void typedDeclarationFailsOnInvalidMappedData() {
        ResourceDefinition payments = new ResourceDefinition(
                "paymentsApi", "mock", null, "Stripe PaymentIntent"
        );
        BBRun bbrun = build(payments, "test");

        String script = """
                let foreign = {
                    "id": "pi_7",
                    "customer": "cus_9007",
                    "amount": 100,
                    "currency": "usd",
                    "status": "expired"
                }

                PaymentIntent bad = paymentsApi.post("/payments", foreign)
                """;

        ExecutionResult res = bbrun.execute(script);
        assertFalse(res.isSuccess());
    }
}