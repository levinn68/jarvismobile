-- ============================================================================
-- J.A.R.V.I.S. Batch 8.3 / B8.1.1 R1 — Extensibility (MCP Servers, Skills Marketplace & Generated Artifacts)
-- Preserves all B7, B8.1, and B8.2 tables, triggers, and RLS policies.
-- ============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS public.jarvis_mcp_servers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    server_key TEXT NOT NULL,
    display_name TEXT NOT NULL,
    transport_type TEXT NOT NULL DEFAULT 'http_jsonrpc' CHECK (transport_type IN ('http_jsonrpc', 'sse', 'stdio_bridge')),
    endpoint_url TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    connection_status TEXT NOT NULL DEFAULT 'active' CHECK (connection_status IN ('active', 'disconnected', 'error')),
    permission_tier TEXT NOT NULL DEFAULT 'write_requires_approval' CHECK (permission_tier IN ('read_only', 'write_requires_approval', 'admin_blocked')),
    tools_count INTEGER NOT NULL DEFAULT 0 CHECK (tools_count >= 0),
    tools_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc', now()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc', now()),
    CONSTRAINT jarvis_mcp_servers_user_server_key UNIQUE (user_id, server_key)
);

CREATE TABLE IF NOT EXISTS public.jarvis_installed_skills (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    skill_id TEXT NOT NULL,
    skill_name TEXT NOT NULL,
    version TEXT NOT NULL DEFAULT '1.0.0',
    author TEXT NOT NULL DEFAULT 'J.A.R.V.I.S. Labs',
    category TEXT NOT NULL DEFAULT 'Produktivitas',
    installed BOOLEAN NOT NULL DEFAULT true,
    enabled BOOLEAN NOT NULL DEFAULT true,
    permissions_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    dependencies_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    manifest_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    installed_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc', now()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc', now()),
    CONSTRAINT jarvis_installed_skills_user_skill_key UNIQUE (user_id, skill_id)
);

CREATE TABLE IF NOT EXISTS public.jarvis_generated_artifacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    artifact_type TEXT NOT NULL CHECK (artifact_type IN ('markdown', 'pdf', 'pdf_spec', 'presentation', 'spreadsheet', 'chart', 'research_report')),
    title TEXT NOT NULL,
    filename TEXT NOT NULL,
    mime_type TEXT NOT NULL DEFAULT 'text/markdown',
    byte_size INTEGER NOT NULL DEFAULT 0 CHECK (byte_size >= 0),
    sha256 TEXT NOT NULL DEFAULT '',
    content_text TEXT NOT NULL DEFAULT '',
    content_base64 TEXT NOT NULL DEFAULT '',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc', now()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc', now())
);

CREATE INDEX IF NOT EXISTS idx_jarvis_mcp_servers_user ON public.jarvis_mcp_servers(user_id);
CREATE INDEX IF NOT EXISTS idx_jarvis_installed_skills_user ON public.jarvis_installed_skills(user_id, category);
CREATE INDEX IF NOT EXISTS idx_jarvis_generated_artifacts_user ON public.jarvis_generated_artifacts(user_id, created_at DESC);

ALTER TABLE public.jarvis_mcp_servers ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.jarvis_mcp_servers FORCE ROW LEVEL SECURITY;

ALTER TABLE public.jarvis_installed_skills ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.jarvis_installed_skills FORCE ROW LEVEL SECURITY;

ALTER TABLE public.jarvis_generated_artifacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.jarvis_generated_artifacts FORCE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.jarvis_mcp_servers FROM anon, public;
REVOKE ALL ON TABLE public.jarvis_installed_skills FROM anon, public;
REVOKE ALL ON TABLE public.jarvis_generated_artifacts FROM anon, public;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.jarvis_mcp_servers TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.jarvis_installed_skills TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.jarvis_generated_artifacts TO authenticated;

DROP POLICY IF EXISTS jarvis_mcp_servers_select_own ON public.jarvis_mcp_servers;
CREATE POLICY jarvis_mcp_servers_select_own ON public.jarvis_mcp_servers
    FOR SELECT TO authenticated USING ((select auth.uid()) is not null and (select auth.uid()) = user_id);

DROP POLICY IF EXISTS jarvis_mcp_servers_insert_own ON public.jarvis_mcp_servers;
CREATE POLICY jarvis_mcp_servers_insert_own ON public.jarvis_mcp_servers
    FOR INSERT TO authenticated WITH CHECK ((select auth.uid()) is not null and (select auth.uid()) = user_id);

