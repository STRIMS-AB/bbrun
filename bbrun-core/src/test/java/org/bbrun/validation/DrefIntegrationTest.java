package org.bbrun.validation;

import org.bbrun.BBRun;
import org.bbrun.config.*;
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

public class DrefIntegrationTest {

    private DrefRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DrefRegistry();

        // Register a Customer domain model with rules
        Property idProp = Property.builder("id", FieldType.STRING)
                .required(true)
                .minLength(3)
                .build();

        Property emailProp = Property.builder("email", FieldType.STRING)
                .required(true)
                .email()
                .build();

        Property ageProp = Property.builder("age", FieldType.NUMBER)
                .required(false)
                .positiveInteger()
                .build();

        DomainModel customerModel = new DomainModel(
                "Customer",
                ModelAuthority.INTERNAL,
                "persistent",
                null,
                List.of(idProp, emailProp, ageProp)
        );

        registry.register(customerModel);
    }

    @Test
    void testValidCustomer() {
        DrefValidatorBridge bridge = new DrefValidatorBridge(registry);
        Map<String, Object> validData = Map.of(
                "id", "cust_001",
                "email", "alex@example.com",
                "age", 25
        );

        assertTrue(bridge.matches(validData, "Customer"));
        assertDoesNotThrow(() -> bridge.validateOrThrow(validData, "Customer"));
    }

    @Test
    void testInvalidCustomerThrowsValidationException() {
        DrefValidatorBridge bridge = new DrefValidatorBridge(registry);
        // Invalid email, negative age, and id too short
        Map<String, Object> invalidData = Map.of(
                "id", "ab",
                "email", "not-an-email",
                "age", -5
        );

        assertFalse(bridge.matches(invalidData, "Customer"));

        DrefValidationException ex = assertThrows(
                DrefValidationException.class,
                () -> bridge.validateOrThrow(invalidData, "Customer")
        );

        assertEquals("Customer", ex.getModelName());
        assertFalse(ex.getErrors().isEmpty());
        assertTrue(ex.getMessage().contains("✖ [id]"));
        assertTrue(ex.getMessage().contains("✖ [email]"));
    }

    @Test
    void testScriptWithValidateStatementAndMatchesExpression() {
        ResourceDefinition mockRes = new ResourceDefinition(
                "customerApi", "mock", null, null, null
        );
        EnvironmentConfig env = new EnvironmentConfig("test", Map.of("customerApi", mockRes));
        BBRunConfig config = new BBRunConfig("1.0", "test", Map.of("test", env), null);

        BBRun bbrun = BBRun.builder()
                .withConfig(config)
                .withEnvironment("test")
                .withDrefRegistry(registry)
                .build();

        String script = """
            let newCustomer = {
                "id": "cust_123",
                "email": "customer@company.com",
                "age": 30
            }
            
            post newCustomer to customerApi."/customers"
            
            assert response.status == 201
            validate newCustomer as Customer
            assert newCustomer matches Customer
            """;

        ExecutionResult result = bbrun.execute(script);
        assertTrue(result.isSuccess(), "Script execution failed: " + result.errorMessage());
        assertEquals(3, result.passedAssertions()); // 2 asserts + 1 validate
        assertEquals(0, result.failedAssertions());
    }

    @Test
    void testScriptWithFailingValidateStatement() {
        ResourceDefinition mockRes = new ResourceDefinition(
                "customerApi", "mock", null, null, null
        );
        EnvironmentConfig env = new EnvironmentConfig("test", Map.of("customerApi", mockRes));
        BBRunConfig config = new BBRunConfig("1.0", "test", Map.of("test", env), null);

        BBRun bbrun = BBRun.builder()
                .withConfig(config)
                .withEnvironment("test")
                .withDrefRegistry(registry)
                .build();

        String failingScript = """
            let badCustomer = {
                "id": "c",
                "email": "invalid"
            }
            
            validate badCustomer as Customer
            """;

        ExecutionResult result = bbrun.execute(failingScript);
        assertFalse(result.isSuccess());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("Validation failed against DRef model 'Customer'"));
    }
}
