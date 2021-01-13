package mill.scalanativelib.api;

import sbt.testing.Framework;

// getFramework result wrapper because we need to return a tuple
public class GetFrameworkResult{
    public final Runnable close;
    public final Framework framework;

    public GetFrameworkResult(Runnable close0, Framework framework0){
        close = close0;
        framework = framework0;
    }
}
