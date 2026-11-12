package org.bbrun.validation;

import dev.dref.DomainModel;
import dev.dref.Formats;
import dev.dref.MappingDefinition;

import java.util.*;

/**
 * Resolves the DRef mapping definitions a domain's models declare via
 * {@code mapsFrom}, and binds them to the resources whose responses they
 * translate.
 *
 * <p>The mapping itself belongs to DRef: {@link DrefDomain#parseMapping} builds
 * the definition, {@link DrefValidator#validateMappingDefinition} rules on it, and
 * {@code DrefMapper} runs it. bbrun adds only the orchestration edges — a resource
 * names the mapping that feeds it, and a mapping is reachable by name. A mapping
 * is declared in the model it imports onto, so that model is also what it
 * produces; bbrun never gets to choose a target.
 */
public class MappingRegistry {

    private final Map<String, Mapping> mappings = new HashMap<>();
    private final MappingEngine engine = new MappingEngine();

    /** Resource name (lowercased) to the mapping bound to it by config. */
    private final Map<String, String> resourceBindings = new HashMap<>();

    /**
     * Supplies the target model and the domain-wide formats used to shape a
     * mapping's output. Optional: without it, mappings apply without a target
     * model and with DRef's ISO formats.
     */
    private DrefRegistry drefRegistry;

    public void setDrefRegistry(DrefRegistry drefRegistry) {
        this.drefRegistry = drefRegistry;
    }

    /**
     * Registers the mappings a domain document's models declare via
     * {@code mapsFrom}. Call once the DRef registry is linked and loaded.
     */
    public void registerInlineMappings() {
        if (drefRegistry == null) return;
        for (DrefRegistry.InlineMapping inline : drefRegistry.inlineMappings()) {
            Mapping mapping = Mapping.from(inline.definition(), inline.model(), inline.domain());
            register(mapping);
        }
    }

    /**
     * Binds a resource to the mapping that translates its inbound responses, as
     * named by {@code mapping:} in the resource's config. The mapping must already
     * be registered — that is, some model's {@code mapsFrom} must declare it.
     * A binding that names nothing registered is reported and ignored, so a typo
     * surfaces at startup instead of silently leaving the response unmapped.
     */
    public void bindResource(String resourceName, String mappingName) {
        if (resourceName == null || resourceName.isBlank()
                || mappingName == null || mappingName.isBlank()) {
            return;
        }
        if (resolve(mappingName) == null) {
            System.err.println("Warning: Resource '" + resourceName + "' is bound to mapping '"
                    + mappingName + "', which no model declares with 'mapsFrom'. "
                    + "Known mappings: " + availableNames());
            return;
        }
        resourceBindings.put(resourceName.toLowerCase(Locale.ROOT), mappingName);
    }

    /**
     * The mapping that translates the raw response of {@code resourceName} into
     * {@code modelName}. A resource bound to exactly one mapping uses that mapping
     * for every model it feeds; otherwise a mapping targeting {@code modelName}
     * is looked up by name.
     */
    public Mapping findMappingFor(String resourceName, String modelName) {
        if (resourceName == null) return null;
        String bound = resourceBindings.get(resourceName.toLowerCase(Locale.ROOT));
        if (bound == null) return null;
        Mapping mapping = resolve(bound);
        if (mapping == null) return null;
        if (modelName == null) return mapping;
        String target = mapping.targetModel();
        return target != null && normalize(target).equals(normalize(modelName)) ? mapping : null;
    }

    public Map<String, Object> apply(Mapping mapping, Object source) {
        return engine.apply(mapping, source, targetModelFor(mapping), formats());
    }

    private DomainModel targetModelFor(Mapping mapping) {
        if (drefRegistry == null || mapping == null || mapping.targetModel() == null) return null;
        return drefRegistry.getModel(mapping.targetModel());
    }

    private Formats formats() {
        return drefRegistry != null ? drefRegistry.formats() : Formats.ISO;
    }

    public void register(Mapping mapping) {
        if (mapping == null || mapping.name() == null || mapping.name().isBlank()) return;
        mappings.put(mapping.name().toLowerCase(Locale.ROOT), mapping);
        mappings.putIfAbsent(normalize(mapping.name()), mapping);
        if (mapping.targetModel() != null && !mapping.targetModel().isBlank()) {
            mappings.putIfAbsent("model:" + mapping.targetModel().toLowerCase(Locale.ROOT), mapping);
            mappings.putIfAbsent("model:" + normalize(mapping.targetModel()), mapping);
        }
    }

    public boolean hasMapping(String key) {
        return resolve(key) != null;
    }

    public Mapping getMapping(String key) {
        Mapping mapping = resolve(key);
        if (mapping == null) {
            throw new MappingException("Unknown DRef mapping: '" + key + "'. Available: " + availableNames());
        }
        return mapping;
    }

    private Mapping resolve(String key) {
        if (key == null) return null;
        Mapping mapping = mappings.get(key.toLowerCase(Locale.ROOT));
        if (mapping != null) return mapping;
        Mapping modelMapping = mappings.get("model:" + normalize(key));
        if (modelMapping != null) return modelMapping;
        String normalized = normalize(key);
        return mappings.get(normalized);
    }

    public Map<String, Object> apply(String key, Object source) {
        return engine.apply(getMapping(key), source);
    }

    public MappingEngine getEngine() {
        return engine;
    }

    public Collection<Mapping> getAllMappings() {
        return Collections.unmodifiableCollection(
                new LinkedHashSet<>(mappings.values().stream()
                        .filter(m -> !m.name().startsWith("model:"))
                        .toList()));
    }

    private List<String> availableNames() {
        return new ArrayList<>(new LinkedHashSet<>(mappings.keySet().stream()
                .filter(k -> !k.startsWith("model:"))
                .toList()));
    }

    private String normalize(String s) {
        return s == null ? "" : s.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }
}
