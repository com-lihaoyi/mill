import mill._
import mill.runner.MillBuildRootModule
import mill.scalalib._

object `package` extends MillBuildRootModule {
  override def ivyDeps = Agg(
    ivy"de.tototec::de.tobiasroeser.mill.vcs.version::0.4.0",
    ivy"com.github.lolgab::mill-mima::0.1.1",
    ivy"net.sourceforge.htmlcleaner:htmlcleaner:2.29",
    // TODO: implement empty version for ivy deps as we do in import parser
    ivy"com.lihaoyi::mill-contrib-buildinfo:${mill.api.BuildInfo.millVersion}",
    ivy"com.goyeau::mill-scalafix::0.4.2",
    ivy"com.lihaoyi::mill-main-graphviz:${mill.api.BuildInfo.millVersion}",
    // TODO: document, why we have this dependency
    ivy"org.jsoup:jsoup:1.18.1"
  )
}
