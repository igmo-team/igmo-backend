package com.igmo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.igmo")
class AdminArchitectureTest {

    @ArchTest
    static final ArchRule admin_does_not_depend_on_game_or_shared_web_implementation = noClasses()
            .that().resideInAPackage("..admin..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "com.igmo.service..",
                    "com.igmo.domain..",
                    "com.igmo.monitoring..",
                    "com.igmo.store..",
                    "com.igmo.web..");
}
