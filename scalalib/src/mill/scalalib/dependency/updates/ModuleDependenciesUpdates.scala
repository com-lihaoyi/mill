package mill.scalalib.dependency.updates

final case class ModuleDependenciesUpdates(
    modulePath: String,
    dependencies: Seq[DependencyUpdates]
)

object ModuleDependenciesUpdates {
  implicit val rw: upickle.default.ReadWriter[ModuleDependenciesUpdates] =
    upickle.default.macroRW
}




