package org.bbrun.validation;

import com.alibaba.fastjson2.JSON;
import dev.dref.DomainModel;
import dev.dref.DrefMapper;
import dev.dref.Formats;
import dev.dref.MappingResult;
import dev.regl.ValidationError;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies a {@link Mapping} document to a source payload, producing an object
 * shaped like our (target) domain model. Execution is delegated to DRef's own
 * mapper, so transform chains and multi-source fields follow the DRef catalog
 * exactly. The output format of date transforms is taken from the target
 * property's type and the domain's {@code defaults.formats}.
 */
public class MappingEngine {

    public Map<String, Object> apply(Mapping mapping, Object source) {
        return apply(mapping, source, null, null);
    }

    public Map<String, Object> apply(Mapping mapping, Object source, DomainModel targetModel, Formats domainFormats) {
        MappingResult result = DrefMapper.map(toMap(source), mapping.definition(), null, targetModel, domainFormats);
        if (!result.isValid()) {
            throw new MappingException("Mapping '" + mapping.name() + "' failed:\n  "
                    + String.join("\n  ", result.errors().stream()
                    .map(MappingEngine::describe)
                    .toList()));
        }
        return new LinkedHashMap<>(result.target());
    }

    private static String describe(ValidationError error) {
        return "✖ [" + error.field() + "] (" + error.rule() + "): " + error.message();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object source) {
        if (source instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (source instanceof String s) {
            return JSON.parseObject(s, Map.class);
        }
        if (source != null) {
            return JSON.parseObject(JSON.toJSONString(source), Map.class);
        }
        return new LinkedHashMap<>();
    }
}
