package com.chtholly.seed;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Seed runner properties.
 */
@Data
@Component
@ConfigurationProperties(prefix = "seed")
public class SeedProperties {
    private boolean enabled = false;
    private String mode = "full";
    private boolean dryRun = false;
}
