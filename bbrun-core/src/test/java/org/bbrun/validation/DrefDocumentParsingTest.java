package org.bbrun.validation;

import dev.dref.DomainModel;
import dev.dref.FieldMapping;
import dev.dref.ModelAuthority;
import dev.dref.ModelType;
import dev.dref.Property;
import dev.dref.TransformType;
import dev.regl.Rule;
import dev.regl.ValidationError;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DrefDocumentParsingTest {

    private static DomainModel model(DrefRegistry registry, String name) {
        return registry.getModel(name);
    }

    private static Property property(DomainModel model, String name) {
        return model.properties().stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no property '" + name + "' on " + model.name()));
    }

    @Test
    void singleModelDocumentKeepsCanonicalParam() {
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("""
                {
                  "name": "Product",
                  "properties": [
                    { "name": "id", "type": "String", "required": true, "key": true },
                    { "name": "name", "type": "String",
                      "rules": [ { "type": "maxLength", "param": 10 } ] }
                  ]
                }
                """);

        List<Rule> rules = property(model(registry, "Product"), "name").rules();
        assertEquals(1, rules.size());
        assertEquals("maxLength", rules.get(0).code());
        assertEquals(10, ((Number) rules.get(0).param()).intValue());
    }

    @Test
    void singleModelDocumentParsesRules() {
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("""
                {
                  "name": "Product",
                  "properties": [
                    { "name": "id", "type": "String", "required": true, "key": true },
                    { "name": "name", "type": "String",
                      "rules": [ { "type": "maxLength", "param": 10 } ] }
                  ]
                }
                """);

        List<Rule> rules = property(model(registry, "Product"), "name").rules();
        assertEquals(10, ((Number) rules.get(0).param()).intValue());
    }

    @Test
    void enumRuleWithListParameter() {
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("""
                {
                  "name": "Product",
                  "properties": [
                    { "name": "id", "type": "String", "required": true, "key": true },
                    { "name": "currency", "type": "String",
                      "rules": [ { "type": "enum", "param": [ "USD", "EUR" ] } ] }
                  ]
                }
                """);

        Object param = property(model(registry, "Product"), "currency").rules().get(0).param();
        assertEquals(List.of("USD", "EUR"), param);
    }

    @Test
    void authorityDefaultsToInternalAndTtlStaysUndeclared() {
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("""
                {
                  "name": "Product",
                  "properties": [ { "name": "id", "type": "String", "required": true, "key": true } ]
                }
                """);

        DomainModel product = model(registry, "Product");
        assertEquals(ModelAuthority.INTERNAL, product.authority());
        assertNull(product.ttl());
    }

    @Test
    void declaredTtlAndAuthorityAreKept() {
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("""
                {
                  "name": "Session",
                  "authority": "external",
                  "ttl": "30d",
                  "properties": [ { "name": "id", "type": "String", "required": true, "key": true } ]
                }
                """);

        DomainModel session = model(registry, "Session");
        assertEquals(ModelAuthority.EXTERNAL, session.authority());
        assertEquals("30d", session.ttl());
    }

    @Test
    void namedFieldTypesSupplyTheirRules() {
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("""
                {
                  "name": "Commerce",
                  "defaults": {
                    "fields": {
                      "ReferenceId": { "type": "String", "rules": [ { "type": "notEmpty" } ] },
                      "OrderRef": { "type": "ReferenceId", "rules": [ { "type": "startsWith", "param": "ord_" } ] }
                    }
                  },
                  "models": [
                    { "name": "Order", "properties": [
                        { "name": "id", "type": "OrderRef", "required": true, "key": true } ] }
                  ]
                }
                """);

        List<String> codes = property(model(registry, "Order"), "id").rules().stream()
                .map(Rule::code)
                .toList();
        assertEquals(List.of("notEmpty", "startsWith"), codes);
    }

    @Test
    void modelTypeIsDerivedRatherThanDeclared() {
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("""
                {
                  "name": "Commerce",
                  "models": [
                    { "name": "Customer", "properties": [
                        { "name": "id", "type": "String", "required": true, "key": true } ] },
                    { "name": "OrderItem", "properties": [
                        { "name": "orderId", "type": "String", "rules": [ { "type": "immutable" } ] } ] },
                    { "name": "Tier", "count": { "min": 1, "max": 3 }, "properties": [
                        { "name": "id", "type": "String", "required": true, "key": true } ] }
                  ]
                }
                """);

        assertEquals(ModelType.ENTITY, model(registry, "Customer").classify());
        assertEquals(ModelType.VALUE, model(registry, "OrderItem").classify());
        assertEquals(ModelType.CONFIGURATION, model(registry, "Tier").classify());
    }

    @Test
    void anUnboundedCountStillCountsAsBounded() {
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("""
                {
                  "name": "Commerce",
                  "models": [
                    { "name": "Customer", "count": { "min": 0 },
                      "properties": [ { "name": "id", "type": "String", "required": true, "key": true } ] }
                  ]
                }
                """);

        assertEquals(ModelType.CONFIGURATION, model(registry, "Customer").classify());
    }

    @Test
    void warnOnlyRulesAreReportedButDoNotFail() {
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("""
                {
                  "name": "Customer",
                  "properties": [
                    { "name": "id", "type": "String", "required": true, "key": true },
                    { "name": "phone", "type": "String",
                      "rules": [ { "type": "startsWith", "param": "+", "warnOnly": true } ] }
                  ]
                }
                """);

        DrefValidatorBridge bridge = new DrefValidatorBridge(registry);
        List<ValidationError> warnings = new ArrayList<>();
        bridge.setWarningSink(warnings::add);

        bridge.validateOrThrow(Map.of("id", "cus_1", "phone", "555-0100"), "Customer");
        assertEquals(1, warnings.size());
        assertEquals("phone", warnings.get(0).field());
    }

    @Test
    void fieldReferenceRulesCompareAgainstSiblings() {
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("""
                {
                  "name": "OrderItem",
                  "properties": [
                    { "name": "quantity", "type": "Number", "required": true, "key": true },
                    { "name": "lineTotal", "type": "Number", "required": true,
                      "rules": [ { "type": "lessThanOrEqual", "param": "$maxLineTotal" } ] },
                    { "name": "maxLineTotal", "type": "Number", "required": false }
                  ]
                }
                """);

        DrefValidatorBridge bridge = new DrefValidatorBridge(registry);
        bridge.setWarningSink(w -> { });
        assertTrue(bridge.matches(Map.of("quantity", 2, "lineTotal", 10, "maxLineTotal", 10), "OrderItem"));
        assertFalse(bridge.matches(Map.of("quantity", 2, "lineTotal", 11, "maxLineTotal", 10), "OrderItem"));
    }

    @Test
    void inlineMappingsAreRegisteredAndApplied() {
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("""
                {
                  "name": "Commerce",
                  "models": [
                    { "name": "Order",
                      "mapsFrom": [
                        { "name": "Order Service Order", "provider": "Order Service", "fields": [
                            { "from": "order_id", "to": "id" },
                            { "from": "total_cents", "to": "total", "transform": [ { "type": "divide", "param": 100 } ] }
                        ] }
                      ],
                      "properties": [
                        { "name": "id", "type": "String", "required": true, "key": true },
                        { "name": "total", "type": "Number", "required": true } ] }
                  ]
                }
                """);

        assertEquals(1, registry.inlineMappings().size());
        assertEquals("Order", registry.inlineMappings().get(0).model());

        MappingRegistry mappings = new MappingRegistry();
        mappings.setDrefRegistry(registry);
        mappings.registerInlineMappings();

        Mapping mapping = mappings.getMapping("Order Service Order");
        assertEquals("Order", mapping.targetModel());
        assertEquals("4.99", String.valueOf(mappings.apply(mapping, Map.of("order_id", "ord_1", "total_cents", 499)).get("total")));
    }

    @Test
    void domainFormatsDriveDateTransforms() {
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("""
                {
                  "name": "Commerce",
                  "defaults": { "formats": { "dateTime": "YYYY/MM/DD HH:mm" } },
                  "models": [
                    { "name": "Event", "properties": [
                        { "name": "id", "type": "String", "required": true, "key": true },
                        { "name": "at", "type": "DateTime", "required": true } ] }
                  ]
                }
                """);

        MappingRegistry mappings = new MappingRegistry();
        mappings.setDrefRegistry(registry);
        Mapping mapping = new Mapping("stamp", null, null, null, "Event", "Commerce", List.of(
                new Mapping.MappingField("created", List.of(), "at",
                List.of(new Mapping.TransformStep("formatDate", "unix")))));

        assertEquals("2025/01/07 20:00",
                mappings.apply(mapping, Map.of("created", 1736280000L)).get("at"));
    }

    @Test
    void aTopLevelArrayRegistersEveryModel() {
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("""
                [
                  { "name": "Customer", "properties": [ { "name": "id", "type": "String", "key": true } ] },
                  { "name": "Product",  "properties": [ { "name": "id", "type": "String", "key": true } ] }
                ]
                """);

        assertNotNull(model(registry, "Customer"));
        assertNotNull(model(registry, "Product"));
    }

    /**
     * The canonical path: a mapping is declared on the model it imports onto via
     * {@code mapsFrom}, DRef parses it, and bbrun exposes it by name with the
     * declaring model as its target.
     */
    private static MappingRegistry registryForMapsFrom(String modelName, String mappingJson) {
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("{\"name\":\"Commerce\",\"models\":[{\"name\":\"" + modelName
                + "\",\"properties\":[{\"name\":\"id\",\"type\":\"String\"}],\"mapsFrom\":["
                + mappingJson + "]}]}");
        MappingRegistry mappings = new MappingRegistry();
        mappings.setDrefRegistry(registry);
        mappings.registerInlineMappings();
        return mappings;
    }

    @Test
    void drefParsesEveryDocumentedFieldForm() {
        MappingRegistry registry = registryForMapsFrom("Product", """
                { "name": "Every form", "provider": "P", "version": "2.0.0",
                  "fields": [
                    "id",
                    { "from": "sku", "to": "name" },
                    { "from": "price_cents", "to": "unitPrice",
                      "transform": [ { "type": "divide", "param": 100 } ] },
                    { "from": "currency", "transform": ["upperCase"] },
                    { "from": [ "total_cents", "amount_cents" ], "to": "amount" } ] }
                """);

        Mapping mapping = registry.getMapping("Every form");
        assertEquals("P", mapping.provider());
        assertEquals("2.0.0", mapping.version());
        // The target is the model that declared the mapping; bbrun never chooses it.
        assertEquals("Product", mapping.targetModel());

        List<FieldMapping> fields = mapping.definition().fields();
        assertEquals(5, fields.size());
        assertEquals("id", fields.get(0).targetProperty());
        assertEquals(List.of(), fields.get(0).transforms());
        assertEquals("name", fields.get(1).targetProperty());
        assertEquals(TransformType.UPPER_CASE, fields.get(3).transforms().get(0).type());
        assertTrue(fields.get(4).isMultiSource());
        assertEquals(List.of("total_cents", "amount_cents"), fields.get(4).sourceProperties());
    }

    /**
     * DRef owns mapping validation. A malformed {@code mapsFrom} must be rejected by
     * DRef, and bbrun must not paper over it by registering a half-built mapping.
     */
    @Test
    void aMalformedMapsFromIsRejectedByDrefAndNeverRegistered() {
        java.io.PrintStream err = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured));
        MappingRegistry registry;
        try {
            registry = registryForMapsFrom("Product", """
                    { "name": "Bad param", "fields": [
                      { "from": "a", "to": "b", "transform": [ { "type": "divide", "param": "one hundred" } ] } ] }
                    """);
        } finally {
            System.setErr(err);
        }
        assertThrows(MappingException.class, () -> registry.getMapping("Bad param"));
    }
}
