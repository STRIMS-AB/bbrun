package org.bbrun.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigLoaderTest {

    @Test
    void testParseEnvironmentsAndInferredType() {
        String yaml = """
            version: "1.0"
            defaultEnvironment: "dev"
            environments:
              dev:
                resources:
                  paymentGateway:
                    baseUrl: "https://api.dev.payments.com"
                    auth:
                      type: bearer
                      token: "secret-token"
              prod:
                resources:
                  paymentGateway:
                    baseUrl: "https://api.payments.com"
            """;

        BBRunConfig config = ConfigLoader.parse(yaml);
        assertEquals("1.0", config.version());
        assertEquals("dev", config.defaultEnvironment());

        EnvironmentConfig dev = config.getEnvironment("dev");
        assertNotNull(dev);
        assertTrue(dev.hasResource("paymentGateway"));

        ResourceDefinition devRes = dev.getResource("paymentGateway");
        assertEquals("https://api.dev.payments.com", devRes.baseUrl());
        assertEquals("http", devRes.resolvedType());

        assertEquals("bearer", devRes.auth().type());
        assertEquals("secret-token", devRes.auth().token());

        // Prod environment checks
        EnvironmentConfig prod = config.getEnvironment("prod");
        ResourceDefinition prodRes = prod.getResource("paymentGateway");
        assertEquals("https://api.payments.com", prodRes.baseUrl());
        assertEquals("http", prodRes.resolvedType());
    }

    @Test
    void testDeclaredMockType() {
        String yaml = """
            version: "1.0"
            defaultEnvironment: "dev"
            environments:
              dev:
                resources:
                  localApi:
                    type: mock
            """;

        BBRunConfig config = ConfigLoader.parse(yaml);
        ResourceDefinition res = config.getEnvironment("dev").getResource("localApi");
        assertEquals("mock", res.resolvedType());
    }

    @Test
    void testMockSchemeInfersMockType() {
        String yaml = """
            version: "1.0"
            defaultEnvironment: "dev"
            environments:
              dev:
                resources:
                  localApi:
                    baseUrl: "mock://customers"
            """;

        BBRunConfig config = ConfigLoader.parse(yaml);
        ResourceDefinition res = config.getEnvironment("dev").getResource("localApi");
        assertEquals("mock://customers", res.baseUrl());
        assertEquals("mock", res.resolvedType());
    }

    @Test
    void testParseOAuth2AuthConfig() {
        String yaml = """
            version: "1.0"
            defaultEnvironment: "dev"
            environments:
              dev:
                resources:
                  edgeApi:
                    baseUrl: "https://org-test.apigee.net/v1"
                    auth:
                      type: oauth2
                      tokenUrl: "https://org-test.apigee.net/oauth2/token"
                      clientId: "${APIGEE_CLIENT_ID}"
                      clientSecret: "${APIGEE_CLIENT_SECRET}"
                      scope: "orders.read orders.write"
            """;

        BBRunConfig config = ConfigLoader.parse(yaml);
        ResourceDefinition res = config.getEnvironment("dev").getResource("edgeApi");
        ResourceAuth auth = res.auth();
        assertEquals("oauth2", auth.type());
        assertTrue(auth.isOAuth2());
        assertEquals("https://org-test.apigee.net/oauth2/token", auth.tokenUrl());
        assertEquals("${APIGEE_CLIENT_ID}", auth.clientId());
        assertEquals("${APIGEE_CLIENT_SECRET}", auth.clientSecret());
        assertEquals("orders.read orders.write", auth.scope());
    }

    @Test
    void testParsesDomainPathAndBaseDir() {
        String yaml = "domain: models/domain.json";
        BBRunConfig config = ConfigLoader.parse(yaml, Path.of("/cfg"));
        assertEquals("models/domain.json", config.dref().domain());
        assertEquals(Path.of("/cfg"), config.basePath());
    }

    @Test
    void testDomainDefaultsToDomainJson() {
        BBRunConfig config = ConfigLoader.parse("version: \"1.0\"");
        assertEquals("domain.json", config.dref().domain());
        assertNull(config.basePath());
    }

    @Test
    void aResourceNamesTheMappingThatFeedsIt() {
        BBRunConfig config = ConfigLoader.parse("""
                version: "1.0"
                environments:
                  local:
                    resources:
                      paymentsApi:
                        type: mock
                        mapping: "Stripe PaymentIntent"
                """);
        ResourceDefinition payments = config.environments().get("local").resources().get("paymentsApi");
        assertEquals("Stripe PaymentIntent", payments.mapping());
    }

    @Test
    void aResourceWithoutAMappingIsLeftUnbound() {
        BBRunConfig config = ConfigLoader.parse("""
                version: "1.0"
                environments:
                  local:
                    resources:
                      customersApi:
                        type: mock
                """);
        assertNull(config.environments().get("local").resources().get("customersApi").mapping());
    }

    @Test
    void theRetiredMappingsKeysAreRejected() {
        assertThrows(ConfigurationException.class, () -> ConfigLoader.parse("""
                version: "1.0"
                mappings: "mappings"
                """));
        assertThrows(ConfigurationException.class, () -> ConfigLoader.parse("""
                version: "1.0"
                environments:
                  local:
                    resources:
                      paymentsApi:
                        type: mock
                        mappings:
                          PaymentIntent: paymentsApi.json
                """));
    }

    @Test
    void testEnvVarInterpolation() {
        String template = "token: ${TEST_NON_EXISTENT_VAR:-fallback123}";
        String result = ConfigLoader.interpolateEnvVars(template);
        assertEquals("token: fallback123", result);
    }
}