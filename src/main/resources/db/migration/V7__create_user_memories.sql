-- Long-term memory for the AI agent — durable facts/preferences about a user,
-- kept separate from chat_messages so they persist and get reused across conversations.
CREATE TABLE user_memories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content VARCHAR(300) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_memories_user_created ON user_memories(user_id, created_at DESC);