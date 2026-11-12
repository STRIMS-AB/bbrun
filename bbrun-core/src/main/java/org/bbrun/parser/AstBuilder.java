package org.bbrun.parser;

import org.bbrun.ast.*;
import org.bbrun.ast.Statements.*;
import org.bbrun.ast.Expressions.*;

import java.util.*;

public class AstBuilder extends BBRunBaseVisitor<Object> {

    @Override
    public ScriptNode visitScript(BBRunParser.ScriptContext ctx) {
        List<StatementNode> statements = new ArrayList<>();
        if (ctx.statement() != null) {
            for (BBRunParser.StatementContext stmtCtx : ctx.statement()) {
                Object stmt = visit(stmtCtx);
                if (stmt instanceof StatementNode s) {
                    statements.add(s);
                }
            }
        }
        return new ScriptNode(statements);
    }

    @Override
    public Object visitVariableDecl(BBRunParser.VariableDeclContext ctx) {
        String name = ctx.IDENTIFIER().getText();
        ExpressionNode value = (ExpressionNode) visit(ctx.expression());
        return new VariableNode(name, value, ctx.getStart().getLine());
    }

    @Override
    public Object visitTypedDeclaration(BBRunParser.TypedDeclarationContext ctx) {
        boolean array = ctx.LBRACKET() != null && ctx.RBRACKET() != null;
        String typeName = ctx.IDENTIFIER(0).getText();
        String name = ctx.IDENTIFIER(1).getText();
        ExpressionNode value = (ExpressionNode) visit(ctx.expression());
        return new TypedVariableDeclNode(typeName, array, name, value, ctx.getStart().getLine());
    }

    @Override
    public Object visitVerbRequestStatement(BBRunParser.VerbRequestStatementContext ctx) {
        String verb = ctx.httpVerb() != null ? ctx.httpVerb().getText() : "delete";
        String path = pathValue(ctx.path());
        String targetResource = ctx.IDENTIFIER() != null ? ctx.IDENTIFIER().getText() : null;
        ExpressionNode body = ctx.expression() != null ? (ExpressionNode) visit(ctx.expression()) : null;
        return new VerbRequestNode(verb, path, body, targetResource, ctx.getStart().getLine());
    }

    @Override
    public Object visitMapStatement(BBRunParser.MapStatementContext ctx) {
        ExpressionNode source = (ExpressionNode) visit(ctx.expression());
        String mappingKey = stripQuotes(ctx.STRING().getText());
        String varName = ctx.IDENTIFIER() != null ? ctx.IDENTIFIER().getText() : null;
        return new MapNode(source, mappingKey, varName, ctx.getStart().getLine());
    }

    @Override
    public Object visitValidateStatement(BBRunParser.ValidateStatementContext ctx) {
        ExpressionNode target = (ExpressionNode) visit(ctx.expression());
        String modelName = ctx.IDENTIFIER().getText();
        String message = ctx.STRING() != null ? stripQuotes(ctx.STRING().getText()) : null;
        return new ValidateNode(target, modelName, message, ctx.getStart().getLine());
    }

    @Override
    public Object visitAssertStatement(BBRunParser.AssertStatementContext ctx) {
        ExpressionNode condition = (ExpressionNode) visit(ctx.expression());
        String message = ctx.STRING() != null ? stripQuotes(ctx.STRING().getText()) : null;
        return new AssertNode(condition, message, ctx.getStart().getLine(), sourceText(ctx.expression()));
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
        List<StatementNode> thenBody = extractBlock(ctx.block(0));
        List<StatementNode> elseBody = ctx.block().size() > 1 ? extractBlock(ctx.block(1)) : List.of();
        return new IfNode(condition, thenBody, elseBody, ctx.getStart().getLine());
    }

    @Override
    public Object visitForRangeStatement(BBRunParser.ForRangeStatementContext ctx) {
        ExpressionNode start = (ExpressionNode) visit(ctx.expression(0));
        ExpressionNode end = (ExpressionNode) visit(ctx.expression(1));
        String varName = ctx.IDENTIFIER().getText();
        List<StatementNode> body = extractBlock(ctx.block());
        return new ForNode(varName, start, end, null, body, ctx.getStart().getLine());
    }

