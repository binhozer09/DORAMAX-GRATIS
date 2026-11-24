create table if not exists public.doramax_payments (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  asaas_payment_id text not null unique,
  amount numeric(10,2) not null default 5.00 check (amount = 5.00),
  status text not null default 'PENDING',
  pix_payload text,
  pix_encoded_image text,
  invoice_url text,
  created_at timestamptz not null default now(),
  paid_at timestamptz,
  updated_at timestamptz not null default now()
);
create index if not exists doramax_payments_user_created_idx
  on public.doramax_payments(user_id, created_at desc);
alter table public.doramax_payments enable row level security;
revoke all on public.doramax_payments from anon, authenticated;
