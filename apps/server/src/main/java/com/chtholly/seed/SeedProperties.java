package com.chtholly.seed;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Seed runner properties.
 */
@Data
@ConfigurationProperties(prefix = "seed")
public class SeedProperties {
    private boolean enabled = false;
    private String mode = "full";
    private boolean dryRun = false;
}
