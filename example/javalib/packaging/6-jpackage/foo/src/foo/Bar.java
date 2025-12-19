package foo;

import static foo.Foo.LOGGER;

import java.awt.*;
import java.io.IOException;
import javax.swing.*;

public class Bar {

  public static boolean isCI() {
    String[] ciEnvironments = {
      "CI",
      "CONTINUOUS_INTEGRATION",
      "JENKINS_URL",
      "TRAVIS",
      "CIRCLECI",
      "GITHUB_ACTIONS",
      "GITLAB_CI",
      "BITBUCKET_PIPELINE",
      "TEAMCITY_VERSION"
    };

    for (String env : ciEnvironments) {
      if (System.getenv(env) != null) {
        return true;
      }
    }

    return false;
  }

  public static void main(String[] args) throws IOException {
    // Needed because Swing GUIs don't work in headless CI environments
    if (isCI()) {
      Foo.readConf();
      LOGGER.info("Hello World application started successfully");
      System.exit(0);
    }

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
