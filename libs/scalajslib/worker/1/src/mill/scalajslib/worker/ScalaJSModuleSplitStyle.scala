package mill.scalajslib.worker

// Separating ModuleSplitStyle in a standalone object avoids
// early classloading which fails in Scala.js versions where
// the classes don't exist
object ScalaJSModuleSplitStyle {
  import org.scalajs.linker.interface.ModuleSplitStyle
  object SmallModulesFor {
    def apply(packages: List[String]): ModuleSplitStyle =
      ModuleSplitStyle.SmallModulesFor(packages)
  }
  object FewestModules {
    def apply(): ModuleSplitStyle = ModuleSplitStyle.FewestModules
  }
  object SmallestModules {
    def apply(): ModuleSplitStyle = ModuleSplitStyle.SmallestModules
  }
}
