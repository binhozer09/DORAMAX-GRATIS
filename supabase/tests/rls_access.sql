-- Run with a Supabase SQL role capable of SET ROLE.
-- Each block rolls back and only validates row-level access boundaries.

BEGIN;
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','55bae538-1fec-48d2-a3a8-0bb5cdc96e33',true);
DO $$
BEGIN
  IF (SELECT count(*) FROM public.access_grants) <> 1 THEN
    RAISE EXCEPTION 'Regular user must see only their own access grant';
  END IF;
  IF (SELECT count(*) FROM public.profiles) <> 1 THEN
    RAISE EXCEPTION 'Regular user must see only their own profile';
  END IF;
  IF (SELECT count(*) FROM public.videos WHERE is_active=false) <> 0 THEN
    RAISE EXCEPTION 'Regular user must not see inactive videos';
  END IF;
END;
$$;
ROLLBACK;

BEGIN;
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','fc8542de-96df-4cd8-9fde-48fef3a2cdf1',true);
DO $$
BEGIN
  IF (SELECT count(*) FROM public.access_grants) <> 3 THEN
    RAISE EXCEPTION 'Admin must see all access grants';
  END IF;
  IF (SELECT count(*) FROM public.profiles) <> 3 THEN
    RAISE EXCEPTION 'Admin must see all profiles';
  END IF;
  IF (SELECT count(*) FROM public.videos WHERE is_active=false) <> 1107 THEN
    RAISE EXCEPTION 'Admin must see inactive catalog rows';
  END IF;
END;
$$;
ROLLBACK;

BEGIN;
SET LOCAL ROLE anon;
DO $$
BEGIN
  IF (SELECT count(*) FROM public.videos WHERE is_active=true) <> 542 THEN
    RAISE EXCEPTION 'Anonymous visitors must see the active catalog';
  END IF;
  IF (SELECT count(*) FROM public.videos WHERE is_active=false) <> 0 THEN
    RAISE EXCEPTION 'Anonymous visitors must not see inactive videos';
  END IF;
  IF (SELECT count(*) FROM public.profiles) <> 0 THEN
    RAISE EXCEPTION 'Anonymous visitors must not see profiles';
  END IF;
  IF (SELECT count(*) FROM public.access_grants) <> 0 THEN
    RAISE EXCEPTION 'Anonymous visitors must not see access grants';
  END IF;
END;
$$;
ROLLBACK;
