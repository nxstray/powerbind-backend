Feature: Dashboard summary

  The dashboard gives an authenticated user a live snapshot of rooms, power
  usage, and estimated cost — and must never be reachable anonymously.

  Scenario: An authenticated user can view the dashboard summary
    When "bdd_dashboard_user" requests the dashboard summary
    Then the dashboard response status should be 200
    And the summary should report room and power fields

  Scenario: An authenticated user can view power usage history
    When "bdd_dashboard_user" requests power history for the last 24 hours
    Then the dashboard response status should be 200
    And the power history should be a list

  Scenario: Anonymous requests are rejected
    When an anonymous user requests the dashboard summary
    Then the dashboard response status should be 401 or 403
