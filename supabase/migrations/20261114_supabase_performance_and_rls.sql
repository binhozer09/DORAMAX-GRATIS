-- DORAMAX-GRATIS: migration de desempenho e politicas RLS.
-- Aplicada ao projeto Supabase qbwsaahlkovrejwliefv em 2026-11-14.
-- Operacoes idempotentes para manter o schema reproduzivel.

create index if not exists videos_active_position_created_at_idx
  on public.videos (position asc, created_at desc)
  where is_active = true;

alter table public.profiles
  alter column user_id set not null;

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conrelid = 'public.profiles'::regclass
      and contype = 'p'
  ) then
    alter table public.profiles
      add constraint profiles_pkey primary key (user_id);
  end if;
end
$$;

drop index if exists public.profiles_user_id_uidx;

create index if not exists payment_click_notifications_user_id_idx
  on public.payment_click_notifications (user_id);

-- O usuario autenticado pode consultar o proprio registro de administrador.
drop policy if exists "Admin can view own account" on public.admin_users;
create policy "Admin can view own account"
  on public.admin_users for select to authenticated
  using (user_id = (select auth.uid()));

-- Administradores podem consultar e alterar permissoes; cada usuario
-- continua podendo consultar somente a propria permissao.
drop policy if exists "Only admin can view access" on public.access_grants;
create policy "Only admin can view access"
  on public.access_grants for select to authenticated
  using (exists (
    select 1 from public.admin_users a
    where a.user_id = (select auth.uid())
  ));

drop policy if exists "Only admin can insert access" on public.access_grants;
create policy "Only admin can insert access"
  on public.access_grants for insert to authenticated
  with check (exists (
    select 1 from public.admin_users a
    where a.user_id = (select auth.uid())
  ));

drop policy if exists "Only admin can update access" on public.access_grants;
create policy "Only admin can update access"
  on public.access_grants for update to authenticated
  using (exists (
    select 1 from public.admin_users a
    where a.user_id = (select auth.uid())
  ))
  with check (exists (
    select 1 from public.admin_users a
    where a.user_id = (select auth.uid())
  ));

drop policy if exists users_view_own_access on public.access_grants;
create policy users_view_own_access
  on public.access_grants for select to authenticated
  using (user_id = (select auth.uid()));

-- Perfis: o usuario consulta o proprio perfil; administradores consultam todos.
drop policy if exists users_view_own_profile on public.profiles;
create policy users_view_own_profile
  on public.profiles for select to authenticated
  using (user_id = (select auth.uid()));

drop policy if exists admins_view_all_profiles on public.profiles;
create policy admins_view_all_profiles
  on public.profiles for select to authenticated
  using (exists (
    select 1 from public.admin_users a
    where a.user_id = (select auth.uid())
  ));

-- Notificacoes de pagamento sao visiveis somente para administradores.
drop policy if exists "Admins can read payment click notifications"
  on public.payment_click_notifications;
create policy "Admins can read payment click notifications"
  on public.payment_click_notifications for select to authenticated
  using (exists (
    select 1 from public.admin_users a
    where a.user_id = (select auth.uid())
  ));
