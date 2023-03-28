package mill.integration
package local

object CrossTests extends CrossTests(fork = false, clientServer = false)
object DocAnnotationsTests extends DocAnnotationsTests(fork = false, clientServer = false)
object HygieneTests extends HygieneTests(fork = false, clientServer = false)
object LargeProjectTests extends LargeProjectTests(fork = false, clientServer = false)
object ScriptsInvalidationTests extends ScriptsInvalidationTests(fork = false, clientServer = false)
object ScriptsInvalidationForeignTests
    extends ScriptsInvalidationForeignTests(fork = false, clientServer = false)
object ZincIncrementalCompilationTests
    extends ZincIncrementalCompilationTests(fork = false, clientServer = false)
object CompileErrorTests extends CompileErrorTests(fork = false, clientServer = false)
object ParseErrorTests extends ParseErrorTests(fork = false, clientServer = false)

object MetaBuildTests extends TwoLevelBuildTests(fork = false, clientServer = false)
object MultiLevelBuildTests extends MultiLevelBuildTests(fork = false, clientServer = false)
object TopLevelModuleTests extends TopLevelModuleTests(fork = false, clientServer = false)
object MissingBuildFileTests extends MissingBuildFileTests(fork = false, clientServer = false)
