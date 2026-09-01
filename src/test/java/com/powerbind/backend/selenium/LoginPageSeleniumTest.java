package com.powerbind.backend.selenium;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Excluded from normal `mvn test` runs via the "ui" tag — requires backend + frontend
// already running. Run explicitly with: mvn test -Dtest=LoginPageSeleniumTest -DexcludedGroups=
@Tag("ui")
class LoginPageSeleniumTest extends SeleniumTestBase {

    @Severity(SeverityLevel.CRITICAL)
    @Test
    void login_withValidCredentials_shouldRedirectToDashboard() {
        driver.get(FRONTEND_URL + "/login");
        attachScreenshot("login-01-page-loaded");

        driver.findElement(By.cssSelector("input[type='text']")).sendKeys(TEST_USERNAME);
        driver.findElement(By.cssSelector("input[type='password']")).sendKeys(TEST_PASSWORD);
        attachScreenshot("login-02-form-filled");

        driver.findElement(By.cssSelector("button[type='submit']")).click();

        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.not(ExpectedConditions.urlContains("/login")));
        attachScreenshot("login-03-redirected-away-from-login");

        assertTrue(!driver.getCurrentUrl().contains("/login"),
                "Should redirect away from login page after successful login");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    void login_withInvalidCredentials_shouldShowErrorMessage() {
        driver.get(FRONTEND_URL + "/login");

        driver.findElement(By.cssSelector("input[type='text']")).sendKeys(TEST_USERNAME);
        driver.findElement(By.cssSelector("input[type='password']")).sendKeys("wrong-password-xyz");
        driver.findElement(By.cssSelector("button[type='submit']")).click();

        // errorMsg is rendered as a <p class="text-sm text-red-500"> right after a failed attempt
        WebElement errorMsg = new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("p.text-red-500")));
        attachScreenshot("login-invalid-error-shown");

        assertTrue(errorMsg.isDisplayed() && !errorMsg.getText().isBlank(),
                "An error message should be shown on invalid login");
    }
}