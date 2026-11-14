-- Consolidate overlapping SELECT policies while preserving public catalog reads
-- and admin access to inactive/private rows.
DROP POLICY IF EXISTS "Only admin can view access" ON public.access_grants;
DROP POLICY IF EXISTS users_view_own_access ON public.access_grants;
CREATE POLICY users_and_admins_view_access ON public.access_grants
FOR SELECT TO authenticated
USING (user_id = (SELECT auth.uid()) OR EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = (SELECT auth.uid())));

DROP POLICY IF EXISTS admins_view_all_profiles ON public.profiles;
DROP POLICY IF EXISTS users_view_own_profile ON public.profiles;
CREATE POLICY users_and_admins_view_profiles ON public.profiles
FOR SELECT TO authenticated
USING (user_id = (SELECT auth.uid()) OR EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = (SELECT auth.uid())));

DROP POLICY IF EXISTS "Admins manage app settings" ON public.app_settings;
DROP POLICY IF EXISTS "Public can read public app settings" ON public.app_settings;
CREATE POLICY public_and_admins_view_app_settings ON public.app_settings
FOR SELECT TO anon, authenticated
USING (is_public = true OR (auth.uid() IS NOT NULL AND EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = (SELECT auth.uid()))));
CREATE POLICY admins_insert_app_settings ON public.app_settings FOR INSERT TO authenticated
WITH CHECK (EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = (SELECT auth.uid())));
CREATE POLICY admins_update_app_settings ON public.app_settings FOR UPDATE TO authenticated
USING (EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = (SELECT auth.uid())))
WITH CHECK (EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = (SELECT auth.uid())));
CREATE POLICY admins_delete_app_settings ON public.app_settings FOR DELETE TO authenticated
USING (EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = (SELECT auth.uid())));

DROP POLICY IF EXISTS "Admins manage videos" ON public.videos;
DROP POLICY IF EXISTS "Public can read active videos" ON public.videos;
CREATE POLICY public_and_admins_view_videos ON public.videos
FOR SELECT TO anon, authenticated
USING (is_active = true OR (auth.uid() IS NOT NULL AND EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = (SELECT auth.uid()))));
CREATE POLICY admins_insert_videos ON public.videos FOR INSERT TO authenticated
WITH CHECK (EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = (SELECT auth.uid())));
CREATE POLICY admins_update_videos ON public.videos FOR UPDATE TO authenticated
USING (EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = (SELECT auth.uid())))
WITH CHECK (EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = (SELECT auth.uid())));
CREATE POLICY admins_delete_videos ON public.videos FOR DELETE TO authenticated
USING (EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = (SELECT auth.uid())));
