public class Outer {
    void method(){
        Inner ic = new Inner();//Causes generation of accessor class
    }
    public class Inner {
        private Inner(){}
    }
}
