/**
 * BBRun Script Grammar
 * A domain-specific language for API testing
 */
grammar BBRun;

// ============================================
// Parser Rules
// ============================================

script
    : statement* EOF
    ;

statement
    : baseUrlStatement
    | authStatement
    | oauthStatement  
    | variableDecl
    | requestStatement
    | assertStatement
    | warnStatement
    | printStatement
    | ifStatement
    | repeatStatement
    | parallelStatement
    | expectBlock
    | macroDecl
    | runStatement
    | cleanupBlock
    | expression NEWLINE?
    | NEWLINE
    ;

// Base URL
baseUrlStatement
    : 'baseUrl' (STRING | functionCall) NEWLINE?
    ;

// Auth
authStatement
    : 'auth' 'bearer' expression NEWLINE?
    | 'auth' 'basic' expression expression NEWLINE?
    | 'auth' STRING 'bearer' expression NEWLINE?  // named auth
    | 'auth' 'apiKey' block NEWLINE?
    ;

oauthStatement
    : 'oauth' IDENTIFIER block NEWLINE?
    ;

// Variables
variableDecl
    : 'let' IDENTIFIER '=' expression NEWLINE?
    ;

// HTTP Requests
requestStatement
    : httpVerb path NEWLINE?
    | httpVerb path 'with' authClause NEWLINE?
    | httpVerb expression 'to' path NEWLINE?
    | httpVerb expression 'to' path 'with' authClause NEWLINE?
    | 'delete' path NEWLINE?
    | 'delete' path 'with' authClause NEWLINE?
    ;

httpVerb
    : 'get'
    | 'post'
    | 'put'
    | 'patch'
    ;

path
    : '#' pathSegment ('/' pathSegment)* queryParams?
    | STRING  // full URL
    ;

pathSegment
    : IDENTIFIER
    | NUMBER
    | keyword
    | interpolation
    | IDENTIFIER interpolation
    | interpolation IDENTIFIER
    ;

// Allow keywords to be used as path segments (e.g., #post, #get, #delete)
keyword
    : 'get' | 'post' | 'put' | 'patch' | 'delete' | 'to'
    | 'auth' | 'bearer' | 'basic' | 'with' | 'using' | 'without'
    | 'assert' | 'warn' | 'print' | 'if' | 'else' | 'repeat'
    | 'parallel' | 'expect' | 'macro' | 'run' | 'cleanup'
    | 'let' | 'baseUrl' | 'true' | 'false' | 'null'
    | 'response' | 'params' | 'thread' | 'stats' | 'timing' | 'metrics'
    | 'is' | 'contains' | 'matches' | 'schema' | 'not' | 'and' | 'or'
    ;

interpolation
    : '${' expression '}'
    ;

queryParams
    : '?' queryParam ('&' queryParam)*
    ;

queryParam
    : IDENTIFIER '=' (IDENTIFIER | STRING | interpolation)
    ;

authClause
    : 'bearer' expression
    | 'basic' expression expression
    | 'headers' block
    | 'using' STRING
    | 'without' 'auth'
    ;

// Assertions
assertStatement
    : 'assert' expression (':' STRING)? NEWLINE?
    ;

warnStatement
    : 'warn' expression (':' STRING)? NEWLINE?
    ;

// Control Flow
ifStatement
    : 'if' expression block ('else' 'if' expression block)* ('else' block)?
    ;

repeatStatement
    : 'repeat' expression block
    ;

parallelStatement
    : 'parallel' parallelOptions block
    ;

parallelOptions
    : parallelOption (',' parallelOption)*
    ;

parallelOption
    : IDENTIFIER ':' expression
    ;

expectBlock
    : 'expect' expression block
    ;

// Macros & Scripts
macroDecl
    : 'macro' IDENTIFIER '(' paramList? ')' block
    ;

paramList
    : IDENTIFIER (',' IDENTIFIER)*
    ;

runStatement
    : 'run' STRING NEWLINE?
    | 'run' STRING 'with' block NEWLINE?
    ;

cleanupBlock
    : 'cleanup' block
    ;

// Print
printStatement
    : 'print' expression NEWLINE?
    ;

// Blocks
block
    : '{' statement* '}'
    ;

// ============================================
// Expressions
// ============================================

expression
    : primary
    | expression '.' IDENTIFIER                           // member access
    | expression '[' expression ']'                       // index access
    | expression '(' argumentList? ')'                    // function call
    | expression ('==' | '!=' | '<' | '>' | '<=' | '>=') expression
    | expression 'is' typeOrFormat
    | expression 'contains' expression
    | expression 'not' 'contains' expression
    | expression 'matches' 'schema' expression
    | expression 'ignoring' '[' stringList ']'
    | 'not' expression
    | expression 'and' expression
    | expression 'or' expression
    | expression '+' expression
    | expression '-' expression
    | expression '*' expression
    | expression '/' expression
    ;

