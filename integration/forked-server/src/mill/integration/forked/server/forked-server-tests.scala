package mill.integration
package forked.server

object CrossTests extends CrossTests(fork = true, clientServer = true)
object DocAnnotationsTests extends DocAnnotationsTests(fork = true, clientServer = true)
object HygieneTests extends HygieneTests(fork = true, clientServer = true)
object LargeProjectTests extends LargeProjectTests(fork = true, clientServer = true)
object MillJvmOptsTests extends MillJvmOptsTests(fork = true, clientServer = true)
object ScriptsInvalidationTests extends ScriptsInvalidationTests(fork = true, clientServer = true)
object ScriptsInvalidationForeignTests
    extends ScriptsInvalidationForeignTests(fork = true, clientServer = true)
object ZincIncrementalCompilationTests
    extends ZincIncrementalCompilationTests(fork = true, clientServer = true)
