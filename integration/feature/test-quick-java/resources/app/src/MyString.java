package app;

import lib.Combinator;
import lib.DefaultValue;

public class MyString {
    private final String value;

    public MyString(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof MyString) {
            return value.equals(((MyString) obj).value);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "MyString(" + value + ")";
    }

    public static final Combinator<MyString> COMBINATOR = new Combinator<MyString>() {
        @Override
        public MyString combine(MyString a, MyString b) {
            return new MyString(a.getValue() + b.getValue());
        }
    };

    public static final DefaultValue<MyString> DEFAULT_VALUE = new DefaultValue<MyString>() {
        @Override
        public MyString defaultValue() {
            return new MyString("");
        }
    };

    public static MyString combine(MyString a, MyString b, MyString c) {
        return COMBINATOR.combine2(a, b, c);
    }

    public static MyString getDefaultValue() {
        return DEFAULT_VALUE.defaultValue();
    }
}
