package org.bbrun.ast;

/** Base URL configuration statement. */
public record BaseUrlNode(String url, int line) implements StatementNode {
}
