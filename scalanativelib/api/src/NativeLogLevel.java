package mill.scalanativelib.api;
public enum NativeLogLevel {
    Error(200),
    Warn(300),
    Info(400),
    Debug(500),
    Trace(600);

    public int value;
    NativeLogLevel(int value0){
        this.value = value0;
    }
}
