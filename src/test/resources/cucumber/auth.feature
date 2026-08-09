Feature: Authentication

  Scenario: Successful registration and login
    Given a user registers with email "test@powerbind.com" and password "password123"
    Then the response status should be 200

    When the user logs in with email "test@powerbind.com" and password "password123"
    Then the response status should be 200
    And the response should contain an access token

  Scenario: Login with wrong password
    When the user logs in with email "test@powerbind.com" and password "wrongpass"
    Then the response status should be 400

  Scenario: Login lockout after 5 failed attempts
    When the user logs in with email "test@powerbind.com" and password "wrong1"
    When the user logs in with email "test@powerbind.com" and password "wrong2"
    When the user logs in with email "test@powerbind.com" and password "wrong3"
    When the user logs in with email "test@powerbind.com" and password "wrong4"
    When the user logs in with email "test@powerbind.com" and password "wrong5"
    Then the response status should be 429
