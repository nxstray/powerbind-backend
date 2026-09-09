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
//   mvn test -Dtest=LogPageSeleniumTest -DexcludedGroups=
// or via .\run-selenium-test.ps1 (runs every @Tag("ui") suite).
//
// The account behind -Dselenium.username/-Dselenium.password MUST be an ADMIN:
// /log is router-guarded (requiresAdmin) and /api/admin/logs is hasRole('ADMIN') gated.
// Loki being down does NOT fail these tests — the page still renders and every
// client-side feature (chips, range, focus) keeps working; only TC-SEL-LOG-05 (row expand)
// needs actual log rows and skips gracefully when the window is empty.
@DisplayName("UI Test (log)")
@Tag("ui")
class LogPageSeleniumTest extends SeleniumTestBase {

    private static final By BACKEND_LABEL = By.xpath("//span[normalize-space()='Backend']");
    private static final By FRONTEND_LABEL = By.xpath("//span[normalize-space()='Frontend']");
    private static final By IOT_LABEL = By.xpath("//span[normalize-space()='IoT']");
    private static final By LOG_ROW = By.cssSelector("div.border-l-2");

    @BeforeEach
    void goToLogPage() {
        loginAsTestUser();
        driver.get(FRONTEND_URL + "/log");
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.visibilityOfElementLocated(BACKEND_LABEL));
    }

    // LogPanel root card — the nearest rounded-md ancestor above the label span
    private WebElement panelRoot(String labelXpath) {
        return driver.findElement(By.xpath(
                "(" + labelXpath + "/ancestor::div[contains(@class,'rounded-md')])[1]"));
    }

    // The focus/unfocus toggle is the ONLY <button> inside a LogPanel
    private WebElement panelFocusButton(String labelXpath) {
        return panelRoot(labelXpath).findElement(By.tagName("button"));
    }

    // Latest backend row, or null when the window is empty. Re-resolved on every
    // poll so the 3-second fetch cycle can never hand back a stale element.
    private WebElement lastBackendRow() {
        List<WebElement> rows = panelRoot("//span[normalize-space()='Backend']").findElements(LOG_ROW);
        return rows.isEmpty() ? null : rows.get(rows.size() - 1);
    }

    @Severity(SeverityLevel.CRITICAL)
    @Test
    @DisplayName("TC-SEL-LOG-01 Log page renders the volume chart, level chips, range selector and all three source panels")
    void log_shouldRenderChartChipsRangeAndPanels() {
        assertFalse(driver.findElements(By.xpath("//button[normalize-space()='Logs volume']")).isEmpty(),
                "The 'Logs volume' chart toggle should sit at the top of the page");
        assertTrue(driver.findElement(BACKEND_LABEL).isDisplayed(), "Backend panel should be visible");
        assertTrue(driver.findElement(FRONTEND_LABEL).isDisplayed(), "Frontend panel should be visible");
        assertTrue(driver.findElement(IOT_LABEL).isDisplayed(), "IoT panel should be visible");

        for (String level : new String[] {"ERROR", "WARN", "INFO", "DEBUG"}) {
            assertFalse(driver.findElements(By.xpath("//button[normalize-space()='" + level + "']")).isEmpty(),
                    "Level filter chip '" + level + "' should be rendered");
        }
        assertFalse(driver.findElements(By.xpath("//button[contains(normalize-space(),'1 jam')]")).isEmpty(),
                "Range dropdown should default to the '1 jam' window");
        attachScreenshot("log-01-page-loaded");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-LOG-02 Level chips toggle off and back on to filter the stream")
    void log_levelChips_shouldToggleFiltering() {
        WebElement errorChip = driver.findElement(By.xpath("//button[normalize-space()='ERROR']"));
        // Active label color is theme-dependent (LogPage.vue chipClass): text-red-400 on
        // the dark weather theme, text-red-600 on the light one. Assert the red prefix so
        // this passes in either theme — inactive is always text-zinc-500/text-gray-400.
        assertTrue(errorChip.getAttribute("class").contains("text-red-"),
                "ERROR chip should start active (red label tint)");

        errorChip.click();
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> !d.findElement(By.xpath("//button[normalize-space()='ERROR']"))
                        .getAttribute("class").contains("text-red-"));
        attachScreenshot("log-02-error-chip-toggled-off");

        driver.findElement(By.xpath("//button[normalize-space()='ERROR']")).click();
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> d.findElement(By.xpath("//button[normalize-space()='ERROR']"))
                        .getAttribute("class").contains("text-red-"));
        attachScreenshot("log-03-error-chip-toggled-back-on");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-LOG-03 Range dropdown lists every window, switches to 24h and closes after picking")
    void log_rangeDropdown_shouldSwitchWindow() {
        driver.findElement(By.xpath("//button[contains(normalize-space(),'1 jam')]")).click();

        WebElement option24h = new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(ExpectedConditions.visibilityOfElementLocated(
                        By.xpath("//div[contains(@class,'w-32')]//button[normalize-space()='24 jam']")));
        assertFalse(driver.findElements(
                        By.xpath("//div[contains(@class,'w-32')]//button[normalize-space()='15 menit']")).isEmpty(),
                "Dropdown should also offer the 15 minute window");
        attachScreenshot("log-04-range-dropdown-open");

        option24h.click();
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> !d.findElements(By.xpath("//button[contains(normalize-space(),'24 jam')]")).isEmpty());
        assertTrue(driver.findElements(By.cssSelector("div.w-32")).isEmpty(),
                "Dropdown panel should close after a window is picked");
        attachScreenshot("log-05-range-switched-to-24h");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-LOG-04 Focusing a panel switches to a single-column view, unfocusing restores all three")
    void log_panelFocus_shouldSwitchBetweenSingleAndTripleView() {
        panelFocusButton("//span[normalize-space()='Backend']").click();

        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> d.findElements(FRONTEND_LABEL).isEmpty());
        assertTrue(!driver.findElements(BACKEND_LABEL).isEmpty(), "The focused Backend panel should stay");
        assertTrue(driver.findElements(IOT_LABEL).isEmpty(), "Other panels should be hidden while focused");
        attachScreenshot("log-06-backend-panel-focused");

        panelFocusButton("//span[normalize-space()='Backend']").click(); // now the unfocus glyph
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> !d.findElements(FRONTEND_LABEL).isEmpty() && !d.findElements(IOT_LABEL).isEmpty());
        attachScreenshot("log-07-all-panels-restored");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-LOG-05 Clicking a log row expands it and clicking again collapses it")
    void log_rowClick_shouldExpandAndCollapse() {
        WebElement row = lastBackendRow();
        Assumptions.assumeTrue(row != null,
                "Skipped: no backend log rows in the current window (Loki down or a very quiet hour)");
        assertFalse(row.getAttribute("class").contains("bg-zinc-800/70"), "Row should start collapsed");

        row.click();
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> {
                    WebElement r = lastBackendRow();
                    return r != null && r.getAttribute("class").contains("bg-zinc-800/70");
                });
        attachScreenshot("log-08-row-expanded");

        lastBackendRow().click();
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> {
                    WebElement r = lastBackendRow();
                    return r != null && !r.getAttribute("class").contains("bg-zinc-800/70");
                });
        attachScreenshot("log-09-row-collapsed");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-LOG-06 Sidebar navigates Log -> ERD and the ERD canvas loads")
    void log_sidebar_shouldNavigateToErdPage() {
        driver.findElement(By.xpath("//aside//button[.//span[text()='ERD']]")).click();
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.urlContains("/erd"));
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> !d.findElements(By.cssSelector("[data-table]")).isEmpty());
        attachScreenshot("log-10-navigated-to-erd");
        assertTrue(driver.getCurrentUrl().contains("/erd"), "Sidebar ERD button should navigate to /erd");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-LOG-07 Logout from the Log page opens the confirm dialog and returns to the login page")
    void log_logout_shouldReturnToLoginPage() {
        // The sidebar logout is the icon button carrying the 'shrink-0' class — the
        // collapse toggle in the same footer has no such class
        driver.findElement(By.xpath("//aside//button[contains(@class,'shrink-0')]")).click();

        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.visibilityOfElementLocated(
                        By.xpath("//*[contains(text(),'Keluar dari Akun')]")));
        attachScreenshot("log-11-logout-confirm-dialog");

        driver.findElement(By.xpath("//button[normalize-space()='Keluar']")).click();
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.urlContains("/login"));
        attachScreenshot("log-12-logged-out");
        assertTrue(driver.getCurrentUrl().contains("/login"),
                "Confirming logout should redirect back to the login page");
    }
}