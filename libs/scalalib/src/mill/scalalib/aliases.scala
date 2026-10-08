package mill.scalalib

object Dependency extends mill.api.ExternalModule.Alias(mill.javalib.Dependency)
object MavenPublishModule
    extends mill.api.ExternalModule.Alias(mill.javalib.MavenPublishModule)
object PublishModule extends mill.api.ExternalModule.Alias(mill.javalib.PublishModule) {
  export mill.javalib.PublishModule.*
}
object SonatypeCentralPublishModule
    extends mill.api.ExternalModule.Alias(mill.javalib.SonatypeCentralPublishModule)
