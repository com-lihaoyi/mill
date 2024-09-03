import mill._
import mill.runner.MillBuildRootModule
import mill.scalalib._

object `package` extends MillBuildRootModule {
  override def ivyDeps = Agg(
    ivy"org.scalaj::scalaj-http:2.4.2",
    ivy"de.tototec::de.tobiasroeser.mill.vcs.version::0.4.0",
    ivy"com.github.lolgab::mill-mima::0.1.1",
    ivy"net.sourceforge.htmlcleaner:htmlcleaner:2.29",
    // TODO: implement empty version for ivy deps as we do in import parser
    ivy"com.lihaoyi::mill-contrib-buildinfo:${mill.api.BuildInfo.millVersion}",
    ivy"com.goyeau::mill-scalafix::0.4.1"
  )
}
