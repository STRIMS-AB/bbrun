package org.bbrun.runtime;

import java.util.List;

/**
 * A service the host provides to scripts in Java, called like any resource:
 * {@code kfig.set("payments.limit", 500)}. The returned value becomes
 * {@code response.body}, with status 200. Throw {@link ServiceException} to answer
 * with another status; any other exception answers 500.
 */
@FunctionalInterface
public interface ServiceHandler {

    Object call(String operation, List<Object> args);

    /** A failed call: {@code response.status} is {@code status}, {@code response.body.error} the message. */
    final class ServiceException extends RuntimeException {
        private final int status;

        public ServiceException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
