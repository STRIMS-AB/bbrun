package org.bbrun.ast;

/** Base sealed interface for auth statement nodes. */
public sealed interface AuthNode extends StatementNode permits
        BearerAuthNode, BasicAuthNode, ApiKeyAuthNode, NamedAuthNode {
}
