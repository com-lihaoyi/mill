package mill.integration.forked

object AcyclicTests extends mill.integration.AcyclicTests(fork = true)
object AmmoniteTests extends mill.integration.AmmoniteTests(fork = true)
object BetterFilesTests extends mill.integration.BetterFilesTests(fork = true)
object JawnTests extends mill.integration.JawnTests(fork = true)
object UpickleTests extends mill.integration.UpickleTests(fork = true)
object PlayJsonTests extends mill.integration.PlayJsonTests(fork = true)
object CaffeineTests extends mill.integration.CaffeineTests(fork = true)
object DocAnnotationsTests extends mill.integration.DocAnnotationsTests(fork = true)
