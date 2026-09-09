package com.powerbind.backend.selenium;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Excluded from normal `mvn test` runs via the "ui" tag — requires backend + frontend
// already running. Run explicitly with:
//   mvn test -Dtest=ErdPageSeleniumTest -DexcludedGroups=
// or via .\run-selenium-test.ps1 (runs every @Tag("ui") suite).
//
// The account behind -Dselenium.username/-Dselenium.password MUST be an ADMIN:
// /erd is router-guarded (requiresAdmin) and /api/admin/erd itself is hasRole('ADMIN')
// gated, so a USER account would just bounce back to the dashboard.
//
// Optional extra coverage: TC-SEL-ERD-10 (router guard for non-admins) only runs when
//   -Dselenium.nonAdminUsername / -Dselenium.nonAdminPassword are also supplied.
@DisplayName("UI Test (erd)")
@Tag("ui")
class ErdPageSeleniumTest extends SeleniumTestBase {

    // Floating toolbar (div.bottom-12) buttons in DOM order — the buttons carry no
    // title/aria attributes (labels live in the hover tooltip span), so position is
    // the stable handle. If a new tool is ever added to the toolbar, re-check this list.
    private static final int IDX_ZOOM_OUT = 1;
    private static final int IDX_SCALE_PCT = 2; // clicking the % label itself resets the scale to 100%
    private static final int IDX_ZOOM_IN = 3;
    private static final int IDX_FIT = 4;
    private static final int IDX_RESET = 5;
    private static final int IDX_GRID = 6;

    private static final By TABLE_BOX = By.cssSelector("[data-table]");
    private static final By RELATION_PATH = By.cssSelector("path[id^='erd-path-']");
    private static final By CODE_PANEL = By.cssSelector(".inset-y-0.right-0");
    private static final By EXPLAIN_PANEL = By.cssSelector(".absolute.z-30.w-96");
    private static final By DATA_PANEL = By.cssSelector("div.h-80");
    private static final By DATA_OUTPUT_TAB = By.xpath("//button[@title='Lihat isi data tabel']");
    private static final By DIMMED_TABLE = By.xpath("//div[@data-table][contains(@class,'opacity-30')]");
    private static final By FOCUSED_TABLE = By.xpath("//div[@data-table][contains(@class,'ring-sky-300')]");
    private static final By LOAD_ERROR = By.xpath("//*[contains(text(),'Gagal memuat skema')]");

    @BeforeEach
    void goToErdPage() {
        loginAsTestUser();
        driver.get(FRONTEND_URL + "/erd");
        waitUntilSchemaLoaded();
    }

