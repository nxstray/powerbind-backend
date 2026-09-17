package com.powerbind.backend.selenium;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Excluded from normal `mvn test` runs via the "ui" tag — requires backend + frontend
// already running. Run explicitly with:
//   mvn test -Dtest=MetricsPageSeleniumTest -DexcludedGroups=
// or via .\run-selenium-test.ps1 (runs every @Tag("ui") suite).
//
// The account behind -Dselenium.username/-Dselenium.password MUST be an ADMIN:
// /metrics is router-guarded and /api/admin/metrics/* is hasRole('ADMIN') gated.
// Prometheus being down does NOT fail these tests — the page still renders its
// header, range selector and the Ask Gemono panel regardless of chart data.
@DisplayName("UI Test (metrics)")
@Tag("ui")
class MetricsPageSeleniumTest extends SeleniumTestBase {

    private static final By PAGE_TITLE = By.xpath("//h1[normalize-space()='System Metrics']");
    private static final By ASK_TRIGGER = By.xpath("//header//button[normalize-space()='Ask Gemono']");
    private static final By ASK_PANEL = By.xpath("//aside[.//p[normalize-space()='Ask Gemono']]");
    private static final By RANGE_DROPDOWN = By.xpath("//div[@data-range-dd]/button");
    private static final By METRIC_DROPDOWN = By.xpath("//div[@data-metric-dd]/button");

