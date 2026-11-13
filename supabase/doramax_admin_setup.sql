-- DORAMAX GRÁTIS: permissões e sincronização do painel ADM
-- Execute uma única vez no Supabase > SQL Editor.

grant select on public.profiles to authenticated;
grant select on public.admin_users to authenticated;
grant select on public.access_grants to authenticated;
grant update on public.access_grants to authenticated;

-- Permite que cada usuário autenticado confirme se sua própria conta é administradora.
drop policy if exists users_view_own_admin_record on public.admin_users;
create policy users_view_own_admin_record
on public.admin_users
for select
to authenticated
using (user_id = auth.uid());

drop policy if exists admins_view_all_profiles on public.profiles;
create policy admins_view_all_profiles
on public.profiles
for select
to authenticated
using (
  exists (
    select 1 from public.admin_users a
    where a.user_id = auth.uid()
  )
);

drop policy if exists admins_view_all_access_grants on public.access_grants;
create policy admins_view_all_access_grants
on public.access_grants
for select
to authenticated
using (
  exists (
    select 1 from public.admin_users a
    where a.user_id = auth.uid()
  )
);

drop policy if exists admins_update_access_grants on public.access_grants;
create policy admins_update_access_grants
on public.access_grants
for update
to authenticated
using (
  exists (
    select 1 from public.admin_users a
    where a.user_id = auth.uid()
  )
)
with check (
  exists (
    select 1 from public.admin_users a
    where a.user_id = auth.uid()
  )
);

-- Garante perfil e registro de acesso também para contas criadas antes do gatilho.
insert into public.profiles (user_id, email)
select id, email
from auth.users
on conflict (user_id) do update
set email = excluded.email;

insert into public.access_grants (user_id, is_unlocked)
select id, false
from auth.users
on conflict (user_id) do nothing;
