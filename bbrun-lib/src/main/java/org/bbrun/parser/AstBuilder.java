package org.bbrun.parser;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import org.bbrun.BBRunException;
import org.bbrun.ast.*;

import java.util.*;

/**
 * Builds AST from ANTLR parse tree.
 */
public class AstBuilder extends BBRunBaseVisitor<Object> {

    private final String sourcePath;

    public AstBuilder(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    /**
     * Parse source code and build AST.
     */
    public static ScriptNode parse(String source, String sourcePath) {
        CharStream input = CharStreams.fromString(source);
        BBRunLexer lexer = new BBRunLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        BBRunParser parser = new BBRunParser(tokens);

        // Custom error handling
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                    int line, int charPositionInLine, String msg,
                    RecognitionException e) {
                throw new BBRunException("Syntax error: " + msg, line, sourcePath);
            }
        });

        BBRunParser.ScriptContext scriptCtx = parser.script();
        AstBuilder builder = new AstBuilder(sourcePath);
        return builder.visitScript(scriptCtx);
    }

    @Override
    public ScriptNode visitScript(BBRunParser.ScriptContext ctx) {
        List<StatementNode> statements = new ArrayList<>();

        for (BBRunParser.StatementContext stmtCtx : ctx.statement()) {
            Object result = visit(stmtCtx);
            if (result instanceof StatementNode stmt) {
                statements.add(stmt);
            }
        }

        return new ScriptNode(sourcePath, statements);
    }

    // ========== Statements ==========

    @Override
    public Object visitBaseUrlStatement(BBRunParser.BaseUrlStatementContext ctx) {
        String url;
        if (ctx.STRING() != null) {
            url = stripQuotes(ctx.STRING().getText());
        } else if (ctx.functionCall() != null) {
            // Handle env() function call - evaluate later
            url = ctx.functionCall().getText();
        } else {
            url = "";
        }
        return new BaseUrlNode(url, ctx.getStart().getLine());
    }

    @Override
    public Object visitVariableDecl(BBRunParser.VariableDeclContext ctx) {
        String name = ctx.IDENTIFIER().getText();
        ExpressionNode value = (ExpressionNode) visit(ctx.expression());
        return new VariableNode(name, value, ctx.getStart().getLine());
    }

    @Override
    public Object visitRequestStatement(BBRunParser.RequestStatementContext ctx) {
        String method;
        if (ctx.httpVerb() != null) {
            method = ctx.httpVerb().getText().toUpperCase();
        } else if (ctx.getStart().getText().equals("delete")) {
            method = "DELETE";
        } else {
            method = "GET";
        }

        PathNode path = (PathNode) visit(ctx.path());

        ExpressionNode body = null;
        // Check for body (expression before 'to')
        if (ctx.expression() != null) {
            body = (ExpressionNode) visit(ctx.expression());
        }

        AuthClauseNode authClause = null;
        if (ctx.authClause() != null) {
            authClause = (AuthClauseNode) visit(ctx.authClause());
        }

        return new RequestNode(method, path, body, authClause, ctx.getStart().getLine());
    }

    @Override
    public Object visitPath(BBRunParser.PathContext ctx) {
        if (ctx.STRING() != null) {
            // Full URL as string
            String url = stripQuotes(ctx.STRING().getText());
            return new PathNode(
                    List.of(new PathNode.LiteralSegment(url)),
                    Map.of(),
                    ctx.getStart().getLine());
        }

        // Path segments: #users/${id}/posts
        List<PathNode.PathSegment> segments = new ArrayList<>();
        for (BBRunParser.PathSegmentContext segCtx : ctx.pathSegment()) {
            segments.add(visitPathSegment(segCtx));
        }

        // Query params
        Map<String, ExpressionNode> queryParams = new LinkedHashMap<>();
        if (ctx.queryParams() != null) {
            for (BBRunParser.QueryParamContext qp : ctx.queryParams().queryParam()) {
                String key = qp.IDENTIFIER(0).getText();
                ExpressionNode value;
                if (qp.STRING() != null) {
                    value = new LiteralNode(stripQuotes(qp.STRING().getText()), LiteralNode.LiteralType.STRING);
                } else if (qp.interpolation() != null) {
                    value = (ExpressionNode) visit(qp.interpolation().expression());
                } else if (qp.IDENTIFIER().size() > 1) {
                    value = new IdentifierNode(qp.IDENTIFIER(1).getText());
                } else {
                    value = new LiteralNode("", LiteralNode.LiteralType.STRING);
                }
                queryParams.put(key, value);
            }
        }

        return new PathNode(segments, queryParams, ctx.getStart().getLine());
    }

    public PathNode.PathSegment visitPathSegment(BBRunParser.PathSegmentContext ctx) {
        if (ctx.interpolation() != null) {
            ExpressionNode expr = (ExpressionNode) visit(ctx.interpolation().expression());
            return new PathNode.InterpolatedSegment(expr);
        }
        // Could be IDENTIFIER or mix
        StringBuilder sb = new StringBuilder();
        for (ParseTree child : ctx.children) {
            if (child instanceof TerminalNode tn) {
                sb.append(tn.getText());
            }
        }
        return new PathNode.LiteralSegment(sb.toString());
    }

    @Override
    public Object visitAssertStatement(BBRunParser.AssertStatementContext ctx) {
        ExpressionNode condition = (ExpressionNode) visit(ctx.expression());
        String message = ctx.STRING() != null ? stripQuotes(ctx.STRING().getText()) : null;
        return new AssertNode(condition, message, ctx.getStart().getLine());
    }

    @Override
    public Object visitWarnStatement(BBRunParser.WarnStatementContext ctx) {
        ExpressionNode condition = (ExpressionNode) visit(ctx.expression());
        String message = ctx.STRING() != null ? stripQuotes(ctx.STRING().getText()) : null;
        return new WarnNode(condition, message, ctx.getStart().getLine());
    }

    @Override
    public Object visitPrintStatement(BBRunParser.PrintStatementContext ctx) {
        ExpressionNode message = (ExpressionNode) visit(ctx.expression());
        return new PrintNode(message, ctx.getStart().getLine());
    }

    @Override
    public Object visitIfStatement(BBRunParser.IfStatementContext ctx) {
        ExpressionNode condition = (ExpressionNode) visit(ctx.expression(0));
        List<StatementNode> thenBlock = parseBlock(ctx.block(0));

        List<IfNode.ElseIfClause> elseIfClauses = new ArrayList<>();
        // Handle else if clauses (expression index 1+, block index 1+)
        int exprIdx = 1;
        int blockIdx = 1;
        for (int i = 0; i < ctx.getChildCount(); i++) {
            ParseTree child = ctx.getChild(i);
            if (child.getText().equals("else") && i + 1 < ctx.getChildCount()
                    && ctx.getChild(i + 1).getText().equals("if")) {
                ExpressionNode elseIfCond = (ExpressionNode) visit(ctx.expression(exprIdx++));
                List<StatementNode> elseIfBlock = parseBlock(ctx.block(blockIdx++));
                elseIfClauses.add(new IfNode.ElseIfClause(elseIfCond, elseIfBlock));
                i++; // skip "if"
            }
        }

        List<StatementNode> elseBlock = null;
        if (blockIdx < ctx.block().size()) {
            elseBlock = parseBlock(ctx.block(blockIdx));
        }

        return new IfNode(condition, thenBlock, elseIfClauses, elseBlock, ctx.getStart().getLine());
    }

    @Override
    public Object visitRepeatStatement(BBRunParser.RepeatStatementContext ctx) {
        ExpressionNode count = (ExpressionNode) visit(ctx.expression());
        List<StatementNode> body = parseBlock(ctx.block());
        return new RepeatNode(count, body, ctx.getStart().getLine());
    }

    private List<StatementNode> parseBlock(BBRunParser.BlockContext ctx) {
        List<StatementNode> statements = new ArrayList<>();
        for (BBRunParser.StatementContext stmtCtx : ctx.statement()) {
            Object result = visit(stmtCtx);
            if (result instanceof StatementNode) {
                statements.add((StatementNode) result);
            }
        }
        return statements;
    }

    // ========== Expressions ==========

    @Override
    public Object visitExpression(BBRunParser.ExpressionContext ctx) {
        // Handle different expression types based on context structure
        if (ctx.primary() != null) {
            return visit(ctx.primary());
        }

        // Member access: expr.member
        if (ctx.DOT() != null) {
            ExpressionNode obj = (ExpressionNode) visit(ctx.expression(0));
            String member = ctx.IDENTIFIER().getText();
            return new MemberAccessNode(obj, member);
        }

        // Index access: expr[index]
        if (ctx.LBRACK() != null && ctx.RBRACK() != null) {
            ExpressionNode obj = (ExpressionNode) visit(ctx.expression(0));
            ExpressionNode index = (ExpressionNode) visit(ctx.expression(1));
            return new IndexAccessNode(obj, index);
        }

        // Function call: expr(args)
        if (ctx.LPAREN() != null && ctx.RPAREN() != null && ctx.expression().size() >= 1) {
            ExpressionNode callee = (ExpressionNode) visit(ctx.expression(0));
            if (callee instanceof IdentifierNode id) {
                List<ExpressionNode> args = new ArrayList<>();
                if (ctx.argumentList() != null) {
                    for (BBRunParser.ExpressionContext argCtx : ctx.argumentList().expression()) {
                        args.add((ExpressionNode) visit(argCtx));
                    }
                }
                return new FunctionCallNode(id.name(), args);
            }
        }

        // Binary comparisons
        if (ctx.EQ() != null || ctx.NE() != null || ctx.LT() != null ||
                ctx.GT() != null || ctx.LE() != null || ctx.GE() != null) {
            ExpressionNode left = (ExpressionNode) visit(ctx.expression(0));
            ExpressionNode right = (ExpressionNode) visit(ctx.expression(1));
            String op = getOperator(ctx);
            return new BinaryOpNode(left, op, right);
        }

        // 'is' type/format check
        if (ctx.IS() != null) {
            ExpressionNode expr = (ExpressionNode) visit(ctx.expression(0));
            BBRunParser.TypeOrFormatContext typeCtx = ctx.typeOrFormat();
            if (typeCtx.STRING() != null) {
                return new IsCheckNode(expr, stripQuotes(typeCtx.STRING().getText()), true);
            }
            return new IsCheckNode(expr, typeCtx.getText(), false);
        }

        // 'contains' check
        if (ctx.CONTAINS() != null) {
            ExpressionNode container = (ExpressionNode) visit(ctx.expression(0));
            ExpressionNode item = (ExpressionNode) visit(ctx.expression(1));
            boolean negated = ctx.NOT() != null;
            return new ContainsNode(container, item, negated, ContainsNode.ContainsMode.ITEM);
        }

        // Logical operators
        if (ctx.AND() != null) {
            ExpressionNode left = (ExpressionNode) visit(ctx.expression(0));
            ExpressionNode right = (ExpressionNode) visit(ctx.expression(1));
            return new BinaryOpNode(left, "and", right);
        }
        if (ctx.OR() != null) {
            ExpressionNode left = (ExpressionNode) visit(ctx.expression(0));
            ExpressionNode right = (ExpressionNode) visit(ctx.expression(1));
            return new BinaryOpNode(left, "or", right);
        }

        // Arithmetic
        if (ctx.PLUS() != null || ctx.MINUS() != null || ctx.STAR() != null || ctx.SLASH() != null) {
            ExpressionNode left = (ExpressionNode) visit(ctx.expression(0));
            ExpressionNode right = (ExpressionNode) visit(ctx.expression(1));
            String op = getOperator(ctx);
            return new BinaryOpNode(left, op, right);
        }

        // Unary not
        if (ctx.NOT() != null && ctx.expression().size() == 1) {
            ExpressionNode operand = (ExpressionNode) visit(ctx.expression(0));
            return new UnaryOpNode("not", operand);
        }

        // Fallback
        if (!ctx.expression().isEmpty()) {
            return visit(ctx.expression(0));
        }

        throw new BBRunException("Unknown expression type", ctx.getStart().getLine(), sourcePath);
    }

    @Override
    public Object visitPrimary(BBRunParser.PrimaryContext ctx) {
        if (ctx.NUMBER() != null) {
            String numStr = ctx.NUMBER().getText();
            if (numStr.contains(".")) {
                return new LiteralNode(Double.parseDouble(numStr), LiteralNode.LiteralType.NUMBER);
            }
            return new LiteralNode(Long.parseLong(numStr), LiteralNode.LiteralType.NUMBER);
        }

        if (ctx.STRING() != null) {
            return new LiteralNode(stripQuotes(ctx.STRING().getText()), LiteralNode.LiteralType.STRING);
        }

        if (ctx.TRUE() != null) {
            return new LiteralNode(true, LiteralNode.LiteralType.BOOLEAN);
        }
        if (ctx.FALSE() != null) {
            return new LiteralNode(false, LiteralNode.LiteralType.BOOLEAN);
        }
        if (ctx.NULL() != null) {
            return new LiteralNode(null, LiteralNode.LiteralType.NULL);
        }

        if (ctx.IDENTIFIER() != null) {
            return new IdentifierNode(ctx.IDENTIFIER().getText());
        }

        // Built-in objects
        if (ctx.RESPONSE() != null)
            return new IdentifierNode("response");
        if (ctx.PARAMS() != null)
            return new IdentifierNode("params");
        if (ctx.THREAD() != null)
            return new IdentifierNode("thread");
        if (ctx.STATS() != null)
            return new IdentifierNode("stats");
        if (ctx.TIMING() != null)
            return new IdentifierNode("timing");
        if (ctx.METRICS() != null)
            return new IdentifierNode("metrics");

        if (ctx.objectLiteral() != null) {
            return visit(ctx.objectLiteral());
        }

        if (ctx.arrayLiteral() != null) {
            return visit(ctx.arrayLiteral());
        }

        if (ctx.functionCall() != null) {
            return visit(ctx.functionCall());
        }

        // Parenthesized expression
        if (ctx.expression() != null) {
            return visit(ctx.expression());
        }

        throw new BBRunException("Unknown primary expression", ctx.getStart().getLine(), sourcePath);
    }

    @Override
    public Object visitObjectLiteral(BBRunParser.ObjectLiteralContext ctx) {
        Map<String, ExpressionNode> properties = new LinkedHashMap<>();
        for (BBRunParser.ObjectPropertyContext propCtx : ctx.objectProperty()) {
            String key;
            if (propCtx.STRING() != null) {
                key = stripQuotes(propCtx.STRING().getText());
            } else {
                key = propCtx.IDENTIFIER().getText();
            }
            ExpressionNode value = (ExpressionNode) visit(propCtx.expression());
            properties.put(key, value);
        }
        return new ObjectLiteralNode(properties);
    }

    @Override
    public Object visitArrayLiteral(BBRunParser.ArrayLiteralContext ctx) {
        List<ExpressionNode> elements = new ArrayList<>();
        for (BBRunParser.ExpressionContext exprCtx : ctx.expression()) {
            elements.add((ExpressionNode) visit(exprCtx));
        }
        return new ArrayLiteralNode(elements);
    }

    @Override
    public Object visitFunctionCall(BBRunParser.FunctionCallContext ctx) {
        String name = ctx.functionName().getText();
        List<ExpressionNode> args = new ArrayList<>();
        if (ctx.argumentList() != null) {
            for (BBRunParser.ExpressionContext argCtx : ctx.argumentList().expression()) {
                args.add((ExpressionNode) visit(argCtx));
            }
        }
        return new FunctionCallNode(name, args);
    }

    // ========== Helpers ==========

    private String stripQuotes(String s) {
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private String getOperator(BBRunParser.ExpressionContext ctx) {
        if (ctx.EQ() != null)
            return "==";
        if (ctx.NE() != null)
            return "!=";
        if (ctx.LT() != null)
            return "<";
        if (ctx.GT() != null)
            return ">";
        if (ctx.LE() != null)
            return "<=";
        if (ctx.GE() != null)
            return ">=";
        if (ctx.PLUS() != null)
            return "+";
        if (ctx.MINUS() != null)
            return "-";
        if (ctx.STAR() != null)
            return "*";
        if (ctx.SLASH() != null)
            return "/";
        return "?";
    }
}
