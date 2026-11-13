-- Этап 6b: срабатывание напоминаний о задачах.
--
-- reminded_at — когда напоминание отправлено. Планировщик забирает
-- задачи с наступившим remind_at и пустым reminded_at, проставляя метку
-- в той же операции (UPDATE ... FOR UPDATE SKIP LOCKED): два экземпляра
-- сервера не отправят одно напоминание дважды.
--
-- Изменение remind_at сбрасывает метку (TasksRepository.applyFields):
-- перенесённое напоминание должно сработать снова.
ALTER TABLE tasks ADD COLUMN reminded_at timestamptz;

CREATE INDEX tasks_reminder_due_idx
    ON tasks (remind_at)
    WHERE remind_at IS NOT NULL AND reminded_at IS NULL AND deleted_at IS NULL;
