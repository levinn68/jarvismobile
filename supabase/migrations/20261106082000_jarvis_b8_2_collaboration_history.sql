-- ============================================================================
-- J.A.R.V.I.S. Batch 8.2 — Collaboration Sessions, Messages & History Search
-- Preserves all B7 and B8.1 tables, triggers, and RLS policies.
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.jarvis_collaboration_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    session_key TEXT NOT NULL,
    title TEXT NOT NULL,
    topic TEXT NOT NULL,
    selection_mode TEXT NOT NULL DEFAULT 'manual' CHECK (selection_mode IN ('auto', 'manual')),
    collaboration_mode TEXT NOT NULL DEFAULT 'multi_perspective' CHECK (
        collaboration_mode IN (
            'parallel',
            'sequential',
            'debate',
            'consensus',
            'specialist',
            'reviewer',
            'ensemble',
            'multi_perspective',
            'debate_pro_contra',
            'structured_workflow',
            'direct_synthesis',
            'peer_review',
            'red_team_audit',
            'executive_council'
        )
    ),
    assign_roles BOOLEAN NOT NULL DEFAULT true,
    enable_knowledge_graph BOOLEAN NOT NULL DEFAULT true,
    current_round INTEGER NOT NULL DEFAULT 1 CHECK (current_round >= 1 AND current_round <= 10),
    total_rounds INTEGER NOT NULL DEFAULT 3 CHECK (total_rounds >= 1 AND total_rounds <= 10),
    status TEXT NOT NULL DEFAULT 'completed' CHECK (status IN ('running', 'paused', 'completed', 'stopped', 'cancelled', 'failed')),
    consensus_score INTEGER NOT NULL DEFAULT 90 CHECK (consensus_score >= 0 AND consensus_score <= 100),
    team_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    events_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    debate_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    workflow_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    knowledge_graph_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    synthesis_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc', now()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc', now()),
    CONSTRAINT jarvis_collab_sessions_id_user_id_key UNIQUE (id, user_id),
    CONSTRAINT jarvis_collab_sessions_user_session_key UNIQUE (user_id, session_key)
);

CREATE TABLE IF NOT EXISTS public.jarvis_collaboration_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    round_number INTEGER NOT NULL DEFAULT 1 CHECK (round_number >= 1 AND round_number <= 10),
    sequence_number INTEGER NOT NULL DEFAULT 1 CHECK (sequence_number >= 1),
    agent_id TEXT NOT NULL,
    model_id TEXT NOT NULL,
    model_name TEXT NOT NULL,
    role_name TEXT NOT NULL,
    stance_badge TEXT NOT NULL DEFAULT 'Speaking',
    content TEXT NOT NULL,
    key_points_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    confidence NUMERIC(4,3) NOT NULL DEFAULT 0.900,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc', now()),
    CONSTRAINT jarvis_collab_messages_session_user_fk
        FOREIGN KEY (session_id, user_id)
        REFERENCES public.jarvis_collaboration_sessions(id, user_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_jarvis_collab_sessions_user_updated
    ON public.jarvis_collaboration_sessions(user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_jarvis_collab_messages_session_round
    ON public.jarvis_collaboration_messages(session_id, round_number ASC, sequence_number ASC);

ALTER TABLE public.jarvis_collaboration_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.jarvis_collaboration_sessions FORCE ROW LEVEL SECURITY;

ALTER TABLE public.jarvis_collaboration_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.jarvis_collaboration_messages FORCE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.jarvis_collaboration_sessions FROM anon, public;
REVOKE ALL ON TABLE public.jarvis_collaboration_messages FROM anon, public;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.jarvis_collaboration_sessions TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.jarvis_collaboration_messages TO authenticated;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'jarvis_collaboration_sessions' AND policyname = 'jarvis_collab_sessions_select_own'
    ) THEN
        CREATE POLICY jarvis_collab_sessions_select_own ON public.jarvis_collaboration_sessions
            FOR SELECT TO authenticated USING (auth.uid() = user_id);
        CREATE POLICY jarvis_collab_sessions_insert_own ON public.jarvis_collaboration_sessions
            FOR INSERT TO authenticated WITH CHECK (auth.uid() = user_id);
        CREATE POLICY jarvis_collab_sessions_update_own ON public.jarvis_collaboration_sessions
            FOR UPDATE TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
        CREATE POLICY jarvis_collab_sessions_delete_own ON public.jarvis_collaboration_sessions
            FOR DELETE TO authenticated USING (auth.uid() = user_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'jarvis_collaboration_messages' AND policyname = 'jarvis_collab_messages_select_own'
    ) THEN
        CREATE POLICY jarvis_collab_messages_select_own ON public.jarvis_collaboration_messages
            FOR SELECT TO authenticated USING (auth.uid() = user_id);
        CREATE POLICY jarvis_collab_messages_insert_own ON public.jarvis_collaboration_messages
            FOR INSERT TO authenticated WITH CHECK (auth.uid() = user_id);
        CREATE POLICY jarvis_collab_messages_update_own ON public.jarvis_collaboration_messages
            FOR UPDATE TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
        CREATE POLICY jarvis_collab_messages_delete_own ON public.jarvis_collaboration_messages
            FOR DELETE TO authenticated USING (auth.uid() = user_id);
    END IF;
END $$;

DROP TRIGGER IF EXISTS trg_jarvis_collab_sessions_updated_at ON public.jarvis_collaboration_sessions;
CREATE TRIGGER trg_jarvis_collab_sessions_updated_at
    BEFORE UPDATE ON public.jarvis_collaboration_sessions
    FOR EACH ROW EXECUTE FUNCTION public.jarvis_touch_b8_updated_at();
