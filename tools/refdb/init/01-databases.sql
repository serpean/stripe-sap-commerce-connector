-- scripts/dev/refdb.sh up runs this file with psql after every start. It is idempotent.
-- POSTGRES_DB already created "hybris". The junit tenant gets its own database,
-- so the integration tests do not touch the master data.
SELECT 'CREATE DATABASE hybris_junit OWNER hybris'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'hybris_junit')\gexec
