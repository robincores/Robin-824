grammar R8Asm;

// Parse ONE source line at a time (Assembler feeds lines individually)
oneLine
  : labelDef? (directive | instruction)? EOF
  ;

labelDef
  : IDENT ':'
  ;

// Allow directive args separated by whitespace OR commas
//   .data 1 2
//   .data 1,2
//   .align 4+4
//   .org (. + 16)
directive
  : '.' IDENT (directiveArg (','? directiveArg)*)?
  ;

directiveArg
  : STRING
  | expr
  ;

instruction
  : IDENT (operand (',' operand)*)?
  ;

operand
  : memOperand
  | AT expr
  | expr
  ;

memOperand
  : expr '(' expr ')'
  ;

// ---------------- Expressions ----------------

expr
  : bitOrExpr
  ;

bitOrExpr
  : bitXorExpr ( '|' bitXorExpr )*
  ;

bitXorExpr
  : bitAndExpr ( '^' bitAndExpr )*
  ;

bitAndExpr
  : shiftExpr ( '&' shiftExpr )*
  ;

shiftExpr
  : addExpr ( ( '<<' | '>>' ) addExpr )*
  ;

addExpr
  : mulExpr ( ( '+' | '-' ) mulExpr )*
  ;

mulExpr
  : unaryExpr ( ( '*' | '/' | '%' ) unaryExpr )*
  ;

unaryExpr
  : ( '+' | '-' | '~' ) unaryExpr
  | primary
  ;

primary
  : NUMBER
  | IDENT
  | '.'
  | '(' expr ')'
  ;

// ---------- LEXER ----------

AT
  : '@'
  ;

COMMENT
  : ';' ~[\r\n]* -> skip
  ;

WS
  : [ \t]+ -> skip
  ;

NUMBER
  : '0' [xX] HEXDIGIT (HEXDIGIT | '_')*
  | '$' HEXDIGIT (HEXDIGIT | '_')*
  | DIGIT (DIGIT | '_')*
  ;

STRING
  : '"' ( '\\' . | ~["\\\r\n] )* '"'
  ;

IDENT
  : [a-zA-Z_] [a-zA-Z0-9_]*
  ;

fragment HEXDIGIT
  : [0-9a-fA-F]
  ;

fragment DIGIT
  : [0-9]
  ;
