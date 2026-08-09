package com.powerbind.backend.cucumber.runner;

import org.junit.platform.suite.api.*;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("cucumber")
@ConfigurationParameter(key = "cucumber.plugin", value = "pretty, io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm")
@ConfigurationParameter(key = "cucumber.glue", value = "com.powerbind.backend.cucumber.steps")
public class CucumberTestRunner {
    // Runs all .feature files under src/test/resources/cucumber
}
