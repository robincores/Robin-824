grammar R8C;

@header {
package io.github.robincores.toolchain.r8cc.antlr;
}

/* =======================
 * Parser rules (v1.1)
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

/* CHANGED: allow decls mixed with stmts in blocks */
compoundStmt
  : LBRACE blockItem* RBRACE
  ;

/* ADDED */
blockItem
  : decl
  | stmt
  ;

/* ADDED: minimal local decl (one name, optional init) */
decl
  : typeSpec IDENT (EQ expr)? SEMI
  ;

stmt
  : returnStmt
  | exprStmt
  | compoundStmt
  ;

returnStmt
  : K_RETURN expr? SEMI
  ;

exprStmt
  : expr? SEMI
  ;

/* expression precedence (minimal but useful) */
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
