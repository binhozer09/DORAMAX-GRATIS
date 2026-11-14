-- Run with a Supabase SQL role capable of SET ROLE.
-- Each block rolls back; IDs are discovered from the project's own user tables.

BEGIN;
SELECT set_config(
  'test.regular_user_id',
  (SELECT g.user_id::text
     FROM public.access_grants g
    WHERE NOT EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id=g.user_id)
    LIMIT 1),
  true
);
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',current_setting('test.regular_user_id'),true);
DO $$
BEGIN
  IF (SELECT count(*) FROM public.access_grants) <> 1 THEN RAISE EXCEPTION 'Regular user must see only own access'; END IF;
  IF (SELECT count(*) FROM public.profiles) <> 1 THEN RAISE EXCEPTION 'Regular user must see only own profile'; END IF;
  IF (SELECT count(*) FROM public.videos WHERE is_active=false) <> 0 THEN RAISE EXCEPTION 'Regular user must not see inactive videos'; END IF;
END;
$$;
ROLLBACK;

BEGIN;
SELECT set_config('test.admin_user_id',(SELECT user_id::text FROM public.admin_users LIMIT 1),true);
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',current_setting('test.admin_user_id'),true);
DO $$
BEGIN
  IF (SELECT count(*) FROM public.access_grants) <> (SELECT count(*) FROM auth.users) THEN RAISE EXCEPTION 'Admin must see all access grants'; END IF;
  IF (SELECT count(*) FROM public.profiles) <> (SELECT count(*) FROM auth.users) THEN RAISE EXCEPTION 'Admin must see all profiles'; END IF;
  IF (SELECT count(*) FROM public.videos WHERE is_active=false) = 0 THEN RAISE EXCEPTION 'Admin must see inactive catalog rows'; END IF;
END;
$$;
UPDATE public.access_grants SET is_unlocked = NOT is_unlocked
WHERE user_id = (SELECT user_id FROM public.access_grants LIMIT 1);
ROLLBACK;

BEGIN;
SET LOCAL ROLE anon;
INSERT INTO public.payment_click_notifications(amount) VALUES (5.00);
DO $$
BEGIN
  BEGIN
    INSERT INTO public.payment_click_notifications(amount) VALUES (10.00);
    RAISE EXCEPTION 'Invalid payment amount was unexpectedly accepted';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
END;
$$;
DO $$
BEGIN
  IF (SELECT count(*) FROM public.videos WHERE is_active=true) <> 542 THEN RAISE EXCEPTION 'Public must see active catalog'; END IF;
  IF (SELECT count(*) FROM public.videos WHERE is_active=false) <> 0 THEN RAISE EXCEPTION 'Public must not see inactive catalog'; END IF;
  IF (SELECT count(*) FROM public.profiles) <> 0 THEN RAISE EXCEPTION 'Public must not see profiles'; END IF;
  IF (SELECT count(*) FROM public.access_grants) <> 0 THEN RAISE EXCEPTION 'Public must not see access grants'; END IF;
END;
$$;
ROLLBACK;
