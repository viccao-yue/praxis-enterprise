alter table auth_sessions add column if not exists desktop_renewal_limit timestamp null;
