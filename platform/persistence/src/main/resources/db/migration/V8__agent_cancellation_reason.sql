ALTER TABLE agent.runs
    ADD COLUMN IF NOT EXISTS cancellation_reason VARCHAR(64);
