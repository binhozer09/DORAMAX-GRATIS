-- Core remote catalog, settings, and administrator registry.
CREATE TABLE IF NOT EXISTS public.videos (
  id text PRIMARY KEY,
  title text NOT NULL,
  published text NOT NULL DEFAULT '',
  source text NOT NULL,
  is_active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS public.app_settings (
  key text PRIMARY KEY,
  value jsonb NOT NULL,
  is_public boolean NOT NULL DEFAULT true,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS public.admin_users (
  user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE
);

ALTER TABLE public.videos ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.app_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.admin_users ENABLE ROW LEVEL SECURITY;

GRANT SELECT ON public.videos TO anon, authenticated;
GRANT INSERT, UPDATE, DELETE ON public.videos TO authenticated;
GRANT SELECT ON public.app_settings TO anon, authenticated;
GRANT INSERT, UPDATE, DELETE ON public.app_settings TO authenticated;
GRANT SELECT ON public.admin_users TO authenticated;

DROP POLICY IF EXISTS "Public can read active videos" ON public.videos;
CREATE POLICY "Public can read active videos" ON public.videos
FOR SELECT TO anon, authenticated USING (is_active = true);
DROP POLICY IF EXISTS "Admins manage videos" ON public.videos;
CREATE POLICY "Admins manage videos" ON public.videos
FOR ALL TO authenticated
USING (EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = (SELECT auth.uid())))
WITH CHECK (EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = (SELECT auth.uid())));

DROP POLICY IF EXISTS "Public can read public app settings" ON public.app_settings;
CREATE POLICY "Public can read public app settings" ON public.app_settings
FOR SELECT TO anon, authenticated USING (is_public = true);
DROP POLICY IF EXISTS "Admins manage app settings" ON public.app_settings;
CREATE POLICY "Admins manage app settings" ON public.app_settings
FOR ALL TO authenticated
USING (EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = (SELECT auth.uid())))
WITH CHECK (EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = (SELECT auth.uid())));

DROP POLICY IF EXISTS "Admin can view own account" ON public.admin_users;
CREATE POLICY "Admin can view own account" ON public.admin_users
FOR SELECT TO authenticated USING (user_id = (SELECT auth.uid()));

INSERT INTO public.app_settings(key,value,is_public)
VALUES
 ('app_config','{"youtube_channels":["lucasdramascurtos"],"youtube_channel_id":"UCmbVDAVpU2jKOQ_HTLtvxwA","remote_catalog_source":"supabase","app_remote_content_enabled":true,"video_count":0}'::jsonb,true),
 ('remote_config','{}'::jsonb,true)
ON CONFLICT (key) DO NOTHING;

CREATE INDEX IF NOT EXISTS videos_active_source_idx ON public.videos(is_active,source);
