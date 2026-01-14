package mill.javalib

import mill.api.Result
import mill.api.SelectMode.Separated
import mill.api.Discover
import mill.api.Evaluator
import mill.api.ExternalModule
import mill.api.PathRef
import mill.api.Task
import mill.util.TokenReaders.given

object OfflineSupport extends ExternalModule {
  override def millDiscover: Discover = Discover[this.type]

  /**
   * Prepare the project for working offline.
   * This should typically fetch (missing) resources like Maven dependencies.
   *
   * All fetched or cached resources will be returned. The result
   * - does not contain duplicates
   * - is also available as a JSON file at the stable location `<mill-out>/mill/scalalib/OfflineSupport/prepareOffline.json`
   *
   * If used without parameters, it's equivalent to running
   * `mill mill.scalalib.OfflineSupport/prepareOffline __:OfflineSupportModule.prepareOffline`
   * but without duplicates.
   *
   * To select only a subset of modules (`foo` and `bar`), run
   * `mill mill.scalalib.OfflineSupport/prepareOffline "{foo,bar}.prepareOffline"`
   *
   * To fetch all dependencies, even rarely used ones, run
   * `mill mill.scalalib.OfflineSupport/prepareOffline __:OfflineSupportModule.prepareOffline --all`
   *
   * @param modules module and parameter selector
   */
  def prepareOffline(
      evaluator: Evaluator,
      modules: mainargs.Leftover[String]
  ): Task.Command[Seq[PathRef]] = {

    val tasks = evaluator.resolveTasks(
      // if not given, use all project modules
      scriptArgs =
        if (modules.value.isEmpty) Seq("__:OfflineSupportModule.prepareOffline")
        else modules.value,
      // Separated: we accept individual task params, e.g. `--all`
      selectMode = Separated
    ).get.asInstanceOf[Seq[Task[Seq[PathRef]]]]
    Task.Command() {
      Task.sequence(tasks)().flatten.distinct
    }
  }

}
