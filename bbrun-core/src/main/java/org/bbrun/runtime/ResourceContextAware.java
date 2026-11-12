package org.bbrun.runtime;

/**
 * Handlers that need the enclosing {@link ResourceContext} (mocks of resources
 * can chain calls to other configured resources).
 */
public interface ResourceContextAware {

    void attachResourceContext(ResourceContext context);
}
