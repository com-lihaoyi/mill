package foo;

import java.awt.*;
import java.util.logging.Logger;
import javax.swing.*;

public class Bar {
  private static final Logger LOGGER = Logger.getLogger(Bar.class.getName());

  public static void main(String[] args) {
    // Use SwingUtilities.invokeLater to ensure thread safety
    SwingUtilities.invokeLater(() -> {
      try {
        // Set a modern look and feel
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());

        // Create the main window
        JFrame frame = new JFrame("Hello World");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(300, 200);
        frame.setLocationRelativeTo(null);

        // Create a label from the application.conf
        JLabel label = new JLabel(Foo.readConf(), SwingConstants.CENTER);
        label.setFont(new Font("Arial", Font.BOLD, 16));

        // Add the label to the frame
        frame.getContentPane().add(label);

        // Make the frame visible
        frame.setVisible(true);

        LOGGER.info("Hello World application started successfully");
      } catch (Exception e) {
        LOGGER.severe("Error initializing application: " + e.getMessage());
      }
    });
  }
}
