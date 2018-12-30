import mill._, scalalib._

trait TModule extends SbtModule {
  def scalaVersion = "2.12.7"
}

object foo extends Module {
  object common extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq(foo.common.one)
    }
    object three extends TModule {
      def moduleDeps = Seq(foo.common.two)
    }
  }
  object domain extends Module {
    object one extends TModule {
      def moduleDeps = Seq(foo.common.one)
    }
    object two extends TModule {
      def moduleDeps = Seq(foo.domain.one)
    }
    object three extends TModule {
      def moduleDeps = Seq(foo.domain.two)
    }
  }
  object server extends Module {
    object one extends TModule {
      def moduleDeps = Seq(foo.domain.three)
    }
    object two extends TModule {
      def moduleDeps = Seq(foo.server.one)
    }
    object three extends TModule {
      def moduleDeps = Seq(foo.server.two)
    }
  }
}

object bar extends Module {
  object common extends Module {
    object one extends TModule {
      def moduleDeps = Seq(foo.common.three)
    }
    object two extends TModule {
      def moduleDeps = Seq(bar.common.one)
    }
    object three extends TModule {
      def moduleDeps = Seq(bar.common.two)
    }
  }
  object domain extends Module {
    object one extends TModule {
      def moduleDeps = Seq(foo.domain.three)
    }
    object two extends TModule {
      def moduleDeps = Seq(bar.domain.one)
    }
    object three extends TModule {
      def moduleDeps = Seq(bar.domain.two)
    }
  }
  object server extends Module {
    object one extends TModule {
      def moduleDeps = Seq(foo.server.one)
    }
    object two extends TModule {
      def moduleDeps = Seq(bar.server.one)
    }
    object three extends TModule {
      def moduleDeps = Seq(bar.server.two)
    }
  }
}

object ham extends Module {
  object common extends Module {
    object one extends TModule {
      def moduleDeps = Seq(bar.common.one)
    }
    object two extends TModule {
      def moduleDeps = Seq(bar.common.two)
    }
    object three extends TModule {
      def moduleDeps = Seq(bar.common.three)
    }
  }
  object domain extends Module {
    object one extends TModule {
      def moduleDeps = Seq(bar.domain.three)
    }
    object two extends TModule {
      def moduleDeps = Seq(bar.domain.two, ham.common.three)
    }
    object three extends TModule {
      def moduleDeps = Seq(bar.domain.two)
    }
  }
  object server extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq()
    }
    object three extends TModule {
      def moduleDeps = Seq()
    }
  }
}

object eggs extends Module {
  object common extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq()
    }
    object three extends TModule {
      def moduleDeps = Seq()
    }
  }
  object domain extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq()
    }
    object three extends TModule {
      def moduleDeps = Seq()
    }
  }
  object server extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq()
    }
    object three extends TModule {
      def moduleDeps = Seq()
    }
  }
}

object salt extends Module {
  object common extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq()
    }
    object three extends TModule {
      def moduleDeps = Seq()
    }
  }
  object domain extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq()
    }
    object three extends TModule {
      def moduleDeps = Seq()
    }
  }
  object server extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq()
    }
    object three extends TModule {
      def moduleDeps = Seq()
    }
  }
}

object pepper extends Module {
  object common extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq()
    }
    object three extends TModule {
      def moduleDeps = Seq()
    }
  }
  object domain extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq()
    }
    object three extends TModule {
      def moduleDeps = Seq()
    }
  }
  object server extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq()
    }
    object three extends TModule {
      def moduleDeps = Seq()
    }
  }
}

object oregano extends Module {
  object common extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq()
    }
    object three extends TModule {
      def moduleDeps = Seq()
    }
  }
  object domain extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq()
    }
    object three extends TModule {
      def moduleDeps = Seq()
    }
  }
  object server extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq()
    }
    object three extends TModule {
      def moduleDeps = Seq()
    }
  }
}

object rosmarin extends Module {
  object common extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq()
    }
    object three extends TModule {
      def moduleDeps = Seq()
    }
  }
  object domain extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq()
    }
    object three extends TModule {
      def moduleDeps = Seq()
    }
  }
  object server extends Module {
    object one extends TModule {
      def moduleDeps = Seq()
    }
    object two extends TModule {
      def moduleDeps = Seq()
    }
    object three extends TModule {
      def moduleDeps = Seq()
    }
  }
}
