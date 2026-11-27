ALTER TABLE service_admins DROP CONSTRAINT service_admins_role_check;
ALTER TABLE service_admins ADD CONSTRAINT service_admins_role_check
    CHECK (role IN ('OWNER','ADMIN','MODERATOR','ANALYST','BOARD','MANAGER'));