DROP POLICY IF EXISTS jarvis_mcp_servers_update_own ON public.jarvis_mcp_servers;
CREATE POLICY jarvis_mcp_servers_update_own ON public.jarvis_mcp_servers
    FOR UPDATE TO authenticated
    USING ((select auth.uid()) is not null and (select auth.uid()) = user_id)
    WITH CHECK ((select auth.uid()) is not null and (select auth.uid()) = user_id);

DROP POLICY IF EXISTS jarvis_mcp_servers_delete_own ON public.jarvis_mcp_servers;
CREATE POLICY jarvis_mcp_servers_delete_own ON public.jarvis_mcp_servers
    FOR DELETE TO authenticated USING ((select auth.uid()) is not null and (select auth.uid()) = user_id);

DROP POLICY IF EXISTS jarvis_installed_skills_select_own ON public.jarvis_installed_skills;
CREATE POLICY jarvis_installed_skills_select_own ON public.jarvis_installed_skills
    FOR SELECT TO authenticated USING ((select auth.uid()) is not null and (select auth.uid()) = user_id);

DROP POLICY IF EXISTS jarvis_installed_skills_insert_own ON public.jarvis_installed_skills;
CREATE POLICY jarvis_installed_skills_insert_own ON public.jarvis_installed_skills
    FOR INSERT TO authenticated WITH CHECK ((select auth.uid()) is not null and (select auth.uid()) = user_id);

DROP POLICY IF EXISTS jarvis_installed_skills_update_own ON public.jarvis_installed_skills;
CREATE POLICY jarvis_installed_skills_update_own ON public.jarvis_installed_skills
    FOR UPDATE TO authenticated
    USING ((select auth.uid()) is not null and (select auth.uid()) = user_id)
    WITH CHECK ((select auth.uid()) is not null and (select auth.uid()) = user_id);

DROP POLICY IF EXISTS jarvis_installed_skills_delete_own ON public.jarvis_installed_skills;
CREATE POLICY jarvis_installed_skills_delete_own ON public.jarvis_installed_skills
    FOR DELETE TO authenticated USING ((select auth.uid()) is not null and (select auth.uid()) = user_id);

DROP POLICY IF EXISTS jarvis_generated_artifacts_select_own ON public.jarvis_generated_artifacts;
CREATE POLICY jarvis_generated_artifacts_select_own ON public.jarvis_generated_artifacts
    FOR SELECT TO authenticated USING ((select auth.uid()) is not null and (select auth.uid()) = user_id);

DROP POLICY IF EXISTS jarvis_generated_artifacts_insert_own ON public.jarvis_generated_artifacts;
CREATE POLICY jarvis_generated_artifacts_insert_own ON public.jarvis_generated_artifacts
    FOR INSERT TO authenticated WITH CHECK ((select auth.uid()) is not null and (select auth.uid()) = user_id);

DROP POLICY IF EXISTS jarvis_generated_artifacts_update_own ON public.jarvis_generated_artifacts;
CREATE POLICY jarvis_generated_artifacts_update_own ON public.jarvis_generated_artifacts
    FOR UPDATE TO authenticated
    USING ((select auth.uid()) is not null and (select auth.uid()) = user_id)
    WITH CHECK ((select auth.uid()) is not null and (select auth.uid()) = user_id);

DROP POLICY IF EXISTS jarvis_generated_artifacts_delete_own ON public.jarvis_generated_artifacts;
CREATE POLICY jarvis_generated_artifacts_delete_own ON public.jarvis_generated_artifacts
    FOR DELETE TO authenticated USING ((select auth.uid()) is not null and (select auth.uid()) = user_id);

DROP TRIGGER IF EXISTS trg_jarvis_mcp_servers_updated_at ON public.jarvis_mcp_servers;
CREATE TRIGGER trg_jarvis_mcp_servers_updated_at
    BEFORE UPDATE ON public.jarvis_mcp_servers
    FOR EACH ROW EXECUTE FUNCTION public.jarvis_touch_b8_updated_at();

DROP TRIGGER IF EXISTS trg_jarvis_installed_skills_updated_at ON public.jarvis_installed_skills;
CREATE TRIGGER trg_jarvis_installed_skills_updated_at
    BEFORE UPDATE ON public.jarvis_installed_skills
    FOR EACH ROW EXECUTE FUNCTION public.jarvis_touch_b8_updated_at();

DROP TRIGGER IF EXISTS trg_jarvis_generated_artifacts_updated_at ON public.jarvis_generated_artifacts;
CREATE TRIGGER trg_jarvis_generated_artifacts_updated_at
    BEFORE UPDATE ON public.jarvis_generated_artifacts
    FOR EACH ROW EXECUTE FUNCTION public.jarvis_touch_b8_updated_at();

COMMIT;
