## Working Defaults (Mandatory)

1. No commit or push unless user explicitly says: "commit" or "push".
2. For non-trivial changes: discuss first, propose design, wait for approval, then implement.
3. Default mode is `DISCUSS ONLY` unless user explicitly asks to `IMPLEMENT`.
4. Prefer standard/library solutions first; if no standard option exists, say so before custom code.
5. No defensive coding and no legacy fallback logic unless explicitly requested.
6. Fail fast by default.
7. Keep data flow raw and direct; avoid unnecessary transformation layers.
8. Minimize code size and complexity; justify any net increase before editing.
9. Max two implementation attempts per issue; if unresolved, stop and propose a minimal reproducible example.
10. After each implementation step: list changed files and rationale, then stop for confirmation.
11. Do not run exploratory benchmarks/experiments for curiosity questions unless explicitly requested by the user.

## Optimal Change (Preferred, Non-Mandatory)

1. Prefer changes that keep net lines of code as low as possible while improving clarity, separation of concerns, and long-term maintainability.
2. Add code only when it is the standard and simplest path to a better end result; otherwise reduce complexity by refactoring and removing unnecessary dependencies, wrappers, and tooling overhead.
