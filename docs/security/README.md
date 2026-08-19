# Security documentation

Model in one line: **Supabase Auth issues the JWT, Spring Boot decides what the caller may
do, PostgreSQL RLS is defense in depth.**

Non-negotiable rules:

- validate the Supabase JWT in Spring Security as an OAuth2 resource server;
- resolve `app_user` and the active `membership` server-side on every request - never trust
  a client-supplied company id;
- enforce organization/company ownership in services **and** in repository queries;
- treat RLS as a second line of defense, never as a substitute for backend authorization;
- frontend hiding is a UX hint, never authorization;
- secrets live only in untracked local env files; this repository contains `.env.example`
  placeholders only.

`SECURITY_BASELINE.md` is produced by Step 03 (backend security foundation) and will
document the filter chain, the tenancy resolution flow, the role model and the negative
tests that prove cross-tenant access is refused.
