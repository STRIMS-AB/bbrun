package org.bbrun.runtime;

/**
 * A request body that is not JSON: {@code form({...})} (a URL-encoded form) or {@code file("x.svg")}
 * (a file's text). It is sent as it is, with its content type.
 */
public record RequestBody(String content, String contentType) {
}
