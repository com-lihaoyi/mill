package mill.javalib

enum CompileFor {

  /** This is a regular compilation, for example for `compile`. */
  case Regular

  /** This is a compilation for SemanticDB, for example for `semanticDbData`. */
  case SemanticDb
}
