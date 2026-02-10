package org.bbrun.ast;

public record NamedAuthNode(String name, AuthNode auth, int line) implements AuthNode {
}
