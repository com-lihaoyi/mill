package mill.integration.local

object AcyclicTests extends mill.integration.AcyclicTests(fork = false)
object AmmoniteTests extends mill.integration.AmmoniteTests(fork = false)
object BetterFilesTests extends mill.integration.BetterFilesTests(fork = false)
object HygieneTests extends mill.integration.HygieneTests(fork = false)
object LargeProjectTests extends mill.integration.LargeProjectTests(fork = false)
object JawnTests extends mill.integration.JawnTests(fork = false)
object UpickleTests extends mill.integration.UpickleTests(fork = false)
object PlayJsonTests extends mill.integration.PlayJsonTests(fork = false)
object CaffeineTests extends mill.integration.CaffeineTests(fork = false)
object DocAnnotationsTests extends mill.integration.DocAnnotationsTests(fork = false)
object ScriptsInvalidationTests extends mill.integration.ScriptsInvalidationTests(fork = true)