    @Override
    public Object visitForEachStatement(BBRunParser.ForEachStatementContext ctx) {
        ExpressionNode collection = (ExpressionNode) visit(ctx.expression());
        String varName = ctx.IDENTIFIER().getText();
        List<StatementNode> body = extractBlock(ctx.block());
        return new ForNode(varName, null, null, collection, body, ctx.getStart().getLine());
    }

    @Override
    public Object visitBreakStatement(BBRunParser.BreakStatementContext ctx) {
        return new BreakNode(ctx.getStart().getLine());
    }

    @Override
    public Object visitReturnStatement(BBRunParser.ReturnStatementContext ctx) {
        ExpressionNode value = ctx.expression() != null ? (ExpressionNode) visit(ctx.expression()) : null;
        return new ReturnNode(value, ctx.getStart().getLine());
    }

    @Override
    public Object visitAbortStatement(BBRunParser.AbortStatementContext ctx) {
        ExpressionNode message = ctx.expression() != null ? (ExpressionNode) visit(ctx.expression()) : null;
        return new AbortNode(message, ctx.getStart().getLine());
    }

    @Override
    public Object visitStatusStatement(BBRunParser.StatusStatementContext ctx) {
        ExpressionNode value = (ExpressionNode) visit(ctx.expression());
        return new SetStatusNode(value, ctx.getStart().getLine());
    }

    @Override
    public Object visitHeaderStatement(BBRunParser.HeaderStatementContext ctx) {
        ExpressionNode name = (ExpressionNode) visit(ctx.expression(0));
        ExpressionNode value = (ExpressionNode) visit(ctx.expression(1));
        return new SetHeaderNode(name, value, ctx.getStart().getLine());
    }

    @Override
    public Object visitGoalStatement(BBRunParser.GoalStatementContext ctx) {
        return new GoalNode((ExpressionNode) visit(ctx.expression()), ctx.getStart().getLine());
    }

    @Override
    public Object visitStepStatement(BBRunParser.StepStatementContext ctx) {
        return new StepNode((ExpressionNode) visit(ctx.expression()), extractBlock(ctx.block()),
                ctx.getStart().getLine());
    }

    @Override
    public Object visitCleanupStatement(BBRunParser.CleanupStatementContext ctx) {
        return new CleanupNode(extractBlock(ctx.block()), ctx.getStart().getLine());
    }

    @Override
    public Object visitIncludeStatement(BBRunParser.IncludeStatementContext ctx) {
        return new IncludeNode(stripQuotes(ctx.STRING().getText()), ctx.getStart().getLine());
    }

    @Override
    public Object visitExpressionStatement(BBRunParser.ExpressionStatementContext ctx) {
        ExpressionNode expr = (ExpressionNode) visit(ctx.expression());
        return new ExpressionStatementNode(expr, ctx.getStart().getLine());
    }

    private List<StatementNode> extractBlock(BBRunParser.BlockContext ctx) {
        if (ctx == null || ctx.statement() == null) return List.of();
        List<StatementNode> statements = new ArrayList<>();
        for (BBRunParser.StatementContext sCtx : ctx.statement()) {
            Object s = visit(sCtx);
            if (s instanceof StatementNode stmt) {
                statements.add(stmt);
            }
        }
        return statements;
    }

    // Expressions

    @Override
    public Object visitPrimaryExpr(BBRunParser.PrimaryExprContext ctx) {
        return visit(ctx.primary());
    }

    @Override
    public Object visitMemberAccessExpr(BBRunParser.MemberAccessExprContext ctx) {
        ExpressionNode obj = (ExpressionNode) visit(ctx.expression());
        String member = ctx.IDENTIFIER() != null ? ctx.IDENTIFIER().getText() : ctx.keyword().getText();
        return new MemberAccessNode(obj, member, ctx.getStart().getLine());
    }

    @Override
    public Object visitIndexAccessExpr(BBRunParser.IndexAccessExprContext ctx) {
        ExpressionNode obj = (ExpressionNode) visit(ctx.expression(0));
        ExpressionNode idx = (ExpressionNode) visit(ctx.expression(1));
        return new IndexAccessNode(obj, idx, ctx.getStart().getLine());
    }

