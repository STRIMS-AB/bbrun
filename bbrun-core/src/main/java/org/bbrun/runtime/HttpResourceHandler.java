package org.bbrun.runtime;

import org.bbrun.config.ResourceDefinition;
import dev.iconn.api.http.HttpResource;
import dev.iconn.connector.http.RealHttpConnector;

import java.util.List;
import java.util.Map;

/**
 * HTTP resource handler backed by iconn's {@link RealHttpConnector}.
 */
public class HttpResourceHandler implements ResourceHandler {

    private final ResourceDefinition definition;
    private IconnResourceHandler delegate;

    public HttpResourceHandler(ResourceDefinition definition) {
        this.definition = definition;
    }

    public HttpResourceHandler(ResourceDefinition definition, HttpResource httpResource) {
        this.definition = definition;
        this.delegate = new IconnResourceHandler(definition, httpResource);
    }

    private synchronized IconnResourceHandler getDelegate() {
        if (delegate == null) {
            dev.iconn.api.ResourceDefinition iconnDef = definition.toIconnDefinition(null);
            RealHttpConnector connector = new RealHttpConnector();
            HttpResource httpResource = connector.start(iconnDef);
            this.delegate = new IconnResourceHandler(definition, httpResource);
        }
        return delegate;
    }

    @Override
    public ResourceDefinition getDefinition() {
        return definition;
    }

    @Override
    public ResponseObject execute(String operation, List<Object> args, Map<String, String> customHeaders) {
        return getDelegate().execute(operation, args, customHeaders);
    }
}