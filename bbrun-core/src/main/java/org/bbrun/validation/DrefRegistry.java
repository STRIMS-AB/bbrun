package org.bbrun.validation;

import com.alibaba.fastjson2.JSON;
import dev.dref.DrefDomain;
import dev.dref.Formats;
import dev.dref.DomainModel;
import dev.dref.MappingDefinition;
import dev.regl.ValidationError;
import dev.regl.ValidationResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class DrefRegistry {

    private final Map<String, DomainModel> models = new HashMap<>();
    private final Map<String, dev.regl.Schema> reglSchemas = new HashMap<>();
    private final List<InlineMapping> inlineMappings = new ArrayList<>();
    private Formats formats = Formats.ISO;

    /**
     * A mapping declared inline on a domain model through {@code mapsFrom}. The
     * enclosing model supplies the target, which the definition itself omits.
     */
    public record InlineMapping(String model, String domain, MappingDefinition definition) {}

    public List<InlineMapping> inlineMappings() {
        return List.copyOf(inlineMappings);
    }

    public void register(DomainModel model) {
        if (model != null && model.name() != null) {
            models.put(model.name(), model);
        }
    }

    public void registerReglSchema(String name, dev.regl.Schema schema) {
        if (name != null && schema != null) {
            reglSchemas.put(name, schema);
        }
    }

    public DomainModel getModel(String modelName) {
        return models.get(modelName);
    }
    
    public dev.regl.Schema getReglSchema(String schemaName) {
        return reglSchemas.get(schemaName);
    }

    public boolean hasModel(String modelName) {
        return models.containsKey(modelName) || reglSchemas.containsKey(modelName);
    }

    public Map<String, DomainModel> getAllModels() {
        return Collections.unmodifiableMap(models);
    }

    /**
     * The domain-wide formats parsed from the loaded document's
     * {@code defaults.formats} (falls back to {@link Formats#ISO} when the document
     * declares none). Future domain-level defaults are parsed by DRef itself and
     * are not hand-picked here.
     */
    public Formats formats() {
        return formats;
    }

    public void loadFromFile(Path filePath) {
        if (!Files.exists(filePath)) return;
        try {
            String json = Files.readString(filePath);
            parseAndRegister(json);
        } catch (IOException e) {
            System.err.println("Warning: Failed to load DRef models from " + filePath + ": " + e.getMessage());
        }
    }

    /**
     * Registers every model in a DRef document. Accepts a full domain document,
     * a single model, or an array of models; all three are normalised into a
     * domain envelope and handed to DRef, which owns every parsing rule (field
     * types, named field types, rules, scopes, mappings, authority, ttl).
     */
    public void parseAndRegister(String json) {
        try {
            load(json);
        } catch (IllegalArgumentException e) {
            System.err.println("Warning: " + e.getMessage());
        }
    }

    /**
     * Registers every model of a DRef document, like {@link #parseAndRegister}, but a
     * document that cannot be read throws {@link IllegalArgumentException} with DRef's own
     * message and registers nothing: for a caller that must refuse such a document rather
     * than go on with no models.
     */
    public void load(String json) {
        Object parsed;
        try {
            parsed = JSON.parse(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON: " + e.getMessage(), e);
        }

        if (parsed instanceof Map<?, ?> map && map.containsKey("fields")) {
            try {
                dev.regl.cli.SchemaFile.LoadedSchema loaded = dev.regl.cli.SchemaFile.fromTree(map);
                String schemaName = map.containsKey("name") ? String.valueOf(map.get("name")) : "regl-schema";
                registerReglSchema(schemaName, loaded.schema());
                return;
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to parse pure regl schema document: " + e.getMessage(), e);
            }
        }

        Map<String, Object> envelope = toDomainEnvelope(parsed);
        if (envelope == null) {
            throw new IllegalArgumentException("Skipping DRef document without a 'name'");
        }

        DrefDomain domain;
        try {
            domain = DrefDomain.from(envelope);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse DRef domain document: " + e.getMessage(), e);
        }
        reportDiagnostics(domain);
        this.formats = domain.formats();
        for (DomainModel model : domain.models()) {
            register(model);
            for (MappingDefinition definition : model.mapsFrom()) {
                inlineMappings.add(new InlineMapping(model.name(), domain.name(), definition));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toDomainEnvelope(Object parsed) {
        if (parsed instanceof List<?> list) {
            return envelope("bbrun-models", list);
        }
        if (!(parsed instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        // A full DRef domain document carries defaults/scopes/models at the
        // top level. Anything else that names a model is a single-model
        // document, which DRef parses through the same rules once wrapped.
        boolean isDomainDocument = map.get("models") instanceof List<?>
                || map.containsKey("scopes")
                || map.containsKey("defaults");
        if (isDomainDocument) {
            return map;
        }
        if (map.get("name") == null) {
            return null;
        }
        return envelope(String.valueOf(map.get("name")), List.of(map));
    }

    private static Map<String, Object> envelope(String name, List<?> modelMaps) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("name", name);
        envelope.put("models", modelMaps);
        return envelope;
    }

    /**
     * Runs DRef's own domain checks and reports anything it finds. A document
     * that parses but does not validate (a duplicate model, an entity without a
     * key, a rule referencing an unknown sibling property) is still
     * registered, so scripts keep working while the defect is surfaced.
     */
    private void reportDiagnostics(DrefDomain domain) {
        ValidationResult result;
        try {
            result = domain.validate();
        } catch (RuntimeException e) {
            System.err.println("Warning: Failed to validate DRef domain '" + domain.name() + "': " + e.getMessage());
            return;
        }
        for (ValidationError error : result.errors()) {
            System.err.println("Warning: DRef domain '" + domain.name() + "' has an error in '"
                    + error.field() + "' (" + error.rule() + "): " + error.message());
        }
        for (ValidationError warning : result.warnings()) {
            System.err.println("Warning: DRef domain '" + domain.name() + "' warns in '"
                    + warning.field() + "' (" + warning.rule() + "): " + warning.message());
        }
    }
}
