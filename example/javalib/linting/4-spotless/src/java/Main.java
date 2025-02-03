import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import javax.swing.JFrame;
import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import javax.swing.JPanel;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
public class Main
{
    public static void main(String[]args){
    System.out.println("Hello, world!");
    BigDecimal price = new BigDecimal("19.99");
    BigDecimal quantity = new BigDecimal("3");
    BigDecimal subtotal = price.multiply(quantity);System.out.println("Subtotal: $" + subtotal);
    int x= 10;int y=20;System.out.println("Sum: "+(x+y));}
}
