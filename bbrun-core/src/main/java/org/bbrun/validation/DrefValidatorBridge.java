package org.bbrun.validation;

import com.alibaba.fastjson2.JSON;
import dev.dref.DomainModel;
import dev.dref.DrefValidator;
import dev.regl.ValidationError;
import dev.regl.ValidationResult;

import java.util.Map;
import java.util.function.Consumer;

public class DrefValidatorBridge {

    private final DrefRegistry registry;
    private Consumer<ValidationError> warningSink = DrefValidatorBridge::reportToStderr;

    public DrefValidatorBridge(DrefRegistry registry) {
        this.registry = registry;
    }

    /**
     * Where violations of {@code warnOnly} rules are reported. They never
     * fail a validation, but they are surfaced here so a soft rule is not
     * silently dropped.
     */
    public void setWarningSink(Consumer<ValidationError> warningSink) {
        this.warningSink = warningSink != null ? warningSink : w -> { };
    }

    private static void reportToStderr(ValidationError warning) {
        System.err.println("Warning: DRef model '" + warning.field() + "' (" + warning.rule()
                + "): " + warning.message());
    }

    public DrefRegistry getRegistry() {
        return registry;
    }

    public boolean matches(Object target, String modelName) {
        ValidationResult result = validate(target, modelName);
        return result.isValid();
    }

    public void validateOrThrow(Object target, String modelName) {
        ValidationResult result = validate(target, modelName);
        if (!result.isValid()) {
            throw new DrefValidationException(modelName, result.errors());
        }
        result.warnings().forEach(warningSink);
    }

    @SuppressWarnings("unchecked")
    public ValidationResult validate(Object target, String modelName) {
        DomainModel model = registry.getModel(modelName);
        dev.regl.Schema reglSchema = registry.getReglSchema(modelName);
        
        if (model == null && reglSchema == null) {
            throw new IllegalArgumentException("Unknown DRef domain model or regl schema: '" + modelName + "'. Available models: " + registry.getAllModels().keySet());
        }

        Map<String, Object> dataMap;
        if (target instanceof Map<?, ?> m) {
            dataMap = (Map<String, Object>) m;
        } else if (target instanceof String s) {
            dataMap = JSON.parseObject(s, Map.class);
        } else if (target != null) {
            dataMap = JSON.parseObject(JSON.toJSONString(target), Map.class);
        } else {
            dataMap = null;
        }

        if (model != null) {
            return DrefValidator.validateInstance(dataMap, model, registry.formats());
        } else {
            if (dataMap == null) {
                return ValidationResult.invalid(java.util.List.of(new ValidationError(modelName, "required", null, "Instance data map cannot be null.")));
            }
            dev.regl.DocumentResult result = reglSchema.validate(dataMap);
            java.util.List<ValidationError> errors = new java.util.ArrayList<>();
            java.util.List<ValidationError> warnings = new java.util.ArrayList<>();
            for (dev.regl.DocumentFinding error : result.errors()) {
                errors.add(new ValidationError(error.path(), error.kind().name(), null, error.message()));
            }
            for (dev.regl.DocumentFinding warning : result.warnings()) {
                warnings.add(new ValidationError(warning.path(), warning.kind().name(), null, warning.message()));
            }
            return ValidationResult.of(errors, warnings);
        }
    }
}
