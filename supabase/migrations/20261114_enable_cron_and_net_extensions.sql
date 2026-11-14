-- Enable Supabase-native scheduling and asynchronous HTTP support.
-- The extensions are enabled here so future scheduled catalog sync can use
-- pg_cron + pg_net without depending on GitHub-hosted runtime services.
CREATE EXTENSION IF NOT EXISTS pg_cron;
CREATE EXTENSION IF NOT EXISTS pg_net;
