package app;

import lib.Combinator;
import lib.DefaultValue;

public class MyNumber {
    private final int value;

    public MyNumber(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof MyNumber) {
            return value == ((MyNumber) obj).value;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }

    @Override
    public String toString() {
        return "MyNumber(" + value + ")";
    }

    public static final Combinator<MyNumber> COMBINATOR = new Combinator<MyNumber>() {
        @Override
        public MyNumber combine(MyNumber a, MyNumber b) {
            return new MyNumber(a.getValue() + b.getValue());
        }
    };

    public static final DefaultValue<MyNumber> DEFAULT_VALUE = new DefaultValue<MyNumber>() {
        @Override
        public MyNumber defaultValue() {
            return new MyNumber(0);
        }
    };

    public static MyNumber combine(MyNumber a, MyNumber b, MyNumber c) {
        return COMBINATOR.combine2(a, b, c);
    }

    public static MyNumber getDefaultValue() {
        return DEFAULT_VALUE.defaultValue();
    }
}
