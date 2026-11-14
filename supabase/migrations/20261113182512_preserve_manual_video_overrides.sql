ALTER TABLE public.videos
ADD COLUMN IF NOT EXISTS manual_override boolean NOT NULL DEFAULT false;
