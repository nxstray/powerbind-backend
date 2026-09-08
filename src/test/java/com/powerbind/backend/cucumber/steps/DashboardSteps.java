package com.powerbind.backend.cucumber.steps;

import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.UserRepository;
import io.cucumber.java.Before;
import io.cucumber.java.en.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

// BDD coverage for /api/dashboard — summary and power-history must require authentication
// and return the shape the frontend dashboard depends on.
public class DashboardSteps {

    @LocalServerPort
    private int port;

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String DEFAULT_PASSWORD = "password123";

    private Response dashboardResponse;

    @Before
    public void seedDashboardUser() {
        if (userRepository.existsByUsername("bdd_dashboard_user")) return;
        userRepository.save(User.builder()
                .username("bdd_dashboard_user")
                .password(passwordEncoder.encode(DEFAULT_PASSWORD))
                .displayName("Dashboard BDD User")
                .build());
    }

    private String loginAndGetToken(String username) {
        return RestAssured.given()
                .contentType("application/json")
                .body(Map.of("username", username, "password", DEFAULT_PASSWORD))
                .post("http://localhost:" + port + "/api/auth/login")
                .jsonPath().getString("data.accessToken");
    }

    @When("{string} requests the dashboard summary")
    public void requestsDashboardSummary(String username) {
        String token = loginAndGetToken(username);
        dashboardResponse = RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .get("http://localhost:" + port + "/api/dashboard/summary");
    }

    @When("{string} requests power history for the last {int} hours")
    public void requestsPowerHistory(String username, int hours) {
        String token = loginAndGetToken(username);
        dashboardResponse = RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .queryParam("hours", hours)
                .get("http://localhost:" + port + "/api/dashboard/power-history");
    }

    @When("an anonymous user requests the dashboard summary")
    public void anonymousRequestsDashboardSummary() {
        dashboardResponse = RestAssured.get("http://localhost:" + port + "/api/dashboard/summary");
    }

    // Named "dashboard response status" for the same reason AgentSteps uses "agent response
    // status" — keeps this class's Response object from colliding with AuthSteps/AgentSteps'
    // identically-shaped assertion steps checking a completely different Response instance.
    @Then("the dashboard response status should be {int}")
    public void verifyDashboardStatus(int expectedStatus) {
        Assertions.assertEquals(expectedStatus, dashboardResponse.getStatusCode());
    }

    @Then("the dashboard response status should be {int} or {int}")
    public void verifyDashboardStatusEither(int optionA, int optionB) {
        int actual = dashboardResponse.getStatusCode();
        Assertions.assertTrue(actual == optionA || actual == optionB,
                "Expected " + optionA + " or " + optionB + ", got " + actual);
    }

    @Then("the summary should report room and power fields")
    public void summaryShouldReportRoomAndPowerFields() {
        Assertions.assertNotNull(dashboardResponse.jsonPath().get("data.totalRooms"));
        Assertions.assertNotNull(dashboardResponse.jsonPath().get("data.occupiedRooms"));
        Assertions.assertNotNull(dashboardResponse.jsonPath().get("data.activeDevices"));
        Assertions.assertNotNull(dashboardResponse.jsonPath().get("data.currentWatts"));
        Assertions.assertNotNull(dashboardResponse.jsonPath().get("data.todayKwh"));
        Assertions.assertNotNull(dashboardResponse.jsonPath().get("data.estimatedCostToday"));
    }

    @Then("the power history should be a list")
    public void powerHistoryShouldBeAList() {
        List<?> history = dashboardResponse.jsonPath().getList("data");
        Assertions.assertNotNull(history);
    }
}