    @Override
    public Object visitFunctionCallExpr(BBRunParser.FunctionCallExprContext ctx) {
        String name = ctx.IDENTIFIER().getText();
        List<ExpressionNode> args = extractArgs(ctx.argumentList());
        return new FunctionCallNode(name, args, ctx.getStart().getLine());
    }

    @Override
    public Object visitMethodCallExpr(BBRunParser.MethodCallExprContext ctx) {
        ExpressionNode obj = (ExpressionNode) visit(ctx.expression());
        String method = methodName(ctx);
        List<ExpressionNode> args = extractArgs(ctx.argumentList());
        return new MethodCallNode(obj, method, args, ctx.getStart().getLine());
    }

    private String methodName(BBRunParser.MethodCallExprContext ctx) {
        if (ctx.IDENTIFIER() != null) return ctx.IDENTIFIER().getText();
        if (ctx.httpVerb() != null) return ctx.httpVerb().getText();
        return ctx.keyword().getText();
    }

    @Override
    public Object visitRelationalExpr(BBRunParser.RelationalExprContext ctx) {
        ExpressionNode left = (ExpressionNode) visit(ctx.expression(0));
        ExpressionNode right = (ExpressionNode) visit(ctx.expression(1));
        String op = ctx.getChild(1).getText();
        return new BinaryOpNode(op, left, right, ctx.getStart().getLine());
    }

    @Override
    public Object visitMulDivExpr(BBRunParser.MulDivExprContext ctx) {
        ExpressionNode left = (ExpressionNode) visit(ctx.expression(0));
        ExpressionNode right = (ExpressionNode) visit(ctx.expression(1));
        String op = ctx.getChild(1).getText();
        return new BinaryOpNode(op, left, right, ctx.getStart().getLine());
    }

    @Override
    public Object visitAddSubExpr(BBRunParser.AddSubExprContext ctx) {
        ExpressionNode left = (ExpressionNode) visit(ctx.expression(0));
        ExpressionNode right = (ExpressionNode) visit(ctx.expression(1));
        String op = ctx.getChild(1).getText();
        return new BinaryOpNode(op, left, right, ctx.getStart().getLine());
    }

    @Override
    public Object visitAndExpr(BBRunParser.AndExprContext ctx) {
        ExpressionNode left = (ExpressionNode) visit(ctx.expression(0));
        ExpressionNode right = (ExpressionNode) visit(ctx.expression(1));
        return new BinaryOpNode("and", left, right, ctx.getStart().getLine());
    }

    @Override
    public Object visitOrExpr(BBRunParser.OrExprContext ctx) {
        ExpressionNode left = (ExpressionNode) visit(ctx.expression(0));
        ExpressionNode right = (ExpressionNode) visit(ctx.expression(1));
        return new BinaryOpNode("or", left, right, ctx.getStart().getLine());
    }

    @Override
    public Object visitMatchesModelExpr(BBRunParser.MatchesModelExprContext ctx) {
        ExpressionNode expr = (ExpressionNode) visit(ctx.expression());
        String model = ctx.IDENTIFIER().getText();
        return new MatchesModelNode(expr, model, ctx.getStart().getLine());
    }

    @Override
    public Object visitContainsExpr(BBRunParser.ContainsExprContext ctx) {
        ExpressionNode left = (ExpressionNode) visit(ctx.expression(0));
        ExpressionNode right = (ExpressionNode) visit(ctx.expression(1));
        return new ContainsNode(left, right, false, ctx.getStart().getLine());
    }

    @Override
    public Object visitNotContainsExpr(BBRunParser.NotContainsExprContext ctx) {
        ExpressionNode left = (ExpressionNode) visit(ctx.expression(0));
        ExpressionNode right = (ExpressionNode) visit(ctx.expression(1));
        return new ContainsNode(left, right, true, ctx.getStart().getLine());
    }

    @Override
    public Object visitNotExpr(BBRunParser.NotExprContext ctx) {
        ExpressionNode operand = (ExpressionNode) visit(ctx.expression());
        return new UnaryOpNode("not", operand, ctx.getStart().getLine());
    }

    // Primaries

