package mill.bsp.newbsp

case class BspServerConfig(
    ammoniteCore: ammonite.main.Config.Core,
    @mainargs.arg(name = "jobs", short = 'j')
    threadCount: Option[Int] = Option(1),
    @mainargs.arg(name = "keep-going", short = 'k')
    keepGoing: Boolean = false
)
