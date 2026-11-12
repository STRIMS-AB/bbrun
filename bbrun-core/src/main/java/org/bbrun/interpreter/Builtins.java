package org.bbrun.interpreter;

import com.alibaba.fastjson2.JSON;
import org.bbrun.ast.ExpressionNode;
import org.bbrun.ast.Expressions.IdentifierNode;
import org.bbrun.runtime.RequestBody;
import org.bbrun.runtime.RequestHeaders;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The built-in functions beyond the core ones ({@code uuid}, {@code now}, {@code random},
 * {@code randomString}, {@code env}, {@code respond}) and the methods of strings.
 * File paths are relative to the folder of the script that names them.
 */
final class Builtins {

    private Builtins() {}

    static Object call(Interpreter in, String name, List<ExpressionNode> args) {
        ExecutionContext ctx = in.getContext();
        return switch (name) {
            case "sleep" -> {
                long ms = number(in, args, 0, name).longValue();
                try {
                    Thread.sleep(Math.max(0, ms));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Cancelled while sleeping");
                }
                yield null;
            }
            case "authenticate" -> {
                if (args.size() != 2) throw new RuntimeException("authenticate(resource, {type, ...}) takes two arguments");
                String resource = args.get(0) instanceof IdentifierNode id && !ctx.hasVariable(id.name())
                        ? id.name() : String.valueOf(in.evaluate(args.get(0)));
                Object auth = in.evaluate(args.get(1));
                if (auth != null && !(auth instanceof Map<?, ?>)) {
                    throw new RuntimeException("authenticate: the second argument is an object like {\"type\": \"bearer\", \"token\": t}");
                }
                ctx.getResourceContext().authenticate(resource, (Map<?, ?>) auth);
                yield null;
            }
            case "headers" -> new RequestHeaders(stringMap(map(in, args, 0, name)));
            case "form" -> {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<?, ?> e : map(in, args, 0, name).entrySet()) {
                    if (e.getValue() == null) continue;
                    if (!sb.isEmpty()) sb.append('&');
                    sb.append(encode(String.valueOf(e.getKey()))).append('=').append(encode(String.valueOf(e.getValue())));
                }
                yield new RequestBody(sb.toString(), "application/x-www-form-urlencoded");
            }
            case "file" -> {
                Path file = path(in, args, name);
                String type = args.size() > 1 ? String.valueOf(in.evaluate(args.get(1))) : contentType(file);
                yield new RequestBody(read(file), type);
            }
            case "readText" -> read(path(in, args, name));
            case "readJson" -> JSON.parse(read(path(in, args, name)));
            case "readCsv" -> csv(read(path(in, args, name)));
            case "parseJson" -> {
                Object text = in.evaluate(args.get(0));
                yield text == null ? null : JSON.parse(String.valueOf(text));
            }
            case "toJson" -> JSON.toJSONString(args.isEmpty() ? null : in.evaluate(args.get(0)));
            case "keys" -> new ArrayList<>(map(in, args, 0, name).keySet().stream().map(String::valueOf).toList());
            case "pluck" -> {
                List<Object> out = new ArrayList<>();
                for (Object item : list(in, args, 0, name)) {
                    out.add(item instanceof Map<?, ?> m ? m.get(String.valueOf(in.evaluate(args.get(1)))) : null);
                }
                yield out;
            }
            case "find" -> {
                if (args.size() != 3) throw new RuntimeException("find(list, field, value) takes three arguments");
                String field = String.valueOf(in.evaluate(args.get(1)));
                Object value = in.evaluate(args.get(2));
                Object found = null;
                for (Object item : list(in, args, 0, name)) {
                    if (item instanceof Map<?, ?> m && same(m.get(field), value)) {
                        found = item;
                        break;
                    }
                }
                yield found;
            }
            case "base64Decode" -> {
                // standard or URL-safe alphabet, with or without padding
                String text = String.valueOf(in.evaluate(args.get(0))).replace("=", "").replace('+', '-').replace('/', '_');
                yield new String(java.util.Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
            }
            case "base64Encode" -> java.util.Base64.getEncoder().encodeToString(
                    String.valueOf(in.evaluate(args.get(0))).getBytes(StandardCharsets.UTF_8));
            case "jwtClaims" -> {
                String[] parts = String.valueOf(in.evaluate(args.get(0))).split("\\.");
                if (parts.length < 2) throw new RuntimeException("jwtClaims(): not a JWT");
                yield JSON.parse(new String(java.util.Base64.getUrlDecoder().decode(parts[1].replace("=", "")), StandardCharsets.UTF_8));
            }
            case "urlEncode" -> encode(String.valueOf(in.evaluate(args.get(0))));
            case "urlParam" -> {
                if (args.size() != 2) throw new RuntimeException("urlParam(url, name) takes two arguments");
                Object url = in.evaluate(args.get(0));
                yield url == null ? null : queryParam(String.valueOf(url), String.valueOf(in.evaluate(args.get(1))));
            }
            default -> throw new RuntimeException("Unknown function " + name + "()");
        };
    }

