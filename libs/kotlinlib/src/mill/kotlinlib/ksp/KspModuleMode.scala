package mill.kotlinlib.ksp

@mill.api.experimental
enum KspModuleMode {
  case Ksp1 // in program
//  case Ksp2 // In program
  case Ksp2Cli // In cli
}
