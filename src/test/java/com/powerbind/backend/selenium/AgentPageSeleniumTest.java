package com.powerbind.backend.selenium;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("ui")
class AgentPageSeleniumTest extends SeleniumTestBase {

    @BeforeEach
    void goToAgentPage() {
        clearBackendChatHistory();
        loginAsTestUser();
        driver.get(FRONTEND_URL + "/agent");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    void agentPage_shouldShowDynamicGreeting() {
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.visibilityOfElementLocated(By.xpath("//h2[contains(text(),'Halo,')]")));
        attachScreenshot("agent-01-greeting-shown");

        String greeting = driver.findElement(By.xpath("//h2[contains(text(),'Halo,')]")).getText();
        assertTrue(greeting.contains("Halo,"), "Greeting should be shown");
        assertTrue(!greeting.equals("Halo,"), "Greeting should include an actual username/displayName, not be empty");
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    void agentPage_sendingMessage_shouldAppearAsUserBubble() {
        String message = "Berapa pemakaian energi hari ini?";

        driver.findElement(By.cssSelector("textarea")).sendKeys(message);
        attachScreenshot("agent-02-message-typed");

        // data-testid is explicit and immune to layout/class changes elsewhere on the page —
        // the old xpath heuristic "(//div[contains(@class,'rounded-full')]//button)[last()]"
        // broke as soon as another rounded-full button was added anywhere on the page.
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.elementToBeClickable(By.cssSelector("[data-testid='send-button']")))
                .click();

        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("body"), message));
        attachScreenshot("agent-03-message-sent");

        assertTrue(driver.getPageSource().contains(message),
                "Sent message should appear as a chat bubble");
    }
}