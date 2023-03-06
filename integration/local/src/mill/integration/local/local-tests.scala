package mill.integration
package local

object DocAnnotationsTests extends DocAnnotationsTests(fork = false, clientServer = false)
object HygieneTests extends HygieneTests(fork = false, clientServer = false)
object LargeProjectTests extends LargeProjectTests(fork = false, clientServer = false)
object ScriptsInvalidationTests extends ScriptsInvalidationTests(fork = false, clientServer = false)
object ScriptsInvalidationForeignTests
    extends ScriptsInvalidationForeignTests(fork = false, clientServer = false)
object ZincIncrementalCompilationTests
    extends ZincIncrementalCompilationTests(fork = false, clientServer = false)
