package org.bbrun.validation;

import dev.dref.FieldMapping;
import dev.dref.MappingDefinition;
import dev.dref.Transform;
import dev.dref.TransformType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A DRef mapping, as bbrun uses it. The mapping itself — its name, provider,
 * version, description and field rules — is a DRef
 * {@link MappingDefinition} that DRef parsed and checked; bbrun keeps the
 * flattened {@link MappingField} view for display, and records which model
 * declared the mapping, since that model is what the mapping produces.
 * <p>
 * Validating the incoming object against its own (provider) model document is
 * not part of the mapping.
 */
public record Mapping(
        String name,
        String provider,
        String version,
        String description,
        String targetModel,
        String domain,
        List<MappingField> fields,
        MappingDefinition definition
) {

    private static final String SUPPORTED_TRANSFORMS = Arrays.stream(TransformType.values())
            .map(TransformType::code)
            .collect(Collectors.joining(", "));

    public Mapping(String name, String provider, String version, String description,
                   String targetModel, String domain, List<MappingField> fields) {
        this(name, provider, version, description, targetModel, domain, fields, null);
    }

    /**
     * A single source-to-target rule. Either {@code from} names one source
     * property, or {@code fromFields} names several whose present values are
     * collected into a list under {@code to}.
     */
    public record MappingField(
            String from,
            List<String> fromFields,
            String to,
            List<TransformStep> transform
    ) {
        public MappingField(String from, String to, List<TransformStep> transform) {
            this(from, List.of(), to, transform);
        }

        public MappingField {
            fromFields = fromFields != null ? List.copyOf(fromFields) : List.of();
            to = to != null ? to : from;
            transform = transform != null ? List.copyOf(transform) : List.of();
        }

        public boolean multiSource() {
            return !fromFields.isEmpty();
        }

        public List<String> sources() {
            return multiSource() ? fromFields : List.of(from);
        }

        public boolean identity() {
            return !multiSource() && from.equals(to) && transform.isEmpty();
        }
    }

    public record TransformStep(String type, Object param) {}

    public Mapping {
        fields = fields != null ? List.copyOf(fields) : List.of();
        if (definition == null) definition = build(name, provider, version, description, fields);
    }

    /**
     * This mapping as DRef sees it, so it runs through DRef's own mapper and the
     * canonical transform catalog. Built once, at load, so a malformed transform
     * parameter is reported when the document is read rather than when the first
     * response arrives.
     */
    public MappingDefinition definition() {
        return definition;
    }

    private static MappingDefinition build(String name, String provider, String version,
                                           String description, List<MappingField> fields) {
        MappingDefinition.Builder builder = MappingDefinition.builder(name)
                .provider(provider)
                .version(version)
                .description(description);
        for (MappingField field : fields) {
            List<Transform> transforms = field.transform().stream()
                    .map(Mapping::toTransform)
                    .toList();
            if (field.multiSource()) {
                FieldMapping.Builder multi = FieldMapping
                        .multiFrom(field.fromFields().toArray(String[]::new))
                        .to(field.to());
                transforms.forEach(multi::add);
                builder.field(multi.build());
            } else {
                builder.field(new FieldMapping(field.from(), field.to(), transforms));
            }
        }
        return builder.build();
    }

    private static Transform toTransform(TransformStep step) {
        if (step == null || step.type() == null || step.type().isBlank()) {
            throw new MappingException("Mapping transform step without a type");
        }
        try {
            return new Transform(TransformType.fromCode(step.type()), step.param());
        } catch (IllegalArgumentException e) {
            throw new MappingException("Unknown mapping transform: '" + step.type()
                    + "'. Supported: " + SUPPORTED_TRANSFORMS, e);
        }
    }

    /**
     * Wraps a mapping DRef parsed — either from a {@code mapsFrom} entry on a
     * domain model, or from a standalone document — and names the target model
     * it lands on. The enclosing model is the target for an inline mapping.
     */
    public static Mapping from(MappingDefinition definition, String targetModel, String domain) {
        List<MappingField> fields = new ArrayList<>();
        for (FieldMapping field : definition.fields()) {
            List<TransformStep> steps = field.transforms().stream()
                    .map(transform -> new TransformStep(transform.typeCode(), transform.param()))
                    .toList();
            if (field.isMultiSource()) {
                fields.add(new MappingField(null, field.sourceProperties(), field.targetProperty(), steps));
            } else {
                fields.add(new MappingField(field.from(), List.of(), field.to(), steps));
            }
        }
        return new Mapping(definition.name(), definition.provider(), definition.version(),
                definition.description(), targetModel, domain, fields, definition);
    }
}
