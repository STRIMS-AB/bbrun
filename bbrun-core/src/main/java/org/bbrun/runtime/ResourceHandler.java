package org.bbrun.runtime;

import org.bbrun.config.ResourceDefinition;

import java.util.List;
import java.util.Map;

public interface ResourceHandler {
    ResourceDefinition getDefinition();
    ResponseObject execute(String operation, List<Object> args, Map<String, String> customHeaders);
}
