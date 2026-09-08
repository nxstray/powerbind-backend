package com.powerbind.backend.selenium;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("UI Test (dashboard)")
@Tag("ui")
class DashboardPageSeleniumTest extends SeleniumTestBase {

    @BeforeEach
    void goToDashboard() {
        loginAsTestUser();
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-UI-03 Dashboard Overview renders stat cards, power chart and energy donut")
    void dashboard_shouldDisplayOverviewAndStatCards() {
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.visibilityOfElementLocated(By.xpath("//h1[text()='Overview']")));
        attachScreenshot("dashboard-01-overview-loaded");

        String pageSource = driver.getPageSource();
        assertTrue(pageSource.contains("Rooms"), "Should show Rooms stat card");
        assertTrue(pageSource.contains("Occupied"), "Should show Occupied stat card");
        assertTrue(pageSource.contains("Devices"), "Should show Devices stat card");
        assertTrue(pageSource.contains("Power Usage"), "Should show Power Usage chart section");
        assertTrue(pageSource.contains("Today's Energy"), "Should show Today's Energy donut section");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-UI-04 Sidebar button navigates to the Agent page")
    void dashboard_shouldNavigateToAgentPageViaSidebar() {
        driver.findElement(By.xpath("//button[.//span[text()='Gemono']]")).click();

        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.urlContains("/agent"));
        attachScreenshot("dashboard-02-navigated-to-agent");

        assertTrue(driver.getCurrentUrl().contains("/agent"),
                "Sidebar Gemono button should navigate to /agent");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-UI-05 Logout redirects back to the login page")
    void dashboard_logout_shouldRedirectToLogin() {
        // Clicking Logout only opens the confirmation ConfirmDialog now — it doesn't
        // log out immediately. The actual logout fires from the dialog's confirm button.
        driver.findElement(By.cssSelector("button[title='Logout']")).click();

        driver.findElement(By.xpath("//button[text()='Keluar']")).click();

        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.urlContains("/login"));
        attachScreenshot("dashboard-03-logged-out");

        assertTrue(driver.getCurrentUrl().contains("/login"),
                "Confirming logout should redirect back to the login page");
    }
}