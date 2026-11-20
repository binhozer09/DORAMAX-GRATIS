-- Preserve technical context for each failed player start.
alter table public.player_diagnostics
  add column if not exists details jsonb not null default '{}'::jsonb;

drop function if exists public.admin_player_diagnostics();
create function public.admin_player_diagnostics()
returns table (
  video_id text,
  video_title text,
  error_count bigint,
  start_timeout_count bigint,
  successful_starts bigint,
  last_error_at timestamptz,
  last_seen_at timestamptz,
  last_failure_details jsonb
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
    max(d.created_at),
    (select d2.details from public.player_diagnostics d2
      where d2.video_id=d.video_id
        and d2.event in ('youtube_player_error','youtube_player_start_timeout')
        and d2.created_at >= now() - interval '30 days'
      order by d2.created_at desc limit 1)
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
