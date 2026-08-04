package com.chtholly;

import com.chtholly.seed.ContentPackCliLauncher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bootstraps the Chtholly Hub server or its dedicated content-pack command-line lifecycle.
 */
@SpringBootApplication
public class ChthollyApplication {

    /**
     * Starts either the dedicated content-pack CLI lifecycle or the normal web application.
     *
     * @param args application arguments
     */
    public static void main(String[] args) {
        if (ContentPackCliLauncher.isContentPackCli(args)) {
            ContentPackCliLauncher.launch(ChthollyApplication.class, args, System::exit);
            return;
        }
        SpringApplication.run(ChthollyApplication.class, args);
    }
}
