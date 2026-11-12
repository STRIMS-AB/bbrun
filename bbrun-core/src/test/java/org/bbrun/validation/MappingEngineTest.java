package org.bbrun.validation;

import org.bbrun.validation.Mapping.MappingField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MappingEngineTest {

    private final MappingEngine engine = new MappingEngine();

    private Mapping mapping(List<MappingField> fields) {
        return new Mapping("Test Mapping", "Provider", "1.0.0", null, "Target", null, fields);
    }

    @Test
    void copiesOneToOneAndRenames() {
        Mapping m = mapping(List.of(
                new MappingField("id", "id", List.of()),
                new MappingField("order_id", "orderId", List.of())
        ));
        Map<String, Object> result = engine.apply(m, Map.of("id", "pi_1", "order_id", "ord_1"));
        assertEquals(Map.of("id", "pi_1", "orderId", "ord_1"), result);
    }

    @Test
    void appliesDivideAndUpperCase() {
        Mapping m = mapping(List.of(
                new MappingField("amount", "amount", List.of(new Mapping.TransformStep("divide", 100))),
                new MappingField("currency", "currency", List.of(new Mapping.TransformStep("upperCase", null)))
        ));
        Map<String, Object> result = engine.apply(m, Map.of("amount", 125050, "currency", "eur"));
        assertEquals(1250.5, (Double) result.get("amount"), 0.0001);
        assertEquals("EUR", result.get("currency"));
    }

    @Test
    void formatsUnixDateToIso() {
        Mapping m = mapping(List.of(
                new MappingField("created", "createdAt", List.of(new Mapping.TransformStep("formatDate", "unix")))
        ));
        Map<String, Object> result = engine.apply(m, Map.of("created", 1736280000));
        assertEquals("2025-01-07T20:00:00Z", result.get("createdAt"));
    }

    @Test
    void keepsAbsentSourceFieldsAsNull() {
        Mapping m = mapping(List.of(
                new MappingField("amount", "amount", List.of()),
                new MappingField("missing", "missing", List.of())
        ));
        Map<String, Object> result = engine.apply(m, Map.of("amount", 5));
        assertEquals(5, result.get("amount"));
        assertTrue(result.containsKey("missing"));
        assertNull(result.get("missing"));
    }

    @Test
    void appliesDefaultToAbsentSourceFields() {
        Mapping m = mapping(List.of(
                new MappingField("note", "note", List.of(new Mapping.TransformStep("default", "n/a")))
        ));
        assertEquals("n/a", engine.apply(m, Map.of()).get("note"));
        assertEquals("set", engine.apply(m, Map.of("note", "set")).get("note"));
    }

    @Test
    void fusesSeveralSourceFieldsIntoOneTarget() {
        Mapping m = mapping(List.of(
                new MappingField(null, List.of("given", "family"), "fullName", List.of(
                        new Mapping.TransformStep("join", " "))))
        );
        assertEquals("Ada Lovelace", engine.apply(m, Map.of("given", "Ada", "family", "Lovelace")).get("fullName"));
    }

    @Test
    void usesTheFullTransformCatalog() {
        Mapping m = mapping(List.of(
                new MappingField("slug", "slug", List.of(new Mapping.TransformStep("prefix", "prod_"))),
                new MappingField("label", "label", List.of(new Mapping.TransformStep("substring",
                        Map.of("start", 0, "end", 5)))),
                new MappingField("cents", "amount", List.of(
                        new Mapping.TransformStep("divide", 100),
                        new Mapping.TransformStep("roundTo", 2))),
                new MappingField("active", "active", List.of(new Mapping.TransformStep("toBoolean", null)))
        ));
        Map<String, Object> result = engine.apply(m, Map.of(
                "slug", "tea", "label", "Earl Grey", "cents", 1250, "active", "1"));
        assertEquals("prod_tea", result.get("slug"));
        assertEquals("Earl ", result.get("label"));
        assertEquals(12.5, (Double) result.get("amount"), 0.0001);
        assertEquals(true, result.get("active"));
    }

    @Test
    void throwsOnUnknownTransformWhenTheMappingIsBuilt() {
        // A malformed transform is rejected when the mapping is created, so a
        // typo in a document is reported on load rather than on the first
        // response that happens to reach that field.
        assertThrows(MappingException.class, () -> mapping(List.of(
                new MappingField("amount", "amount", List.of(new Mapping.TransformStep("teleport", null)))
        )));
    }

    @Test
    void parsesAMappingDeclaredOnItsTargetModel() {
        // DRef puts the mapping on the model it imports onto, so the target is
        // the declaring model and DRef itself builds the field list.
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("""
                {
                  "name": "commerce",
                  "models": [
                    {
                      "name": "PaymentIntent",
                      "properties": [ { "name": "id", "type": "String" } ],
                      "mapsFrom": [
                        {
                          "name": "Stripe PaymentIntent",
                          "provider": "Stripe",
                          "fields": [
                            "id",
                            { "from": "customer", "to": "customerId" },
                            { "from": "amount", "transform": [{ "type": "divide", "param": 100 }] },
                            { "from": "currency", "transform": ["upperCase"] }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);
        MappingRegistry mappings = new MappingRegistry();
        mappings.setDrefRegistry(registry);
        mappings.registerInlineMappings();

        Mapping parsed = mappings.getMapping("Stripe PaymentIntent");
        assertNotNull(parsed);
        assertEquals("PaymentIntent", parsed.targetModel());
        assertEquals(4, parsed.fields().size());
        assertEquals("customerId", parsed.fields().get(1).to());
        assertEquals("divide", parsed.fields().get(2).transform().get(0).type());
        assertEquals(100, ((Number) parsed.fields().get(2).transform().get(0).param()).intValue());
    }

    // ---- resource -> mapping binding: a resource names the mapping that feeds it ----

    private static MappingRegistry registryWithTwoModelMappings() {
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("""
                {
                  "name": "commerce",
                  "models": [
                    {
                      "name": "PaymentIntent",
                      "properties": [ { "name": "id", "type": "String" } ],
                      "mapsFrom": [ { "name": "Stripe PaymentIntent", "fields": [ "id" ] } ]
                    },
                    {
                      "name": "Product",
                      "properties": [ { "name": "id", "type": "String" } ],
                      "mapsFrom": [ { "name": "Catalog product", "fields": [ "id" ] } ]
                    }
                  ]
                }
                """);
        MappingRegistry mappings = new MappingRegistry();
        mappings.setDrefRegistry(registry);
        mappings.registerInlineMappings();
        return mappings;
    }

    @Test
    void aBoundResourceUsesTheMappingItNames() {
        MappingRegistry mappings = registryWithTwoModelMappings();
        mappings.bindResource("paymentsApi", "Stripe PaymentIntent");

        Mapping found = mappings.findMappingFor("paymentsApi", "PaymentIntent");
        assertNotNull(found);
        assertEquals("Stripe PaymentIntent", found.name());
    }

    @Test
    void aBindingDoesNotApplyToAModelItDoesNotTarget() {
        MappingRegistry mappings = registryWithTwoModelMappings();
        mappings.bindResource("paymentsApi", "Stripe PaymentIntent");

        // The resource speaks Stripe, so it cannot produce a Product.
        assertNull(mappings.findMappingFor("paymentsApi", "Product"));
    }

    @Test
    void anUnboundResourceHasNoImplicitMapping() {
        MappingRegistry mappings = registryWithTwoModelMappings();
        assertNull(mappings.findMappingFor("paymentsApi", "PaymentIntent"));
    }

    @Test
    void bindingToAMappingNoModelDeclaresIsReportedAndNotBound() {
        MappingRegistry mappings = registryWithTwoModelMappings();
        java.io.PrintStream err = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured));
        try {
            mappings.bindResource("paymentsApi", "No Such Mapping");
        } finally {
            System.setErr(err);
        }
        assertTrue(captured.toString().contains("No Such Mapping"), captured.toString());
        assertNull(mappings.findMappingFor("paymentsApi", "PaymentIntent"));
    }

    @Test
    void resourceNamesAreMatchedCaseInsensitively() {
        MappingRegistry mappings = registryWithTwoModelMappings();
        mappings.bindResource("PaymentsApi", "Stripe PaymentIntent");
        assertNotNull(mappings.findMappingFor("paymentsapi", "PaymentIntent"));
    }
}
