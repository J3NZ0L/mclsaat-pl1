-- ---------------------------------------------------------------------------
-- Three databases, three roles, one PostgreSQL server.
--
-- The subsystems share a server because this is a research PoC on one machine, and nothing else.
-- They do not share a database, a schema or a login: each service can only see its own data, so a
-- join across subsystem boundaries is not merely discouraged, it is impossible. That is the point.
--
-- The one deliberate exception is at the bottom: the ops console is granted read-only access to the
-- catalog's schema, because "direct database access into another subsystem" is one of the four
-- interface styles this landscape is meant to demonstrate. Granting it explicitly, with SELECT only,
-- keeps it honest - the shortcut exists, and it is visible here rather than hidden in a connection
-- string.
--
-- Flyway creates the schemas and tables inside each database at service start.
-- ---------------------------------------------------------------------------

-- subsystem 1: catalog and subscription registry
CREATE ROLE catalog_app WITH LOGIN PASSWORD 'catalog_app';
CREATE DATABASE catalogdb OWNER catalog_app;

-- subsystem 2: activation. Flowable's engine tables live here too, in the public schema.
CREATE ROLE activation_app WITH LOGIN PASSWORD 'activation_app';
CREATE DATABASE flowabledb OWNER activation_app;

-- subsystem 3: billing and payment
CREATE ROLE billing_app WITH LOGIN PASSWORD 'billing_app';
CREATE DATABASE billingdb OWNER billing_app;

-- the ops console's read-only login into the catalog's database
CREATE ROLE ops_reader WITH LOGIN PASSWORD 'ops_reader';

\connect catalogdb

-- Flyway has not run yet, so the catalog schema does not exist. Grant on it for the future instead:
-- DEFAULT PRIVILEGES apply to tables catalog_app creates later, which is exactly when Flyway makes
-- them. Without this the ops console would be able to connect and see nothing.
CREATE SCHEMA IF NOT EXISTS catalog AUTHORIZATION catalog_app;
GRANT CONNECT ON DATABASE catalogdb TO ops_reader;
GRANT USAGE ON SCHEMA catalog TO ops_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA catalog TO ops_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE catalog_app IN SCHEMA catalog
    GRANT SELECT ON TABLES TO ops_reader;