    @BeforeEach
    void goToMetricsPage() {
        loginAsTestUser();
        driver.get(FRONTEND_URL + "/metrics");
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.visibilityOfElementLocated(PAGE_TITLE));
    }

    private WebElement rangeButton() {
        return driver.findElement(RANGE_DROPDOWN);
    }

    private WebElement askPanel() {
        return driver.findElement(ASK_PANEL);
    }

    // The typewriter greeting takes ~1s to spell out — wait until it starts
    private void waitForGreeting() {
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> d.findElement(ASK_PANEL).getText().contains("Halo,"));
    }

    @Severity(SeverityLevel.CRITICAL)
    @Test
    @DisplayName("TC-SEL-MET-01 Metrics page renders header, both chart panels and the Ask Gemono trigger")
    void metrics_shouldRenderHeaderChartsAndAskTrigger() {
        assertTrue(driver.findElement(PAGE_TITLE).isDisplayed(), "Page header should be visible");
        assertTrue(driver.findElement(ASK_TRIGGER).isDisplayed(), "Ask Gemono trigger should be visible");
        assertTrue(rangeButton().getText().contains("Last 1 hour"),
                "Range selector should default to the last hour");

        // The two fixed charts: memory chart title + the explorer dropdown entry
        assertTrue(driver.findElements(By.xpath("//*[normalize-space()='jvm_memory_used_bytes']"))
                .size() >= 2, "Memory chart title and its explorer entry should both render");
        assertTrue(!driver.findElements(METRIC_DROPDOWN).isEmpty(),
                "Metric explorer dropdown should be present");
        attachScreenshot("metrics-01-page-rendered");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-MET-02 Metric explorer dropdown filters by search and selecting a metric updates the label")
    void metrics_explorerDropdown_shouldFilterAndSelectMetric() {
        driver.findElement(METRIC_DROPDOWN).click();
        WebElement search = new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(ExpectedConditions.visibilityOfElementLocated(
                        By.xpath("//div[@data-metric-dd]//input")));
        search.sendKeys("cpu");

        List<WebElement> options = driver.findElements(
                By.xpath("//div[@data-metric-dd]//div[contains(@class,'overflow-y-auto')]//button"));
        Assumptions.assumeTrue(!options.isEmpty(),
                "Skipped: no metrics matched 'cpu' (Prometheus up but empty TSDB?)");
        assertTrue(options.stream().allMatch(o -> o.getText().toLowerCase().contains("cpu")),
                "Every listed option should match the search term");
        attachScreenshot("metrics-02-explorer-filtered");

        String picked = options.get(0).getText();
        options.get(0).click();
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> driver.findElement(METRIC_DROPDOWN).getText().contains(picked));
        assertTrue(driver.findElement(METRIC_DROPDOWN).getText().contains(picked),
                "Explorer dropdown label should show the selected metric");
        attachScreenshot("metrics-03-metric-selected");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-MET-03 Changing the time range updates the selector label and reloads the charts")
    void metrics_rangeSelector_shouldChangeLabelAndReload() {
        rangeButton().click();
        WebElement sixHours = new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(ExpectedConditions.visibilityOfElementLocated(
                        By.xpath("//div[@data-range-dd]//button[normalize-space()='Last 6 hours']")));
        sixHours.click();

        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> rangeButton().getText().contains("Last 6 hours"));
        assertTrue(rangeButton().getText().contains("Last 6 hours"),
                "Range label should update to the chosen option");
        attachScreenshot("metrics-04-range-changed");
    }

    @Severity(SeverityLevel.CRITICAL)
    @Test
    @DisplayName("TC-SEL-MET-04 Ask Gemono opens the side panel, hides the trigger, and closes back via X")
    void metrics_askGemono_shouldOpenHideTriggerAndClose() {
        driver.findElement(ASK_TRIGGER).click();

        WebElement panel = new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(ExpectedConditions.visibilityOfElementLocated(ASK_PANEL));
        assertTrue(panel.getAttribute("style").contains("width: 320px"),
                "Panel should expand to its fixed 320px width");
        assertTrue(driver.findElements(ASK_TRIGGER).isEmpty(),
                "Header trigger should disappear while the panel is open");
        attachScreenshot("metrics-05-panel-open");

        // the X close button is the only button in the panel's header row
        driver.findElement(By.xpath(
                "//aside[.//p[normalize-space()='Ask Gemono']]//div[contains(@class,'border-b')]//button")).click();
        // NOTE: use ASK_PANEL (not cssSelector("aside")) — the page has TWO asides
        // (nav sidebar first, Ask Gemono panel second) and only the panel's inline
        // width animates between 320px and 0px.
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> askPanel().getAttribute("style").contains("width: 0px"));
        assertFalse(askPanel().getAttribute("style").contains("320px"),
                "Panel should collapse back to zero width");
        attachScreenshot("metrics-06-panel-closed");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-MET-05 Ask Gemono empty state plays the typewriter greeting and offers an input")
    void metrics_askGemono_shouldPlayGreetingAndShowInput() {
        driver.findElement(ASK_TRIGGER).click();
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(ExpectedConditions.visibilityOfElementLocated(ASK_PANEL));

        waitForGreeting();
        assertTrue(askPanel().getText().contains("Halo,"),
                "Typewriter greeting should start spelling out inside the panel");
        assertTrue(!driver.findElements(By.xpath("//aside//input")).isEmpty(),
                "Question input should be available in the panel");
        attachScreenshot("metrics-07-greeting-typewriter");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-MET-06 Sending a question shows the user bubble and streams an AI reply")
    void metrics_askGemono_shouldSendQuestionAndStreamReply() {
        driver.findElement(ASK_TRIGGER).click();
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(ExpectedConditions.visibilityOfElementLocated(ASK_PANEL));

        WebElement input = driver.findElement(By.xpath("//aside//input"));
        input.sendKeys("Sebut singkat: apa itu jvm_memory_used_bytes?");
        driver.findElement(By.xpath("//aside//form//button[@type='submit']")).click();

        // user bubble — blue rounded pill containing the sent text
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> !d.findElements(
                        By.xpath("//aside//p[contains(@class,'bg-[#0f8cd5]')][contains(.,'Sebut singkat')]")
                ).isEmpty());
        attachScreenshot("metrics-08-question-sent");

        // assistant reply eventually appears below it (streaming cursor may still blink)
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(d -> d.findElement(ASK_PANEL).getText().length() > "Sebut singkat: apa itu jvm_memory_used_bytes?".length());
        attachScreenshot("metrics-09-reply-streamed");
    }
}
