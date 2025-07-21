package mill.javalib.zinc

/** Entry point for the Zinc worker subprocess. */
object ZincWorkerMain {
  def main(args: Array[String]): Unit = ZincWorkerRpcServer().run()
}
