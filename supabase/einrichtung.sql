-- Mein Daten-Digitalisierer: Tabelle für die verschlüsselten Daten
-- In Supabase: SQL Editor → New query → alles einfügen → Run

create table if not exists public.vault_docs (
  user_id    uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  coll       text        not null check (coll in ('meta', 'entries', 'blobs')),
  id         text        not null,
  body       jsonb       not null,
  updated_at timestamptz not null default now(),
  primary key (user_id, coll, id)
);

-- Jeder sieht und ändert nur seine eigenen Einträge
alter table public.vault_docs enable row level security;

drop policy if exists "eigene lesen"   on public.vault_docs;
drop policy if exists "eigene anlegen" on public.vault_docs;
drop policy if exists "eigene ändern"  on public.vault_docs;
drop policy if exists "eigene löschen" on public.vault_docs;

create policy "eigene lesen"   on public.vault_docs for select to authenticated using (auth.uid() = user_id);
create policy "eigene anlegen" on public.vault_docs for insert to authenticated with check (auth.uid() = user_id);
create policy "eigene ändern"  on public.vault_docs for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "eigene löschen" on public.vault_docs for delete to authenticated using (auth.uid() = user_id);

revoke all on public.vault_docs from anon;
grant select, insert, update, delete on public.vault_docs to authenticated;

-- Zeitstempel bei Änderungen aktualisieren
create or replace function public.vault_touch() returns trigger language plpgsql as $$
begin new.updated_at = now(); return new; end $$;
drop trigger if exists vault_touch on public.vault_docs;
create trigger vault_touch before update on public.vault_docs for each row execute function public.vault_touch();
