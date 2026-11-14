-- pg_net is an internal scheduler transport, not a client API.
REVOKE ALL ON SCHEMA net FROM PUBLIC, anon, authenticated;
REVOKE ALL ON ALL TABLES IN SCHEMA net FROM PUBLIC, anon, authenticated;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA net FROM PUBLIC, anon, authenticated;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA net FROM PUBLIC, anon, authenticated;
