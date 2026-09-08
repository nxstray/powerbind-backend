Feature: Agent conversation history

  A family member should only ever see, read, or delete their own AI agent
  conversation threads — never someone else's. Groq itself is never called
  here (chat/document/vision are streaming endpoints backed by an external
  API); conversations and messages are seeded directly instead.

  Background:
    Given the following conversations exist:
      | owner       | title             | message             |
      | bdd_alice   | Percakapan Alice  | Pesan rahasia Alice |
      | bdd_bob     | Percakapan Bob    | Pesan rahasia Bob   |

  Scenario: A user only sees their own conversations in the list
    When "bdd_alice" requests their conversation list
    Then the agent response status should be 200
    And the conversation titles should include "Percakapan Alice"
    And the conversation titles should not include "Percakapan Bob"

  Scenario: A user can read the messages inside their own conversation
    When "bdd_alice" opens their conversation "Percakapan Alice"
    Then the agent response status should be 200
    And the conversation messages should include "Pesan rahasia Alice"

  Scenario: A user cannot open another user's conversation
    When "bdd_bob" attempts to open "bdd_alice"'s conversation "Percakapan Alice"
    Then the agent response status should be 404 or 403

  Scenario: A user can delete their own conversation
    When "bdd_bob" deletes their conversation "Percakapan Bob"
    Then the agent response status should be 200
    When "bdd_bob" requests their conversation list
    Then the conversation titles should not include "Percakapan Bob"

  Scenario: Anonymous requests are rejected
    When an anonymous user requests the conversation list
    Then the agent response status should be 401 or 403
