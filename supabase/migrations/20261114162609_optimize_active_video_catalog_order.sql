CREATE INDEX IF NOT EXISTS videos_active_position_created_at_idx
ON public.videos (position ASC, created_at DESC)
WHERE is_active = true;
