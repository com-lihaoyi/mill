import mill._

def pub = task {
  priv()
}

private def priv = task {
  "priv"
}

object foo extends Module {
  def bar = task {
    baz()
  }
}

private def baz = task {
  "bazOuter"
}

object qux extends Module {
  object foo extends Module {
    def bar = task {
      baz()
    }
  }
  private def baz = task {
    "bazInner"
  }
}

object cls extends cls
class cls extends Module {
  object foo extends Module {
    def bar = task {
      baz()
    }
  }

  private def baz = task {
    "bazCls"
  }
}
