Feature: BDD (Authentication)

  Scenario: Successful login with a pre-seeded account
    When the user logs in with username "cucumber_user" and password "password123"
    Then the response status should be 200
    And the response should contain an access token

  Scenario: Login with wrong password
    When the user logs in with username "cucumber_user" and password "wrongpass"
    Then the response status should be 400

  Scenario: Login lockout after 5 failed attempts
    When the user logs in with username "cucumber_lockout_user" and password "wrong1"
    When the user logs in with username "cucumber_lockout_user" and password "wrong2"
    When the user logs in with username "cucumber_lockout_user" and password "wrong3"
    When the user logs in with username "cucumber_lockout_user" and password "wrong4"
    When the user logs in with username "cucumber_lockout_user" and password "wrong5"
    Then the response status should be 429