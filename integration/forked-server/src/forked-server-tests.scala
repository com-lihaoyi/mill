package mill.integration
package forked.server

object DocAnnotationsTests extends DocAnnotationsTests(fork = true, clientServer = true)
object HygieneTests extends HygieneTests(fork = true, clientServer = true)
object LargeProjectTests extends LargeProjectTests(fork = true, clientServer = true)
object ScriptsInvalidationTests extends ScriptsInvalidationTests(fork = true, clientServer = true)
object ScriptsInvalidationForeignTests extends ScriptsInvalidationForeignTests(fork = true, clientServer = true)