    // The canvas only renders table boxes once /api/admin/erd returns the schema —
    // if the request fails the page swaps to its error message instead.
    private void waitUntilSchemaLoaded() {
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> !d.findElements(TABLE_BOX).isEmpty());
        assertTrue(driver.findElements(LOAD_ERROR).isEmpty(),
                "ERD page shows its load error — is the selenium account an ADMIN and is the backend up?");
    }

    private WebElement tableHeader(String tableName) {
        // Every table header div carries the "open code panel" title — matching the
        // text inside it pins the locator to exactly one table
        return driver.findElement(By.xpath(
                "//div[@data-table]//div[@title='Klik untuk lihat kode entity'][normalize-space()='" + tableName + "']"));
    }

    private WebElement tableBox(String tableName) {
        return tableHeader(tableName).findElement(By.xpath("ancestor::div[@data-table][1]"));
    }

    // A column row (not the header) inside a table box — clicking one triggers the
    // box-level focus handler. Matched via the column name span + the row's
    // items-center class so the box/header divs never win the match.
    private WebElement columnRow(String tableName, String columnName) {
        return tableBox(tableName).findElement(By.xpath(
                ".//div[contains(@class,'items-center')][.//span[normalize-space()='" + columnName + "']]"));
    }

    private List<WebElement> toolbarButtons() {
        return driver.findElements(By.cssSelector(".bottom-12 button"));
    }

    private WebElement canvasViewport() {
        return driver.findElement(By.cssSelector("div.flex-1.select-none"));
    }

    private int currentScalePct() {
        return Integer.parseInt(toolbarButtons().get(IDX_SCALE_PCT).getText().replace("%", "").trim());
    }

    private void waitUntilScaleChanges(int previousPct) {
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> currentScalePct() != previousPct);
    }

    private void waitUntilGone(By locator) {
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> d.findElements(locator).isEmpty());
    }

    @Severity(SeverityLevel.CRITICAL)
    @Test
    @DisplayName("TC-SEL-ERD-01 ERD canvas renders every entity table with columns, key icons and relation cables")
    void erd_shouldRenderSchemaTablesAndRelations() {
        List<WebElement> tables = driver.findElements(TABLE_BOX);
        assertTrue(tables.size() >= 5,
                "Expected the JPA entities to render as table boxes, got " + tables.size());
        assertTrue(driver.findElements(By.xpath(
                        "//div[@data-table]//div[@title='Klik untuk lihat kode entity'][normalize-space()='users']"))
                        .size() == 1,
                "The 'users' table box should be on the canvas");
        assertFalse(driver.findElements(RELATION_PATH).isEmpty(),
                "At least one relation cable (svg path) should connect the tables");
        assertFalse(driver.findElements(By.cssSelector("[data-table] svg.text-amber-500")).isEmpty(),
                "Primary-key columns should be marked with the amber key icon");
        assertFalse(driver.findElements(By.cssSelector("[data-table] svg.text-sky-500")).isEmpty(),
                "Foreign-key columns should be marked with the blue link icon");
        attachScreenshot("erd-01-schema-loaded");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-ERD-02 Toolbar zoom in/out buttons and the % label update the canvas scale")
    void erd_zoomControls_shouldUpdateScale() {
        int initial = currentScalePct(); // fresh load starts at the 1.2 default => 120%
        assertEquals(120, initial, "A fresh ERD load should start at the default 120% scale");
        attachScreenshot("erd-02-before-zoom");

        toolbarButtons().get(IDX_ZOOM_IN).click(); // 1.2 * 1.15 => 138%
        waitUntilScaleChanges(initial);
        assertEquals(138, currentScalePct(), "Zoom in should multiply the scale by 1.15 (120% -> 138%)");
        attachScreenshot("erd-03-zoomed-in");

        toolbarButtons().get(IDX_ZOOM_OUT).click(); // back to 120%
        waitUntilScaleChanges(138);
        assertEquals(120, currentScalePct(), "Zoom out should bring the scale back to 120%");
        attachScreenshot("erd-04-zoomed-out");

        toolbarButtons().get(IDX_SCALE_PCT).click();
        waitUntilScaleChanges(120);
        assertEquals(100, currentScalePct(), "Clicking the % label should snap the scale back to exactly 100%");
        attachScreenshot("erd-05-pct-label-reset-to-100");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-ERD-03 'Fit to screen' shrinks the tall layout and 'Reset view' restores the default")
    void erd_fitAndReset_shouldAdjustViewport() {
        toolbarButtons().get(IDX_ZOOM_IN).click();
        waitUntilScaleChanges(120); // now at 138% so Reset has something visible to undo

        toolbarButtons().get(IDX_RESET).click();
        waitUntilScaleChanges(138);
        assertEquals(120, currentScalePct(), "'Reset tampilan' should restore the default 120% scale");

        toolbarButtons().get(IDX_FIT).click();
        waitUntilScaleChanges(120);
        int fitted = currentScalePct();
        // The 2-column layout is taller than the 1366x900 headless viewport, so fit
        // always lands below the 120% default but never below the 35% MIN_SCALE clamp
        assertTrue(fitted >= 35 && fitted < 120,
                "'Sesuaikan ke layar' should shrink the layout below the default scale (got " + fitted + "%)");
        attachScreenshot("erd-06-fit-to-screen");

        toolbarButtons().get(IDX_RESET).click();
        waitUntilScaleChanges(fitted);
        assertEquals(120, currentScalePct(), "Reset should work from any scale, not just after zoom-in");
        attachScreenshot("erd-07-reset-view");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-ERD-04 Grid toggle switches the canvas dot-grid off and back on")
    void erd_gridToggle_shouldSwitchBackgroundGrid() {
        assertTrue(toolbarButtons().get(IDX_GRID).getAttribute("class").contains("bg-sky-500"),
                "Grid button should start highlighted (grid is on by default)");
        assertTrue(canvasViewport().getAttribute("style").contains("radial-gradient"),
                "Canvas background should use the dot-grid radial-gradient");

        toolbarButtons().get(IDX_GRID).click();
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> !d.findElements(By.cssSelector(".bottom-12 button"))
                        .get(IDX_GRID).getAttribute("class").contains("bg-sky-500"));
        assertFalse(canvasViewport().getAttribute("style").contains("radial-gradient"),
                "Toggling the grid off should remove the dot-grid background");
        attachScreenshot("erd-08-grid-toggled-off");

        toolbarButtons().get(IDX_GRID).click();
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> d.findElements(By.cssSelector(".bottom-12 button"))
                        .get(IDX_GRID).getAttribute("class").contains("bg-sky-500"));
        assertTrue(canvasViewport().getAttribute("style").contains("radial-gradient"),
                "Toggling the grid back on should restore the dot-grid background");
        attachScreenshot("erd-09-grid-toggled-back-on");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-ERD-05 Clicking a table header opens the entity code panel and closing it hides the panel")
    void erd_tableHeaderClick_shouldOpenCodePanel() {
        tableHeader("users").click();

        WebElement panel = new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.visibilityOfElementLocated(CODE_PANEL));
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> panel.getText().contains("public class")); // async reconstruct request
        assertTrue(panel.getText().contains("public class"), "Panel should show the reconstructed entity class");
        assertTrue(panel.getText().contains("@Table"), "Panel should show the JPA @Table mapping");
        assertTrue(panel.getText().contains(".java"), "Panel header should name the reconstructed .java file");
        attachScreenshot("erd-10-code-panel-open");

        panel.findElement(By.tagName("button")).click(); // the only (Tutup/close) button in the panel header
        waitUntilGone(CODE_PANEL);
        attachScreenshot("erd-11-code-panel-closed");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-ERD-06 Clicking a PK/FK icon opens the AI explain panel for that column")
    void erd_keyIconClick_shouldOpenAiExplainPanel() {
        // The first amber key icon on the canvas is always a primary-key column
        WebElement pkIcon = driver.findElement(By.cssSelector("[data-table] svg.text-amber-500"));
        pkIcon.findElement(By.xpath("ancestor::button[1]")).click();

        WebElement panel = new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.visibilityOfElementLocated(EXPLAIN_PANEL));
        String header = panel.getText();
        assertTrue(header.contains("·"), "Panel header should show '<column> · <table>'");
        // The badge is styled with CSS `uppercase`, so the rendered text Selenium
        // reads back is "PRIMARY KEY"/"FOREIGN KEY" — compare case-insensitively.
        String headerLower = header.toLowerCase();
        assertTrue(headerLower.contains("primary key") || headerLower.contains("foreign key"),
                "Panel badge should classify the column kind, got: " + header);
        // The body streams from Groq — its content depends on the API key, so only the
        // three known UI states are asserted (streaming text / loading / error + retry)
        assertTrue(header.contains("Gemono sedang menyusun penjelasan")
                        || header.contains("Gagal menghubungi AI")
                        || header.split("\n").length > 2,
                "Explain body should be streaming, loading, or showing its error state");
        attachScreenshot("erd-12-ai-explain-panel-open");

        panel.findElement(By.cssSelector(".cursor-move button")).click(); // Tutup button in the draggable header
        waitUntilGone(EXPLAIN_PANEL);
        attachScreenshot("erd-13-ai-explain-panel-closed");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-ERD-07 Data output panel previews table rows with pagination and closes from its tab")
    void erd_dataOutputPanel_shouldPreviewTableRows() {
        driver.findElement(DATA_OUTPUT_TAB).click();

        // Don't just wait for the container to become "visible" — its height
        // animates in via a 300ms CSS transition (h-0 -> h-80) and the child is
        // overflow-hidden, so the container reports non-zero size (and thus
        // "visible") well before the placeholder text is actually un-clipped.
        // Poll on the rendered text itself so this naturally waits out the
        // transition instead of racing it.
        WebElement panel = new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> {
                    List<WebElement> els = d.findElements(DATA_PANEL);
                    if (els.isEmpty()) return null;
                    WebElement el = els.get(0);
                    return el.getText().contains("Pilih tabel") ? el : null;
                });
        attachScreenshot("erd-13b-data-panel-just-opened");

        panel.findElement(By.xpath(".//button[contains(normalize-space(),'Pilih tabel')]")).click();
        panel.findElement(By.xpath(".//div[contains(@class,'w-56')]//button[normalize-space()='users']")).click();

        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> !d.findElements(By.cssSelector("div.h-80 table")).isEmpty());
        String header = driver.findElement(DATA_PANEL).getText();
        assertTrue(header.contains("Hal. 1 /"), "Pagination line 'Hal. x / y' should be shown, got: " + header);
        assertTrue(header.contains("baris"), "The total row count should be shown next to the pagination");
        attachScreenshot("erd-14-data-panel-users-rows");

        driver.findElement(DATA_OUTPUT_TAB).click(); // the folder tab toggles the panel closed again
        waitUntilGone(DATA_PANEL);
        attachScreenshot("erd-15-data-panel-closed");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-ERD-08 Clicking a table focuses its neighbours, dims the rest, and clicking again clears it")
    void erd_tableClick_shouldFocusNeighbours() {
        columnRow("users", "username").click();

        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> !d.findElements(FOCUSED_TABLE).isEmpty());
        assertFalse(driver.findElements(DIMMED_TABLE).isEmpty(),
                "Tables outside the focused neighbourhood should be dimmed (opacity-30)");
        assertTrue(driver.findElements(FOCUSED_TABLE).size() >= 2,
                "The focused table plus at least one neighbour should be highlighted with the sky ring");
        attachScreenshot("erd-16-table-focus-neighbours");

        columnRow("users", "username").click(); // onTableClick toggles the focus off
        waitUntilGone(DIMMED_TABLE);
        attachScreenshot("erd-17-focus-cleared");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-SEL-ERD-09 Sidebar navigates ERD -> Log -> Dashboard")
    void erd_sidebar_shouldNavigateBetweenPages() {
        driver.findElement(By.xpath("//aside//button[.//span[text()='Log']]")).click();
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.urlContains("/log"));
        attachScreenshot("erd-18-navigated-to-log");

        driver.findElement(By.xpath("//aside//button[.//span[text()='ERD']]")).click();
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.urlContains("/erd"));
        waitUntilSchemaLoaded(); // schema re-fetches on every visit to /erd
        attachScreenshot("erd-19-navigated-back-to-erd");

        driver.findElement(By.xpath("//aside//button[.//span[text()='Dashboard']]")).click();
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.visibilityOfElementLocated(By.xpath("//h1[text()='Ringkasan']")));
        attachScreenshot("erd-20-navigated-to-dashboard");
    }

    @Severity(SeverityLevel.CRITICAL)
    @Test
    @DisplayName("TC-SEL-ERD-10 Router guard bounces a non-admin account away from /erd and /log")
    void erd_routerGuard_shouldRedirectNonAdminToDashboard() {
        String nonAdminUser = System.getProperty("selenium.nonAdminUsername");
        String nonAdminPass = System.getProperty("selenium.nonAdminPassword");
        Assumptions.assumeTrue(nonAdminUser != null && !nonAdminUser.isBlank(),
                "Skipped: -Dselenium.nonAdminUsername not provided (optional guard coverage)");
        Assumptions.assumeTrue(nonAdminPass != null && !nonAdminPass.isBlank(),
                "Skipped: -Dselenium.nonAdminPassword not provided (optional guard coverage)");

        // The class @BeforeEach already logged the admin in — swap the session for a
        // non-admin one by clearing storage and logging in through the UI again.
        ((JavascriptExecutor) driver).executeScript("window.localStorage.clear();");
        driver.get(FRONTEND_URL + "/login");
        driver.findElement(By.cssSelector("input[type='text']")).sendKeys(nonAdminUser);
        driver.findElement(By.cssSelector("input[type='password']")).sendKeys(nonAdminPass);
        driver.findElement(SUBMIT_CONTROL).click();
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.not(ExpectedConditions.urlContains("/login")));

        driver.get(FRONTEND_URL + "/erd");
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> !d.getCurrentUrl().contains("/erd"));
        assertFalse(driver.getCurrentUrl().contains("/login"),
                "The guard should land the non-admin on the dashboard, not the login page");
        attachScreenshot("erd-21-nonadmin-redirected-from-erd");

        driver.get(FRONTEND_URL + "/log");
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> !d.getCurrentUrl().contains("/log"));
        assertFalse(driver.getCurrentUrl().contains("/erd"),
                "The guard should never land on another guarded page");
        attachScreenshot("erd-22-nonadmin-redirected-from-log");
    }
}