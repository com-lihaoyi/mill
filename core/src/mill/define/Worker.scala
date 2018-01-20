package mill.define

/**
  * Worker serves three purposes:
  *
  * - Cache in-memory state between tasks (e.g. object initialization)
  *   - Including warm classloaders with isolated bytecode
  * - Mutex to limit concurrency
  * - Manage out-of-process subprocesses <-- skip this for now
  *
  * Key usage:
  *
  * - T{
  *     ZincWorker().compile(a() + b())
  *   }
  *
  * Desugars into:
  *
  * - T.zipMap(ZincWorker, a, b){ (z, a1, b1) => z.compile(a1, b1) }
  *
  * Workers are shoehorned into the `Task` type. This lets them fit nicely in
  * the `T{...}` syntax, as well as being statically-inspectable before
  * evaluating the task graph. The Worker defines how it is evaluated, but it's
  * evaluation/caching/lifecycle are controlled by the `Evaluator`
  */
trait Worker[V] extends Task[V] with mill.util.Ctx.Loader[V] {
  val inputs = Nil
  def make(): V
  def evaluate(args: mill.util.Ctx) = args.load(this)
  def path = this.getClass.getCanonicalName.filter(_ != '$').split('.')
}
