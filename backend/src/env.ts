export interface Env {
  DB: D1Database;
  SESSIONS: KVNamespace;
}

export interface AuthenticatedUser {
  id: string;
  email: string;
  username: string;
  nickname: string | null;
}

export interface AppVariables {
  user: AuthenticatedUser;
  sessionToken: string;
}
