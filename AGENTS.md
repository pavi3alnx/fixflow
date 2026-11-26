# FixFlow Git workflow

- Never implement directly on `main`.
- Start each issue on a dedicated feature branch from safely updated `origin/main`.
- Before editing, check the branch, status, and existing work. Inspect an existing feature branch before using it; never overwrite it.
- Preserve unrelated changes, especially `.vscode/settings.json`. If they block switching, use a separate worktree rather than stashing, discarding, or committing them.
- Never commit, push, create or merge a pull request automatically. The user decides when to perform each action.
- Never modify unrelated files or discard user work. Identify agent-created changes before removing them; do not use `git reset --hard` or `git clean -fd`.
- Treat merged Flyway migrations as the schema source of truth. Never modify or baseline the developer's existing database for verification; use disposable PostgreSQL.
