import mill._

def checkJvmOpts() = T.command {
  val prop = System.getProperty("PROPERTY_PROPERLY_SET_VIA_JVM_OPTS")
  if (prop != "value-from-file") sys.error("jvm-opts not correctly applied, value was: " + prop)
  ()
}
