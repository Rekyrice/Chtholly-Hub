package com.chtholly.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class PostAgentDependencyTest {

    @Test
    void postPackageDoesNotDependOnAgentPackage() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.chtholly");

        noClasses()
                .that().resideInAPackage("com.chtholly.post..")
                .should().dependOnClassesThat().resideInAPackage("com.chtholly.agent..")
                .check(productionClasses);
    }
}
