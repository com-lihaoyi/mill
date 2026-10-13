package app

// Compiles only if the bindings of `wit/adder.wit` were generated into the module's sources
object Uses {
  val witPackage: String = generated.Marker.witPackage
}
