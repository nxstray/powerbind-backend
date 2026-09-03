package com.powerbind.backend.selenium;

import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Exercises the mandatory change-password modal that appears when must_change_password
// is true for the logged-in account. Each test forces the test account back into that
// state directly in the database (see SeleniumTestBase.setMustChangePassword) so this
// suite is repeatable regardless of whether the account already completed the flow —
// there is no API to re-arm the flag, only to clear it, by design.
@Tag("ui")
class ChangePasswordModalSeleniumTest extends SeleniumTestBase {

    private static final String TEMP_PASSWORD = "Temp-Selenium-Pass-1!";
    private static final By MODAL_TITLE = By.xpath("//h3[text()='Ganti password default']");

    // Only true for the one test that actually completes the change — lets @AfterEach
    // restore the real password without probing the account with a doomed login attempt
    // (and its failed-attempt counter) on every other test in this class.
    private boolean passwordWasChanged = false;

    @BeforeEach
    void forcePasswordChangeRequired() {
        passwordWasChanged = false;
        setMustChangePassword(TEST_USERNAME, true);
    }

    @AfterEach
    void restoreOriginalPasswordIfChanged() {
        if (!passwordWasChanged) return;

        String token = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("username", TEST_USERNAME, "password", TEMP_PASSWORD))
                .post(BACKEND_URL + "/api/auth/login")
                .jsonPath().getString("data.accessToken");

        if (token == null) return;

        RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(Map.of("currentPassword", TEMP_PASSWORD, "newPassword", TEST_PASSWORD))
                .put(BACKEND_URL + "/api/auth/change-password");
    }

    private void loginAndWaitForModal() {
        driver.get(FRONTEND_URL + "/login");

        // LoginPage.vue has an entry transition (the animated "ring" decoration) —
        // wait for the username input to actually be interactable instead of assuming
        // it's ready the instant it exists in the DOM, otherwise sendKeys can hit it
        // mid-transition and throw "element not interactable".
        WebElement usernameInput = new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.elementToBeClickable(By.cssSelector("input[type='text']")));
        usernameInput.sendKeys(TEST_USERNAME);

        WebElement passwordInput = new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.elementToBeClickable(By.cssSelector("input[type='password']")));
        passwordInput.sendKeys(TEST_PASSWORD);

        // LoginPage.vue's submit control is <input type="submit">, not a <button> —
        // SUBMIT_CONTROL (from SeleniumTestBase) matches either markup.
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.elementToBeClickable(SUBMIT_CONTROL))
                .click();

        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.visibilityOfElementLocated(MODAL_TITLE));
    }

    private void fillForm(String current, String next, String confirm) {
        // Wait for all 3 password fields to exist AND be part of a fully-mounted modal —
        // modal has a 0.15s fade-in transition, so grabbing elements the instant the
        // title becomes visible can still race the form's own readiness.
        List<WebElement> inputs = new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> {
                    List<WebElement> found = d.findElements(By.cssSelector("input[type='password']"));
                    return (found.size() == 3 && found.stream().allMatch(WebElement::isDisplayed)) ? found : null;
                });

        inputs.get(0).sendKeys(current);
        inputs.get(1).sendKeys(next);
        inputs.get(2).sendKeys(confirm);
    }

    private void submit() {
        // The change-password modal itself uses a real <button type="submit">, so the
        // plain button selector is correct here — this is not the LoginPage form.
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.elementToBeClickable(By.cssSelector("button[type='submit']")))
                .click();
    }

    @Severity(SeverityLevel.CRITICAL)
    @Test
    void modal_shouldAppear_whenAccountStillOnDefaultPassword() {
        loginAndWaitForModal();
        attachScreenshot("changepw-01-modal-shown");

        assertTrue(driver.findElement(MODAL_TITLE).isDisplayed(),
                "Modal should appear right after login when must_change_password is true");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    void modal_shouldBlockAccessToDashboard_untilCompleted() {
        loginAndWaitForModal();

        // Dashboard content renders underneath, but the modal's fixed full-screen backdrop
        // intercepts any click before it reaches the page behind it.
        assertThrows(ElementClickInterceptedException.class,
                () -> driver.findElement(By.cssSelector("button[title='Logout']")).click(),
                "Clicks on the dashboard behind the modal should be intercepted");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    void modal_shouldShowError_whenConfirmationDoesNotMatch() {
        loginAndWaitForModal();
        fillForm(TEST_PASSWORD, "NewPassword123", "DifferentPassword123");
        submit();
        attachScreenshot("changepw-02-mismatch-error");

        assertTrue(driver.getPageSource().contains("tidak cocok"),
                "Should show a client-side mismatch error");
        assertTrue(driver.findElement(MODAL_TITLE).isDisplayed(), "Modal should stay open");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    void modal_shouldShowError_whenCurrentPasswordIsWrong() {
        loginAndWaitForModal();
        fillForm("wrong-current-password", "NewPassword123", "NewPassword123");
        submit();

        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("body"), "incorrect"));
        attachScreenshot("changepw-03-wrong-current-password");

        assertTrue(driver.findElement(MODAL_TITLE).isDisplayed(), "Modal should stay open on server-side rejection");
    }

    @Severity(SeverityLevel.CRITICAL)
    @Test
    void modal_shouldCloseAndUnlockDashboard_onSuccessfulChange() {
        loginAndWaitForModal();
        fillForm(TEST_PASSWORD, TEMP_PASSWORD, TEMP_PASSWORD);
        submit();
        passwordWasChanged = true; // mark before the assertion so teardown restores it even if this fails

        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.invisibilityOfElementLocated(MODAL_TITLE));
        attachScreenshot("changepw-04-modal-closed");

        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.visibilityOfElementLocated(By.xpath("//h1[text()='Overview']")));
    }
}