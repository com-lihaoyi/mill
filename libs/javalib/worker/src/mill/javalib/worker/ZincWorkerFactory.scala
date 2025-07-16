package mill.javalib.worker

trait ZincWorkerFactory {
  def apply(javaHome: Option[os.Path], javaRuntimeOptions: JavaRuntimeOptions): ZincWorkerApi
}