    static Object stringMethod(String s, String method, List<Object> a, int line) {
        return switch (method) {
            case "toLowerCase" -> s.toLowerCase(Locale.ROOT);
            case "toUpperCase" -> s.toUpperCase(Locale.ROOT);
            case "trim" -> s.trim();
            case "length" -> s.length();
            case "startsWith" -> s.startsWith(arg(a, 0, method));
            case "endsWith" -> s.endsWith(arg(a, 0, method));
            case "contains" -> s.contains(arg(a, 0, method));
            case "indexOf" -> s.indexOf(arg(a, 0, method));
            case "replace" -> s.replace(arg(a, 0, method), arg(a, 1, method));
            case "split" -> new ArrayList<Object>(List.of(s.split(java.util.regex.Pattern.quote(arg(a, 0, method)), -1)));
            case "substring" -> {
                int from = ((Number) a.get(0)).intValue();
                yield a.size() > 1 ? s.substring(from, ((Number) a.get(1)).intValue()) : s.substring(from);
            }
            default -> throw new RuntimeException("No method " + method + "() on a string at line " + line);
        };
    }

    private static String arg(List<Object> a, int i, String method) {
        if (a.size() <= i || a.get(i) == null) {
            throw new RuntimeException(method + "() needs " + (i + 1) + " argument(s)");
        }
        return String.valueOf(a.get(i));
    }

    private static Number number(Interpreter in, List<ExpressionNode> args, int i, String fn) {
        Object v = args.size() > i ? in.evaluate(args.get(i)) : null;
        if (!(v instanceof Number n)) throw new RuntimeException(fn + "() expects a number");
        return n;
    }

    private static Map<?, ?> map(Interpreter in, List<ExpressionNode> args, int i, String fn) {
        Object v = args.size() > i ? in.evaluate(args.get(i)) : null;
        if (!(v instanceof Map<?, ?> m)) throw new RuntimeException(fn + "() expects an object");
        return m;
    }

    private static List<?> list(Interpreter in, List<ExpressionNode> args, int i, String fn) {
        Object v = args.size() > i ? in.evaluate(args.get(i)) : null;
        if (v == null) return List.of();
        if (!(v instanceof List<?> l)) throw new RuntimeException(fn + "() expects a list, not " + Interpreter.describe(v));
        return l;
    }

    private static boolean same(Object a, Object b) {
        return Interpreter.sameValue(a, b);
    }

    private static Map<String, String> stringMap(Map<?, ?> m) {
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (v != null) out.put(String.valueOf(k), String.valueOf(v));
        });
        return out;
    }

    private static Path path(Interpreter in, List<ExpressionNode> args, String fn) {
        if (args.isEmpty()) throw new RuntimeException(fn + "(path) needs a path");
        return in.getContext().getScriptDir().resolve(String.valueOf(in.evaluate(args.get(0)))).normalize();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new RuntimeException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String queryParam(String url, String name) {
        String query = url;
        int q = url.indexOf('?');
        if (q >= 0) query = url.substring(q + 1);
        else if (url.contains("://")) query = URI.create(url).getRawQuery();
        if (query == null) return null;
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, StandardCharsets.UTF_8);
            if (key.equals(name)) {
                return eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }

    static String contentType(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
        return switch (ext) {
            case "json" -> "application/json";
            case "csv" -> "text/csv";
            case "txt", "properties" -> "text/plain; charset=utf-8";
            case "md" -> "text/markdown; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "html", "htm" -> "text/html; charset=utf-8";
            case "css" -> "text/css";
            case "xml" -> "application/xml";
            case "yaml", "yml" -> "application/yaml";
            default -> "application/octet-stream";
        };
    }

    /** RFC 4180 CSV: rows of string cells; quoted cells may hold commas, quotes ("") and line breaks. */
    static List<List<String>> csv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        if (text.startsWith("﻿")) i = 1;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
