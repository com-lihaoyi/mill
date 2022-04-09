package mill.integration.thirdparty
package local

object AcyclicTests extends AcyclicTests(fork = false)
object AmmoniteTests extends AmmoniteTests(fork = false)
object BetterFilesTests extends BetterFilesTests(fork = false)
object JawnTests extends JawnTests(fork = false)
object UpickleTests extends UpickleTests(fork = false)
// PlayJsonTests doesn't work in non-forked mode, and I have no idea why!
// object PlayJsonTests extends PlayJsonTests(fork = false)
object CaffeineTests extends CaffeineTests(fork = false)
