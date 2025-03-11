package mill

package object javascriptlib {
  // These types are commonly used in javascript modules. Export them to make using
  // them possible without an import.

  type License = mill.scalalib.publish.License
  val License = mill.scalalib.publish.License

  type PublishMeta = PublishModule.PublishMeta
  val PublishMeta = PublishModule.PublishMeta

  type Export = PublishModule.Export
  val Export = PublishModule.Export

  type ExportConditions = PublishModule.ExportConditions
  val ExportConditions = PublishModule.ExportConditions
}
