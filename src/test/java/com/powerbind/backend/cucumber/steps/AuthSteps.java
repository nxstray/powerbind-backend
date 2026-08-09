package com.powerbind.backend.cucumber.steps;

import io.cucumber.java.en.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assertions;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

public class AuthSteps {

    @LocalServerPort
    private int port;

    private Response response;

    @Given("a user registers with email {string} and password {string}")
    public void registerUser(String email, String password) {
        response = RestAssured.given()
                .contentType("application/json")
                .body(Map.of(
                    "email", email,
                    "password", password,
                    "displayName", "Test User"
                ))
                .post("http://localhost:" + port + "/api/auth/register");
    }

    @When("the user logs in with email {string} and password {string}")
    public void loginUser(String email, String password) {
        response = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("email", email, "password", password))
                .post("http://localhost:" + port + "/api/auth/login");
    }

    @Then("the response status should be {int}")
    public void verifyStatus(int expectedStatus) {
        Assertions.assertEquals(expectedStatus, response.getStatusCode());
    }

    @Then("the response should contain an access token")
    public void verifyAccessToken() {
        String token = response.jsonPath().getString("data.accessToken");
        Assertions.assertNotNull(token);
        Assertions.assertFalse(token.isEmpty());
    }
}
