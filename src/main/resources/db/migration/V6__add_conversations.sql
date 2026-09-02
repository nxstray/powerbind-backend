-- Conversations table — groups chat messages into separate threads per user
CREATE TABLE conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conversations_user_updated ON conversations(user_id, updated_at DESC);

-- Migrate existing flat chat_messages into a single "Percakapan Lama" conversation per user
-- so no history is lost when this migration runs on an existing database
INSERT INTO conversations (id, user_id, title, created_at, updated_at)
SELECT gen_random_uuid(), user_id, 'Percakapan Lama', MIN(created_at), MAX(created_at)
FROM chat_messages
GROUP BY user_id;

-- Add conversation_id column, nullable first so we can backfill it
ALTER TABLE chat_messages ADD COLUMN conversation_id UUID;

-- Backfill conversation_id for existing rows using the migrated conversation above
UPDATE chat_messages cm
SET conversation_id = c.id
FROM conversations c
WHERE cm.user_id = c.user_id AND c.title = 'Percakapan Lama';

-- Now enforce NOT NULL and add the foreign key
ALTER TABLE chat_messages ALTER COLUMN conversation_id SET NOT NULL;
ALTER TABLE chat_messages
    ADD CONSTRAINT fk_chat_messages_conversation
    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE;

CREATE INDEX idx_chat_messages_conversation_created ON chat_messages(conversation_id, created_at ASC);