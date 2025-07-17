package mill.javalib.zinc
import mill.javalib.worker.JavaRuntimeOptions

trait ZincWorkerFactory {
  def apply(javaHome: Option[os.Path], javaRuntimeOptions: JavaRuntimeOptions): ZincWorkerApi
}
