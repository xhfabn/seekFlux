CREATE SCHEMA IF NOT EXISTS content AUTHORIZATION seekflux;
CREATE SCHEMA IF NOT EXISTS feature_registry AUTHORIZATION seekflux;
CREATE SCHEMA IF NOT EXISTS experiment AUTHORIZATION seekflux;
CREATE SCHEMA IF NOT EXISTS model_registry AUTHORIZATION seekflux;
CREATE SCHEMA IF NOT EXISTS outbox AUTHORIZATION seekflux;

COMMENT ON SCHEMA content IS 'Content control-plane source of truth';
COMMENT ON SCHEMA feature_registry IS 'Versioned feature definitions';
COMMENT ON SCHEMA experiment IS 'Experiment configuration and assignments';
COMMENT ON SCHEMA model_registry IS 'Model metadata and activation state';
COMMENT ON SCHEMA outbox IS 'Transactional integration-event outbox';
