## Working Defaults (Mandatory)

1. No commit or push unless user explicitly says: "commit" or "push".
2. Implement by default when the user asks for a change; do not pause for approval on normal edits.
3. Ask for confirmation only before high-risk/destructive actions (e.g., irreversible data deletion, large refactors with broad impact, production-impacting operations) or when requirements are ambiguous enough to risk wrong implementation.
4. Treat venting, frustration, criticism, or other commentary as non-actionable by default. Do not implement, edit, or run commands unless the user explicitly asks for a change, fix, or command.
5. Prefer standard/library solutions first; if no standard option exists, say so before custom code.
6. No defensive coding and no legacy fallback logic unless explicitly requested.
7. Fail fast by default.
8. Keep data flow raw and direct; avoid unnecessary transformation layers.
9. Minimize code size and complexity; justify any net increase before editing.
10. Max two implementation attempts per issue; if unresolved, stop and propose a minimal reproducible example.
11. After each implementation step: list changed files and rationale, then continue unless user asks to pause.
12. Do not run exploratory benchmarks/experiments for curiosity questions unless explicitly requested by the user.
13. Avoid file renames/moves when possible; prefer keeping paths stable so humans can review git diffs more easily.
14. Branch policy: do all feature development on `dev`. Only pipeline, script, and test fixes may be implemented directly on `main`.
15. For manual runtime verification, never use the user's default app ports (`8080`, `7071`, `9630`, `9631`). Use isolated ports or the repo's dedicated isolated launch profile instead.
16. Do not run commands that can stop or restart the user's live app session (`start`, `start:release`, `stop:dev`, e2e startup scripts) against the default ports unless the user explicitly asks.
17. Assume the user normally runs exactly one Codex instance for this repo. Do not optimize for multi-agent coordination unless the user explicitly says multiple agents or CLIs are active.
18. If the user later runs multiple agents or CLIs in parallel, keep changes narrowly scoped, re-read touched files before editing, and avoid overwriting work you did not make.
19. Do not run builds during normal development just to be safe. The user's dev loop already relies on hot reload from the existing local app session.
20. Run a build only when it is the minimal direct way to verify syntax, type errors, packaging, or another issue that cannot be checked reliably from local code inspection.
21. Do not run tests unless the user asks, or you are preparing for a push, or a test run is the only practical way to verify the requested change.
22. Before pushing, run the relevant tests needed to catch regressions in the changed area unless the user explicitly tells you not to.
23. If the user explicitly says to "put this to todo list" or clearly means the same thing for a decided-not-to-implement item, treat that as a GitHub issue task: check existing repo issues for duplicates first, merge new context into the existing issue if it is a duplicate, otherwise create a new issue. Do not create duplicate todo issues for the same underlying problem.

## Optimal Change (Preferred, Non-Mandatory)

1. Prefer changes that keep net lines of code as low as possible while improving clarity, separation of concerns, and long-term maintainability.
2. Add code only when it is the standard and simplest path to a better end result; otherwise reduce complexity by refactoring and removing unnecessary dependencies, wrappers, and tooling overhead.
