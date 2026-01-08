package lib;

public interface Combinator<T> {
    T combine(T a, T b);

    default T combine2(T a, T b, T c) {
        return combine(combine(a, b), c);
    }
}
