/**
 * BBRun Language Grammar
 * Domain-specific language for testing and integration with resource abstractions and DRef validation.
 */
grammar BBRun;

// ============================================
// Parser Rules
// ============================================

script
    : statement* EOF
    ;

statement
    : typedDeclaration
    | variableDecl
    | verbRequestStatement
    | mapStatement
    | validateStatement
    | assertStatement
    | warnStatement
    | printStatement
    | ifStatement
    | forStatement
    | returnStatement
    | breakStatement
    | abortStatement
    | statusStatement
    | headerStatement
    | goalStatement
    | stepStatement
    | cleanupStatement
    | includeStatement
    | expressionStatement
    | ';'
    ;

// A scenario states what it is for, then its flow as named steps.
goalStatement
    : 'goal' expression ';'?
    ;

stepStatement
    : 'step' expression block
    ;

// Runs when the script ends, passed or failed, last registered first.
cleanupStatement
    : 'cleanup' block
    ;

// Runs another script file in the same context; the path is relative to the including file.
includeStatement
    : 'include' STRING ';'?
    ;

statusStatement
    : 'status' '(' expression ')' ';'?
    ;

headerStatement
    : 'header' '(' expression ',' expression ')' ';'?
    ;

typedDeclaration
    : IDENTIFIER IDENTIFIER ASSIGN expression ';'?
    | IDENTIFIER LBRACKET RBRACKET IDENTIFIER ASSIGN expression ';'?
    ;

variableDecl
    : 'let' IDENTIFIER ASSIGN expression ';'?
    ;

verbRequestStatement
    : httpVerb expression? 'to' IDENTIFIER '.' path ';'?
    | httpVerb IDENTIFIER '.' path ';'?
    | 'delete' IDENTIFIER '.' path ';'?
    ;

mapStatement
    : 'map' expression 'using' STRING ('as' IDENTIFIER)? ';'?
    ;

validateStatement
    : 'validate' expression 'as' IDENTIFIER (':' STRING)? ';'?
    ;

assertStatement
    : 'assert' expression (':' STRING)? ';'?
    ;

warnStatement
    : 'warn' expression (':' STRING)? ';'?
    ;

printStatement
    : 'print' expression ';'?
    ;

ifStatement
    : 'if' expression block ('else' 'if' expression block)* ('else' block)?
    ;

forStatement
    : 'for' IDENTIFIER 'in' expression '..' expression block  # ForRangeStatement
    | 'for' IDENTIFIER 'in' expression block                  # ForEachStatement
    ;

breakStatement
    : 'break' ';'?
    ;

returnStatement
    : 'return' expression ';'?
    ;

abortStatement
    : 'abort' (expression)? ';'?
    ;

expressionStatement
    : expression ';'?
    ;

block
    : '{' statement* '}'
    ;

httpVerb
    : 'get'
    | 'post'
    | 'put'
    | 'patch'
    | 'delete'
    ;

path
    : '#' pathSegment ('/' pathSegment)* queryParams?
    | STRING
    ;

pathSegment
    : IDENTIFIER
    | NUMBER
    | keyword
    | interpolation
    | IDENTIFIER interpolation
    | interpolation IDENTIFIER
    ;

queryParams
    : '?' queryParam ('&' queryParam)*
    ;

queryParam
    : IDENTIFIER ASSIGN (IDENTIFIER | NUMBER | STRING | interpolation)
    ;

interpolation
    : '${' expression '}'
    ;

keyword
    : 'get' | 'post' | 'put' | 'patch' | 'delete' | 'to'
    | 'map' | 'validate' | 'as' | 'assert' | 'warn' | 'print'
    | 'if' | 'else' | 'for' | 'in' | 'break' | 'return' | 'abort'
    | 'let' | 'true' | 'false' | 'null'
    | 'is' | 'contains' | 'matches' | 'not' | 'and' | 'or'
    | 'status' | 'header'
    | 'goal' | 'step' | 'cleanup' | 'include'
    ;

// ============================================
// Expressions
// ============================================

expression
    // A call first: otherwise `let a = f(1)` reads as `let a = f` followed by a statement `(1)`
    : IDENTIFIER '(' argumentList? ')'                                 # FunctionCallExpr
    | primary                                                          # PrimaryExpr
    | expression '.' (IDENTIFIER | httpVerb | keyword) '(' argumentList? ')'  # MethodCallExpr
    | expression '.' (IDENTIFIER | keyword)                          # MemberAccessExpr
    | expression LBRACKET expression RBRACKET                                    # IndexAccessExpr
    | expression ('*' | '/') expression                                # MulDivExpr
    | expression ('+' | '-') expression                                # AddSubExpr
    | expression ('==' | '!=' | '<' | '>' | '<=' | '>=') expression    # RelationalExpr
    | expression 'matches' IDENTIFIER                                  # MatchesModelExpr
    | expression 'contains' expression                                 # ContainsExpr
    | expression 'not' 'contains' expression                           # NotContainsExpr
    | 'not' expression                                                 # NotExpr
    | expression 'and' expression                                      # AndExpr
    | expression 'or' expression                                       # OrExpr
    ;

argumentList
    : expression (',' expression)*
    ;

primary
    : NUMBER                                                           # NumberLiteral
    | STRING                                                           # StringLiteral
    | 'true'                                                           # BooleanTrue
    | 'false'                                                          # BooleanFalse
    | 'null'                                                           # NullLiteral
    | path                                                             # PathExpr
    | IDENTIFIER                                                       # IdentifierExpr
    | objectLiteral                                                    # ObjectLiteralExpr
    | arrayLiteral                                                     # ArrayLiteralExpr
    | '(' expression ')'                                               # GroupedExpr
    ;

objectLiteral
    : '{' (objectEntry (',' objectEntry)*)? ','? '}'
    ;

objectEntry
    : (IDENTIFIER | STRING | keyword) ':' expression
    ;

arrayLiteral
    : LBRACKET (expression (',' expression)*)? ','? RBRACKET
    ;

// ============================================
// Lexer Rules
// ============================================

NUMBER
    : '-'? [0-9]+ ('.' [0-9]+)?
    ;

STRING
    : '"' (~["\r\n\\] | '\\' .)* '"'
    | '\'' (~['\r\n\\] | '\\' .)* '\''
    ;

STATUS
    : 'status'
    ;

HEADER
    : 'header'
    ;

IDENTIFIER
    : [a-zA-Z_][a-zA-Z0-9_]*
    ;

ASSIGN
    : '='
    ;

LBRACKET
    : '['
    ;

RBRACKET
    : ']'
    ;

LINE_COMMENT
    : '//' ~[\r\n]* -> skip
    ;

BLOCK_COMMENT
    : '/*' .*? '*/' -> skip
    ;

WS
    : [ \t\r\n\f]+ -> skip
    ;
