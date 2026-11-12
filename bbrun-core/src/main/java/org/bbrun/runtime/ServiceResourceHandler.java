package org.bbrun.runtime;

import org.bbrun.config.ResourceDefinition;

import java.util.List;
import java.util.Map;

/** Adapts a host {@link ServiceHandler} to the resource interface scripts call. */
final class ServiceResourceHandler implements ResourceHandler {

    private final ResourceDefinition definition;
    private final ServiceHandler service;

    ServiceResourceHandler(String name, ServiceHandler service) {
        this.definition = new ResourceDefinition(name, "service", null, Map.of(), null);
        this.service = service;
    }

    @Override
    public ResourceDefinition getDefinition() {
        return definition;
    }

    @Override
    public ResponseObject execute(String operation, List<Object> args, Map<String, String> customHeaders) {
        long start = System.nanoTime();
        int status;
        Object body;
        // A refusal answers {"message": ...}, as the platform's HTTP APIs do; an answer
        // with nothing to say is 204 with no body
        try {
            body = service.call(operation, args);
            status = body == null ? 204 : 200;
        } catch (ServiceHandler.ServiceException e) {
            status = e.status();
            body = Map.of("message", String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            status = 500;
            body = Map.of("message", String.valueOf(e.getMessage()));
        }
        return new ResponseObject(status, Map.of(), body, (System.nanoTime() - start) / 1_000_000);
    }
}
