ALTER TABLE projects ADD COLUMN deleted_at timestamptz;
ALTER TABLE project_instances ADD COLUMN deleted_at timestamptz;

CREATE INDEX project_instances_live_owner
    ON project_instances(owner_id) WHERE deleted_at IS NULL;
