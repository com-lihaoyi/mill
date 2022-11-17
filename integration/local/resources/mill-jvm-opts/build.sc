import mill._
import java.lang.management.ManagementFactory
import scala.jdk.CollectionConverters._

def checkJvmOpts() = T.command {
  val prop = System.getProperty("PROPERTY_PROPERLY_SET_VIA_JVM_OPTS")
  if (prop != "value-from-file") sys.error("jvm-opts not correctly applied, value was: " + prop)
  val runtime = ManagementFactory.getRuntimeMXBean()
  val args = runtime.getInputArguments().asScala.toSet
  if (!args.contains("-DPROPERTY_PROPERLY_SET_VIA_JVM_OPTS=value-from-file")) {
    sys.error("jvm-opts not correctly applied, args were: " + args.mkString)
  }
  if (!args.contains("-Xss120m")) {
    sys.error("jvm-opts not correctly applied, args were: " + args.mkString)
  }
  ()
}
