package coding;

// https://checkstyle.org/checks/coding/emptystatement.html#EmptyStatement
public class EmptyStatement {

    public class Example1 {
        public void foo() {
            int i = 5;
            if(i > 3); // violation
            i++;
            for (i = 0; i < 5; i++); // violation
            i++;
            while (i > 10)
                i++;
        }
    }
}
