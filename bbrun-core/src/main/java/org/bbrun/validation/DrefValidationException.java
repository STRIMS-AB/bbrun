package org.bbrun.validation;

import dev.regl.ValidationError;

import java.util.List;

public class DrefValidationException extends RuntimeException {

    private final String modelName;
    private final List<ValidationError> errors;

    public DrefValidationException(String modelName, List<ValidationError> errors) {
        super(formatMessage(modelName, errors));
        this.modelName = modelName;
        this.errors = errors != null ? List.copyOf(errors) : List.of();
    }

    public String getModelName() {
        return modelName;
    }

    public List<ValidationError> getErrors() {
        return errors;
    }

    private static String formatMessage(String modelName, List<ValidationError> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("Validation failed against DRef model '").append(modelName).append("':\n");
        if (errors == null || errors.isEmpty()) {
            sb.append("  - Unknown validation failure");
        } else {
            for (ValidationError err : errors) {
                sb.append("  ✖ [").append(err.field()).append("] (").append(err.rule()).append("): ")
                  .append(err.message()).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
