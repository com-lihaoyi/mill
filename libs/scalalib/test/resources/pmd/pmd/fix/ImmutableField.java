public class ImmutableField {
    private final int x;

    public ImmutableField() {
        x = 7;
    }

    public void foo() {
        int a = x + 2;
    }
}
