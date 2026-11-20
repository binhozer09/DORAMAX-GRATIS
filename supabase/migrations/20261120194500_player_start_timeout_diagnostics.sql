-- Classify videos that never reach PLAYING within the app's 20-second start window.
alter table public.player_diagnostics
  drop constraint if exists player_diagnostics_event_check;
alter table public.player_diagnostics
  add constraint player_diagnostics_event_check
  check (event in ('youtube_player_error','youtube_player_playing','youtube_player_start_timeout'));

drop function if exists public.admin_player_diagnostics();
create function public.admin_player_diagnostics()
returns table (
  video_id text,
  video_title text,
  error_count bigint,
  start_timeout_count bigint,
  successful_starts bigint,
  last_error_at timestamptz,
  last_seen_at timestamptz
)
language plpgsql
security invoker
set search_path = public, auth
as $$
begin
  if auth.uid() is null or not exists (
    select 1 from public.admin_users a where a.user_id = auth.uid()
  ) then
    raise exception 'admin access required' using errcode = '42501';
  end if;
  return query
  select
    d.video_id,
    max(d.video_title),
    count(*) filter (where d.event = 'youtube_player_error'),
    count(*) filter (where d.event = 'youtube_player_start_timeout'),
    count(*) filter (where d.event = 'youtube_player_playing'),
    max(d.created_at) filter (where d.event in ('youtube_player_error','youtube_player_start_timeout')),
    max(d.created_at)
  from public.player_diagnostics d
  where d.created_at >= now() - interval '30 days'
  group by d.video_id
  order by (
    count(*) filter (where d.event = 'youtube_player_error') +
    count(*) filter (where d.event = 'youtube_player_start_timeout')
  ) desc, max(d.created_at) desc
  limit 300;
end;
$$;
revoke all on function public.admin_player_diagnostics() from public, anon;
grant execute on function public.admin_player_diagnostics() to authenticated;
