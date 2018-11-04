import mill._

def selfDest = T { T.ctx().dest / os.up / os.up }
