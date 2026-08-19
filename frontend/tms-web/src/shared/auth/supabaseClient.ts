import { createClient } from '@supabase/supabase-js'
import { appEnv } from '../config/env'

/**
 * The only direct Supabase usage in the app (V1 rule): authentication.
 *
 * `persistSession`/`autoRefreshToken` let supabase-js keep the session valid in the
 * background; `AuthContext` reads whatever session this client currently holds rather than
 * managing tokens itself. No business table is ever queried through this client.
 */
export const supabase = createClient(appEnv.supabaseUrl, appEnv.supabaseAnonKey, {
  auth: {
    persistSession: true,
    autoRefreshToken: true,
    detectSessionInUrl: false,
  },
})