    @Override
    public Object visitNumberLiteral(BBRunParser.NumberLiteralContext ctx) {
        String text = ctx.NUMBER().getText();
        if (text.contains(".")) {
            return new LiteralNode(Double.parseDouble(text), ctx.getStart().getLine());
        }
        return new LiteralNode(Long.parseLong(text), ctx.getStart().getLine());
    }

    @Override
    public Object visitStringLiteral(BBRunParser.StringLiteralContext ctx) {
        return new LiteralNode(unescape(stripQuotes(ctx.STRING().getText())), ctx.getStart().getLine());
    }

    @Override
    public Object visitBooleanTrue(BBRunParser.BooleanTrueContext ctx) {
        return new LiteralNode(true, ctx.getStart().getLine());
    }

    @Override
    public Object visitBooleanFalse(BBRunParser.BooleanFalseContext ctx) {
        return new LiteralNode(false, ctx.getStart().getLine());
    }

    @Override
    public Object visitNullLiteral(BBRunParser.NullLiteralContext ctx) {
        return new LiteralNode(null, ctx.getStart().getLine());
    }

    @Override
    public Object visitIdentifierExpr(BBRunParser.IdentifierExprContext ctx) {
        return new IdentifierNode(ctx.IDENTIFIER().getText(), ctx.getStart().getLine());
    }

    @Override
    public Object visitPathExpr(BBRunParser.PathExprContext ctx) {
        return new PathNode(ctx.path().getText(), ctx.getStart().getLine());
    }

    @Override
    public Object visitGroupedExpr(BBRunParser.GroupedExprContext ctx) {
        return visit(ctx.expression());
    }

    @Override
    public Object visitObjectLiteralExpr(BBRunParser.ObjectLiteralExprContext ctx) {
        Map<String, ExpressionNode> entries = new LinkedHashMap<>();
        if (ctx.objectLiteral().objectEntry() != null) {
            for (BBRunParser.ObjectEntryContext e : ctx.objectLiteral().objectEntry()) {
                String key;
                if (e.IDENTIFIER() != null) {
                    key = e.IDENTIFIER().getText();
                } else {
                    key = stripQuotes(e.getChild(0).getText());
                }
                ExpressionNode val = (ExpressionNode) visit(e.expression());
                entries.put(key, val);
            }
        }
        return new ObjectLiteralNode(entries, ctx.getStart().getLine());
    }

    @Override
    public Object visitArrayLiteralExpr(BBRunParser.ArrayLiteralExprContext ctx) {
        List<ExpressionNode> items = new ArrayList<>();
        if (ctx.arrayLiteral().expression() != null) {
            for (BBRunParser.ExpressionContext e : ctx.arrayLiteral().expression()) {
                items.add((ExpressionNode) visit(e));
            }
        }
        return new ArrayLiteralNode(items, ctx.getStart().getLine());
    }

    private List<ExpressionNode> extractArgs(BBRunParser.ArgumentListContext ctx) {
        if (ctx == null || ctx.expression() == null) return List.of();
        List<ExpressionNode> args = new ArrayList<>();
        for (BBRunParser.ExpressionContext e : ctx.expression()) {
            args.add((ExpressionNode) visit(e));
        }
        return args;
    }

    private String pathValue(BBRunParser.PathContext ctx) {
        String text = ctx.getText();
        if (text != null && text.length() >= 2
                && ((text.startsWith("\"") && text.endsWith("\""))
                    || (text.startsWith("'") && text.endsWith("'")))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** Resolves the escapes of a string literal: \n, \t, \r, \", \', \\ (any other \x stays as it is). */
    static String unescape(String s) {
        if (s == null || s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 == s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case '"', '\'', '\\' -> sb.append(n);
                default -> sb.append('\\').append(n);
            }
        }
        return sb.toString();
    }

    /** The expression as written in the script, spaces included. */
    private static String sourceText(org.antlr.v4.runtime.ParserRuleContext ctx) {
        org.antlr.v4.runtime.Token start = ctx.getStart();
        org.antlr.v4.runtime.Token stop = ctx.getStop();
        if (start == null || stop == null || start.getInputStream() == null) return ctx.getText();
        return start.getInputStream().getText(
                org.antlr.v4.runtime.misc.Interval.of(start.getStartIndex(), stop.getStopIndex()));
    }

    private String stripQuotes(String s) {
        if (s == null || s.length() < 2) return s;
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
