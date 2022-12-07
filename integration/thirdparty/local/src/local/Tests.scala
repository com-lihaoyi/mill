package mill.integration.thirdparty
package local

object AcyclicTests extends AcyclicTests(fork = false)
object AmmoniteTests extends AmmoniteTests(fork = false)
object JawnTests extends JawnTests(fork = false)
object UpickleTests extends UpickleTests(fork = false)
// CaffeineTests are flaky in local (fork=false) mode
// object CaffeineTests extends CaffeineTests(fork = false)
