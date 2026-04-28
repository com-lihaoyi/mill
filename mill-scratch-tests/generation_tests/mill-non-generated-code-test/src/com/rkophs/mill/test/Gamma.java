package com.rkophs.mill.test;

public class Gamma {
    private final int someInteger;
    private final Delta delta;

    public Gamma(int someInteger, Delta delta) {
        this.someInteger = someInteger;
        this.delta = delta;
    }

    public int getSomeInteger() {
        return someInteger;
    }

    public Delta getDelta() {
        return delta;
    }
}
