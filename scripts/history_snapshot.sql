-- Operator-only staging export. Never expose this query as an account API.
-- Current membership matches the legacy history API; past ACLs are not inferred.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '60s';
WITH accounts AS (
 SELECT id FROM users WHERE deleted_at IS NULL
), exports AS (
 SELECT u.id, jsonb_build_object(
  'account', u.id,
  'conversations', COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id)
    FROM conversations c JOIN conversation_members cm ON cm.conversation_id=c.id
    WHERE cm.user_id=u.id AND cm.left_at IS NULL AND c.deleted_at IS NULL), '[]'::jsonb),
  'members', COALESCE((SELECT jsonb_agg(jsonb_build_object(
    'conversation_id',cm.conversation_id,'user_id',cm.user_id,'role',cm.role,
    'joined_at',cm.joined_at,'left_at',cm.left_at) ORDER BY cm.conversation_id,cm.user_id)
    FROM conversation_members cm JOIN conversations c ON c.id=cm.conversation_id
    WHERE c.deleted_at IS NULL AND EXISTS(SELECT 1 FROM conversation_members mine
      WHERE mine.conversation_id=c.id AND mine.user_id=u.id AND mine.left_at IS NULL)), '[]'::jsonb),
  'messages', COALESCE((SELECT jsonb_agg(
    (to_jsonb(m) - 'body') || jsonb_build_object('body',CASE WHEN m.deleted_at IS NULL THEN m.body ELSE NULL END)
    ORDER BY m.conversation_id,m.seq)
    FROM messages m JOIN conversations c ON c.id=m.conversation_id
    WHERE c.deleted_at IS NULL AND EXISTS(SELECT 1 FROM conversation_members cm
      WHERE cm.conversation_id=c.id AND cm.user_id=u.id AND cm.left_at IS NULL)), '[]'::jsonb),
  'reactions', COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.message_id,r.user_id,r.emoji)
    FROM message_reactions r JOIN messages m ON m.id=r.message_id JOIN conversations c ON c.id=m.conversation_id
    WHERE m.deleted_at IS NULL AND c.deleted_at IS NULL AND EXISTS(SELECT 1 FROM conversation_members cm
      WHERE cm.conversation_id=c.id AND cm.user_id=u.id AND cm.left_at IS NULL)), '[]'::jsonb),
  'attachments', COALESCE((SELECT jsonb_agg(to_jsonb(a) ORDER BY a.id)
    FROM attachments a JOIN messages m ON m.id=a.message_id JOIN conversations c ON c.id=m.conversation_id
    WHERE a.conversation_id=m.conversation_id AND a.deleted_at IS NULL AND a.scan_status='CLEAN'
      AND m.deleted_at IS NULL AND c.deleted_at IS NULL AND EXISTS(SELECT 1 FROM conversation_members cm
        WHERE cm.conversation_id=c.id AND cm.user_id=u.id AND cm.left_at IS NULL)), '[]'::jsonb)
 ) AS data FROM accounts u
)
SELECT jsonb_build_object('databaseSnapshot',pg_current_snapshot()::text,
 'capturedAt',transaction_timestamp(),'accounts',COALESCE(jsonb_agg(data ORDER BY id),'[]'::jsonb)) FROM exports;
COMMIT;
