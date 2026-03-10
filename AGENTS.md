## Working Defaults (Mandatory)

1. No commit or push unless user explicitly says: "commit" or "push".
2. Implement by default when the user asks for a change; do not pause for approval on normal edits.
3. Ask for confirmation only before high-risk/destructive actions (e.g., irreversible data deletion, large refactors with broad impact, production-impacting operations) or when requirements are ambiguous enough to risk wrong implementation.
4. Prefer standard/library solutions first; if no standard option exists, say so before custom code.
5. No defensive coding and no legacy fallback logic unless explicitly requested.
6. Fail fast by default.
7. Keep data flow raw and direct; avoid unnecessary transformation layers.
8. Minimize code size and complexity; justify any net increase before editing.
9. Max two implementation attempts per issue; if unresolved, stop and propose a minimal reproducible example.
10. After each implementation step: list changed files and rationale, then continue unless user asks to pause.
11. Do not run exploratory benchmarks/experiments for curiosity questions unless explicitly requested by the user.
12. Avoid file renames/moves when possible; prefer keeping paths stable so humans can review git diffs more easily.
13. Branch policy: do all feature development on `dev`. Only pipeline, script, and test fixes may be implemented directly on `main`.
14. For manual runtime verification, never use the user's default app ports (`8080`, `7071`, `9630`, `9631`). Use isolated ports or the repo's dedicated isolated launch profile instead.
15. Do not run commands that can stop or restart the user's live app session (`start`, `start:release`, `stop:dev`, e2e startup scripts) against the default ports unless the user explicitly asks.

## Optimal Change (Preferred, Non-Mandatory)

1. Prefer changes that keep net lines of code as low as possible while improving clarity, separation of concerns, and long-term maintainability.
2. Add code only when it is the standard and simplest path to a better end result; otherwise reduce complexity by refactoring and removing unnecessary dependencies, wrappers, and tooling overhead.
