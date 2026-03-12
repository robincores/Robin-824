grammar R8C;

/* =======================
 * Parser rules (v1.2)
 * ======================= */

translationUnit
  : (functionDef)* EOF
  ;

functionDef
  : typeSpec IDENT LPAREN paramList? RPAREN compoundStmt
  ;

paramList
  : param (COMMA param)*
  ;

param
  : typeSpec IDENT
  ;

typeSpec
  : K_INT
  ;

compoundStmt
  : LBRACE blockItem* RBRACE
  ;

blockItem
  : decl
  | stmt
  ;

decl
  : typeSpec IDENT (EQ expr)? SEMI
  ;

stmt
  : returnStmt
  | ifStmt
  | whileStmt
  | exprStmt
  | compoundStmt
  ;

ifStmt
  : K_IF LPAREN expr RPAREN stmt (K_ELSE stmt)?
  ;

whileStmt
  : K_WHILE LPAREN expr RPAREN stmt
  ;

returnStmt
  : K_RETURN expr? SEMI
  ;

exprStmt
  : expr? SEMI
  ;

expr
  : assignment
  ;

assignment
  : logicalOr (EQ assignment)?
  ;

logicalOr
  : logicalAnd (BARBAR logicalAnd)*
  ;

logicalAnd
  : equality (AMPAMP equality)*
  ;

equality
  : relational ((EQEQ | NEQ) relational)*
  ;

relational
  : additive ((LT | LTE | GT | GTE) additive)*
  ;

additive
  : multiplicative ((PLUS | MINUS) multiplicative)*
  ;

multiplicative
  : unary ((STAR | SLASH | PERCENT) unary)*
  ;

unary
  : (PLUS | MINUS | BANG | STAR | AMP) unary
  | postfix
  ;

argList
  : expr (COMMA expr)*
  ;

postfix
  : primary (LPAREN argList? RPAREN)*
  ;

primary
  : INT_LIT
  | IDENT
  | LPAREN expr RPAREN
  ;

/* =======================
 * Lexer rules
 * ======================= */

K_INT    : 'int';
K_RETURN : 'return';
K_IF     : 'if';
K_ELSE   : 'else';
K_WHILE  : 'while';

IDENT    : [a-zA-Z_][a-zA-Z0-9_]*;

INT_LIT
  : '0' [xX] [0-9a-fA-F]+
  | [0-9]+
  ;

LPAREN  : '(';
RPAREN  : ')';
LBRACE  : '{';
RBRACE  : '}';
COMMA   : ',';
SEMI    : ';';

PLUS    : '+';
MINUS   : '-';
STAR    : '*';
SLASH   : '/';
PERCENT : '%';

EQ      : '=';
EQEQ    : '==';
NEQ     : '!=';
LT      : '<';
LTE     : '<=';
GT      : '>';
GTE     : '>=';

BANG    : '!';
AMP     : '&';
AMPAMP  : '&&';
BARBAR  : '||';

WS            : [ \t\r\n]+ -> skip;
LINE_COMMENT  : '//' ~[\r\n]* -> skip;
BLOCK_COMMENT : '/*' .*? '*/' -> skip;
