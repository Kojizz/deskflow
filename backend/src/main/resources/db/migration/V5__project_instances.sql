CREATE TABLE projects (
 id uuid PRIMARY KEY,
 owner_id uuid NOT NULL REFERENCES users(id),
 name text NOT NULL,
 root_path text NOT NULL,
 kind text NOT NULL CHECK (kind IN ('git','plain')),
 allowed_commands jsonb NOT NULL DEFAULT '[]',
 created_at timestamptz NOT NULL DEFAULT now(),
 archived boolean NOT NULL DEFAULT false,
 UNIQUE (id, owner_id)
);
CREATE TABLE project_instances (
 id uuid PRIMARY KEY,
 owner_id uuid NOT NULL,
 project_id uuid NOT NULL,
 name text NOT NULL,
 ref text,
 workspace_path text NOT NULL,
 status text NOT NULL CHECK (status IN ('READY','BUSY','STALE','ARCHIVED')),
 base_instance_id uuid REFERENCES project_instances(id),
 lease_token uuid,
 lease_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY (project_id, owner_id) REFERENCES projects(id, owner_id),
 UNIQUE (id, project_id, owner_id)
);
ALTER TABLE executions ADD COLUMN project_id uuid;
ALTER TABLE executions ADD COLUMN instance_id uuid;
ALTER TABLE executions ADD COLUMN state_fingerprint text;
ALTER TABLE executions ADD CONSTRAINT execution_instance_owner
 FOREIGN KEY (instance_id, project_id, owner_id) REFERENCES project_instances(id, project_id, owner_id);
CREATE INDEX project_instances_owner ON project_instances(owner_id);
