create table if not exists public.player_diagnostics (
  id bigint generated always as identity primary key,
  video_id text not null check (video_id ~ '^[A-Za-z0-9_-]{11}$'),
  video_title text,
  event text not null check (event in ('youtube_player_error','youtube_player_playing')),
  error_code integer,
  created_at timestamptz not null default now()
);
create index if not exists player_diagnostics_created_at_idx on public.player_diagnostics (created_at desc);
create index if not exists player_diagnostics_video_idx on public.player_diagnostics (video_id, created_at desc);
alter table public.player_diagnostics enable row level security;
revoke all on public.player_diagnostics from anon;
grant select on public.player_diagnostics to authenticated;
drop policy if exists admins_read_player_diagnostics on public.player_diagnostics;
create policy admins_read_player_diagnostics
on public.player_diagnostics
for select
to authenticated
using (exists (select 1 from public.admin_users a where a.user_id = auth.uid()));

create or replace function public.admin_player_diagnostics()
returns table (
  video_id text, video_title text, error_count bigint, successful_starts bigint,
  last_error_at timestamptz, last_seen_at timestamptz
)
language plpgsql security invoker
set search_path = public, auth
as $$
begin
  if auth.uid() is null or not exists (
    select 1 from public.admin_users a where a.user_id = auth.uid()
  ) then
    raise exception 'admin access required' using errcode = '42501';
  end if;
  return query
  select d.video_id, max(d.video_title),
         count(*) filter (where d.event = 'youtube_player_error'),
         count(*) filter (where d.event = 'youtube_player_playing'),
         max(d.created_at) filter (where d.event = 'youtube_player_error'),
         max(d.created_at)
  from public.player_diagnostics d
  where d.created_at >= now() - interval '30 days'
  group by d.video_id
  order by count(*) filter (where d.event = 'youtube_player_error') desc, max(d.created_at) desc
  limit 300;
end;
$$;
revoke all on function public.admin_player_diagnostics() from public, anon;
grant execute on function public.admin_player_diagnostics() to authenticated;
