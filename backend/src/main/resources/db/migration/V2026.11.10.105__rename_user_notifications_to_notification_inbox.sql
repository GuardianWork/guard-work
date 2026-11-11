-- Rename the recipient inbox table to notification_inbox. No columns, data or API change.
-- Committed migrations that created user_notifications are immutable; this migration renames it.
-- Dependent auto-named constraints retain their legacy names, as with a plain PostgreSQL table rename.
ALTER TABLE user_notifications RENAME TO notification_inbox;
ALTER INDEX ix_user_notifications_recipient RENAME TO ix_notification_inbox_recipient;
ALTER SEQUENCE IF EXISTS user_notifications_id_seq RENAME TO notification_inbox_id_seq;
