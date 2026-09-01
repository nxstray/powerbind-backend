package com.powerbind.backend.selenium;

import io.github.bonigarcia.wdm.WebDriverManager;
import io.qameta.allure.Allure;
import io.restassured.RestAssured;
import io.restassured.response.Response;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

// Shared setup for UI tests against the real, already-running frontend + backend.
// Requires both servers started manually before running these tests:
//   Backend:  mvn spring-boot:run   (or the deployed dev API)
//   Frontend: npm run dev           (default: http://localhost:5173)
//
// Credentials are NEVER hardcoded — pass them via system properties:
//   mvn test -Dgroups=ui -DexcludedGroups= -Dselenium.username=xxx -Dselenium.password=yyy
public abstract class SeleniumTestBase {

    protected static final String FRONTEND_URL =
            System.getProperty("frontend.url", "http://localhost:5173");

    protected static final String BACKEND_URL =
        System.getProperty("backend.url", "http://localhost:8045");

    protected static final String TEST_USERNAME = System.getProperty("selenium.username");
    protected static final String TEST_PASSWORD = System.getProperty("selenium.password");

    protected WebDriver driver;

    @BeforeEach
    void baseSetUp() {
        // Fail fast with a clear message instead of a confusing NPE mid-test
        Assumptions.assumeTrue(TEST_USERNAME != null && !TEST_USERNAME.isBlank(),
                "Skipped: -Dselenium.username not provided");
        Assumptions.assumeTrue(TEST_PASSWORD != null && !TEST_PASSWORD.isBlank(),
                "Skipped: -Dselenium.password not provided");

        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--window-size=1366,900", "--no-sandbox");
        driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
    }

    @AfterEach
    void baseTearDown() {
        if (driver != null) driver.quit();
    }

    // Clears every conversation for this user via the real API so the AgentPage empty-state
    // greeting is guaranteed to render, regardless of leftover data from prior runs.
    // History moved from a single flat list to per-conversation threads, so this now lists
    // conversations and deletes each one instead of hitting a single bulk-clear endpoint.
    protected void clearBackendChatHistory() {
        String token = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("username", TEST_USERNAME, "password", TEST_PASSWORD))
                .post(BACKEND_URL + "/api/auth/login")
                .jsonPath().getString("data.accessToken");

        if (token == null) return;

        Response conversations = RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .get(BACKEND_URL + "/api/agent/conversations");

        List<String> ids = conversations.jsonPath().getList("data.id");
        if (ids == null) return;

        for (String id : ids) {
            RestAssured.given()
                    .header("Authorization", "Bearer " + token)
                    .delete(BACKEND_URL + "/api/agent/conversations/" + id);
        }
    }

    protected void loginAsTestUser() {
        driver.get(FRONTEND_URL + "/login");
        driver.findElement(By.cssSelector("input[type='text']")).sendKeys(TEST_USERNAME);
        driver.findElement(By.cssSelector("input[type='password']")).sendKeys(TEST_PASSWORD);
        driver.findElement(By.cssSelector("button[type='submit']")).click();

        // Dashboard route path is "/" (root), not "/dashboard" — wait for the login
        // page to disappear instead of checking for a URL fragment that doesn't exist.
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.not(ExpectedConditions.urlContains("/login")));
    }

    protected void attachScreenshot(String name) {
        byte[] screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
        Allure.addAttachment(name, new ByteArrayInputStream(screenshot));
    }
}