package mill.integration
package forked

object CrossTests extends CrossTests(fork = true, clientServer = false)
object DocAnnotationsTests extends DocAnnotationsTests(fork = true, clientServer = false)
object HygieneTests extends HygieneTests(fork = true, clientServer = false)
object LargeProjectTests extends LargeProjectTests(fork = true, clientServer = false)
object MillJvmOptsTests extends MillJvmOptsTests(fork = true, clientServer = false)
object ScriptsInvalidationTests extends ScriptsInvalidationTests(fork = true, clientServer = false)
object ScriptsInvalidationForeignTests
    extends ScriptsInvalidationForeignTests(fork = true, clientServer = false)
object ZincIncrementalCompilationTests
    extends ZincIncrementalCompilationTests(fork = true, clientServer = false)
object CompileErrorTests extends CompileErrorTests(fork = true, clientServer = false)
object ParseErrorTests extends ParseErrorTests(fork = true, clientServer = false)

object MetaBuildTests extends TwoLevelBuildTests(fork = true, clientServer = false)
object MultiLevelBuildTests extends MultiLevelBuildTests(fork = true, clientServer = false)
object TopLevelModuleTests extends TopLevelModuleTests(fork = true, clientServer = false)
object MissingBuildFileTests extends MissingBuildFileTests(fork = true, clientServer = false)
