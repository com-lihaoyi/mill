package mill.scalalib
import mill._


trait PlatformScalaModule extends ScalaModule{
  override def millSourcePath = super.millSourcePath / os.up

  override def sources = T.sources {
    val platform = millModuleSegments.parts.last
    super.sources().flatMap(source =>
      Seq(
        source,
        PathRef(source.path / os.up / s"${source.path.last}-${platform}")
      )
    )
  }

  override def artifactName = millModuleSegments.parts.dropRight(1).last
}
