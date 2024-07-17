import mill._

def getUpickleVersion = T{
  classOf[upickle.Api].getProtectionDomain.getCodeSource.getLocation.toString
}