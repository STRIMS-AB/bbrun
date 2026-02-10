package org.bbrun.ast;

import java.util.List;
import java.util.Map;

/** Path node for URL construction. */
public record PathNode(List<PathSegment> segments, Map<String, ExpressionNode> queryParams, int line) {
    public sealed interface PathSegment permits LiteralSegment, InterpolatedSegment {
    }

    public record LiteralSegment(String value) implements PathSegment {
    }

    public record InterpolatedSegment(ExpressionNode expression) implements PathSegment {
    }
}
