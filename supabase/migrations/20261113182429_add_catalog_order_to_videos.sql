ALTER TABLE public.videos
ADD COLUMN IF NOT EXISTS position integer NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS videos_active_position_idx ON public.videos(is_active,position);
