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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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

    private static final By CHANGE_PASSWORD_MODAL_TITLE =
            By.xpath("//h3[text()='Ganti password default']");

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

        dismissMandatoryPasswordChangeIfPresent();
    }

    // Suites other than ChangePasswordModalSeleniumTest assume the test account already
    // has its own password and can interact with the dashboard right after login. If
    // must_change_password is still true for it (e.g. right after the V9 migration ships,
    // or right after ChangePasswordModalSeleniumTest's @BeforeEach flips it back to true)
    // the mandatory modal covers the whole screen and every click below it fails with an
    // intercepted-click error. This clears the flag directly in the database — not through
    // the UI — so those suites stay focused on what they're actually testing; the modal's
    // own behavior is exercised separately by ChangePasswordModalSeleniumTest.
    private void dismissMandatoryPasswordChangeIfPresent() {
        boolean modalShowing = !driver.findElements(CHANGE_PASSWORD_MODAL_TITLE).isEmpty();
        if (!modalShowing) return;

        setMustChangePassword(TEST_USERNAME, false);
        driver.navigate().refresh();

        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.invisibilityOfElementLocated(CHANGE_PASSWORD_MODAL_TITLE));
    }

    // Direct DB access, deliberately bypassing the API — there is no endpoint to flip this
    // flag back to true (and there shouldn't be one in production: it would let any user
    // force another user's account back into the mandatory flow). Reuses the same Postgres
    // instance the app itself runs against; skipped gracefully if connection details aren't
    // supplied, same pattern as the username/password assumptions above.
    //   mvn test ... -Ddb.username=xxx -Ddb.password=yyy [-Ddb.url=jdbc:postgresql://host:5432/powerbind]
    protected void setMustChangePassword(String username, boolean value) {
        String dbUrl = System.getProperty("db.url", "jdbc:postgresql://localhost:5432/powerbind");
        String dbUser = System.getProperty("db.username");
        String dbPassword = System.getProperty("db.password");

        Assumptions.assumeTrue(dbUser != null && !dbUser.isBlank(),
                "Skipped: -Ddb.username not provided (needed to toggle must_change_password directly)");

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE users SET must_change_password = ? WHERE username = ?")) {
            stmt.setBoolean(1, value);
            stmt.setString(2, username);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update must_change_password for " + username, e);
        }
    }

    protected void attachScreenshot(String name) {
        byte[] screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
        Allure.addAttachment(name, new ByteArrayInputStream(screenshot));
    }
}