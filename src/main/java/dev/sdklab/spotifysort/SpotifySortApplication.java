package dev.sdklab.spotifysort;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;

@SpringBootApplication(exclude = {SecurityAutoConfiguration.class})
public class SpotifySortApplication {

  public static void main(String[] args) {
    SpringApplication.run(SpotifySortApplication.class, args);
  }

}