primary
    : IDENTIFIER
    | NUMBER
    | STRING
    | 'true'
    | 'false'
    | 'null'
    | 'response'
    | 'params'
    | 'thread'
    | 'stats'
    | 'timing'
    | 'metrics'
    | objectLiteral
    | arrayLiteral
    | functionCall
    | '(' expression ')'
    ;

typeOrFormat
    : 'number'
    | 'string' 
    | 'boolean'
    | 'array'
    | 'object'
    | 'email'
    | 'url'
    | 'uuid'
    | 'datetime'
    | 'phone'
    | 'ipv4'
    | STRING        // custom regex pattern
    ;

objectLiteral
    : '{' NEWLINE* '}'                                                    // empty
    | '{' NEWLINE* objectProperty (separator objectProperty)* NEWLINE* '}'  // with properties
    ;

objectProperty
    : (STRING | IDENTIFIER) ':' expression
    ;

arrayLiteral
    : '[' NEWLINE* ']'                                          // empty 
    | '[' NEWLINE* expression (separator expression)* NEWLINE* ']'  // with elements
    ;

// Separator allows comma and/or newlines between elements
separator
    : ','? NEWLINE+ ','?
    | ','
    ;

stringList
    : STRING (',' STRING)*
    ;

functionCall
    : functionName '(' argumentList? ')'
    ;

// Function names can be identifiers or built-in function keywords
functionName
    : IDENTIFIER
    | 'uuid' | 'datetime' | 'env' | 'now' | 'random' | 'randomString' | 'timestamp' | 'base64' | 'json'
    | 'length' | 'keys' | 'values' | 'pick' | 'omit' | 'merge' | 'concat'
    ;

argumentList
    : expression (',' expression)*
    ;

// ============================================
// Lexer Rules
// ============================================

// Keywords (must come before IDENTIFIER)
BASEURL     : 'baseUrl' ;
AUTH        : 'auth' ;
OAUTH       : 'oauth' ;
LET         : 'let' ;
GET         : 'get' ;
POST        : 'post' ;
PUT         : 'put' ;
PATCH       : 'patch' ;
DELETE      : 'delete' ;
TO          : 'to' ;
WITH        : 'with' ;
ASSERT      : 'assert' ;
WARN        : 'warn' ;
IF          : 'if' ;
ELSE        : 'else' ;
REPEAT      : 'repeat' ;
PARALLEL    : 'parallel' ;
EXPECT      : 'expect' ;
MACRO       : 'macro' ;
RUN         : 'run' ;
CLEANUP     : 'cleanup' ;
PRINT       : 'print' ;
BEARER      : 'bearer' ;
BASIC       : 'basic' ;
HEADERS     : 'headers' ;
USING       : 'using' ;
WITHOUT     : 'without' ;
LOAD        : 'load' ;

// Types
NUMBER_TYPE : 'number' ;
STRING_TYPE : 'string' ;
BOOLEAN_TYPE: 'boolean' ;
ARRAY_TYPE  : 'array' ;
OBJECT_TYPE : 'object' ;

// Formats
EMAIL       : 'email' ;
URL         : 'url' ;
UUID        : 'uuid' ;
DATETIME    : 'datetime' ;
PHONE       : 'phone' ;
IPV4        : 'ipv4' ;

// Operators
IS          : 'is' ;
CONTAINS    : 'contains' ;
MATCHES     : 'matches' ;
SCHEMA      : 'schema' ;
IGNORING    : 'ignoring' ;
NOT         : 'not' ;
AND         : 'and' ;
OR          : 'or' ;
TRUE        : 'true' ;
FALSE       : 'false' ;
NULL        : 'null' ;

// Built-in objects
RESPONSE    : 'response' ;
PARAMS      : 'params' ;
THREAD      : 'thread' ;
STATS       : 'stats' ;
TIMING      : 'timing' ;
METRICS     : 'metrics' ;

// Literals
IDENTIFIER  : [a-zA-Z_][a-zA-Z0-9_]* ;
NUMBER      : '-'? [0-9]+ ('.' [0-9]+)? ;
STRING      : '"' (~["\r\n] | '\\"')* '"' ;

// Symbols
HASH        : '#' ;
DOT         : '.' ;
COMMA       : ',' ;
COLON       : ':' ;
LPAREN      : '(' ;
RPAREN      : ')' ;
LBRACE      : '{' ;
RBRACE      : '}' ;
LBRACK      : '[' ;
RBRACK      : ']' ;
EQ          : '==' ;
NE          : '!=' ;
LT          : '<' ;
GT          : '>' ;
LE          : '<=' ;
GE          : '>=' ;
ASSIGN      : '=' ;
PLUS        : '+' ;
MINUS       : '-' ;
STAR        : '*' ;
SLASH       : '/' ;
QUESTION    : '?' ;
AMP         : '&' ;
DOLLAR_LBRACE : '${' ;

// Whitespace & Comments
NEWLINE     : [\r\n]+ ;
WS          : [ \t]+ -> skip ;
LINE_COMMENT: '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT: '/*' .*? '*/' -> skip ;
