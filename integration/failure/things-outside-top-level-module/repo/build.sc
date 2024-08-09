import mill._

def invalidTarget = Task { "..." }

object invalidModule extends Module

object bar extends RootModule