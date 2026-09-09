package com.powerbind.backend.selenium;

import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// Exercises the real-time energy-waste toast on the Agent page — the feature that
// replaced the old static "Terdeteksi pemborosan energi" badge. A fresh room is
// created via the REST API so it starts with presence_detected = false and
// relay_on = false (i.e. NOT wasting). We then flip its relay ON through the same
// endpoint the dashboard's manual-override dialog calls; that ON transition, with
// no presence detected, is exactly what RoomService.publishAnomalyIfNewlyWasting
// broadcasts over STOMP to /topic/anomaly — so this drives the real anomaly path
// end-to-end instead of asserting on mocked/injected data.
@Tag("ui")
@DisplayName("UI Test (anomaly toast)")
class AnomalyToastSeleniumTest extends SeleniumTestBase {

    private static final String ROOM_NAME = "Selenium Anomaly Room";
    private static final By TOAST_TITLE = By.xpath("//p[text()='Pemborosan energi terdeteksi']");
    private static final By OLD_BADGE = By.xpath("//span[text()='Terdeteksi pemborosan energi']");

    private String accessToken;
    private String roomId;

    @BeforeEach
    void createFreshRoom() {
        accessToken = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("username", TEST_USERNAME, "password", TEST_PASSWORD))
                .post(BACKEND_URL + "/api/auth/login")
                .jsonPath().getString("data.accessToken");

        // Unique mqttTopic per run so repeated executions never collide on the unique constraint
        String uniqueTopic = "selenium-anomaly-" + UUID.randomUUID();
        Response created = RestAssured.given()
                .header("Authorization", "Bearer " + accessToken)
                .contentType("application/json")
                .body(Map.of("name", ROOM_NAME, "mqttTopic", uniqueTopic))
                .post(BACKEND_URL + "/api/rooms");

        roomId = created.jsonPath().getString("data.id");
        assertNotNull(roomId, "Room creation must succeed for this test to have something to flip");
    }

    @AfterEach
    void deleteRoom() {
        if (roomId == null || accessToken == null) return;
        RestAssured.given()
                .header("Authorization", "Bearer " + accessToken)
                .delete(BACKEND_URL + "/api/rooms/" + roomId);
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    @DisplayName("TC-UI-06 Relay turned ON in an empty room shows the anomaly toast, not the old badge")
    void relayTurnedOnInEmptyRoom_shouldShowAnomalyToast() {
        loginAsTestUser();
        driver.get(FRONTEND_URL + "/agent");

        // Give the STOMP client a moment to finish its onConnect + subscribe handshake
        // before we publish — otherwise the broadcast can fire before we're listening.
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // New room defaults to presence_detected=false / relay_on=false (not wasting).
        // Flipping relay ON here is the wasWasting=false -> isWasting=true transition
        // that RoomService uses as the trigger to publish onto /topic/anomaly.
        RestAssured.given()
                .header("Authorization", "Bearer " + accessToken)
                .contentType("application/json")
                .body(Map.of("relayOn", true))
                .patch(BACKEND_URL + "/api/rooms/" + roomId + "/relay");

        WebElement toast = new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.visibilityOfElementLocated(TOAST_TITLE));
        attachScreenshot("anomaly-01-toast-shown");

        assertTrue(toast.isDisplayed(), "Anomaly toast should appear after the relay-on/no-presence transition");
        assertTrue(driver.getPageSource().contains(ROOM_NAME),
                "Toast message should reference the specific room that started wasting energy");
        assertTrue(driver.findElements(OLD_BADGE).isEmpty(),
                "The old static waste badge should no longer be rendered on the page");
    }
}