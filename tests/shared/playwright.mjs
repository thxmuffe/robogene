const FAIL_WORD_RE = /\b(fail|failed|error|exception|uncaught)\b/i;

export function attachConsoleFailureGuard(page, { ignore = [] } = {}) {
  const issues = [];

  const shouldIgnore = (text) => ignore.some((entry) => text.includes(entry));

  const onConsole = (msg) => {
    const text = String(msg.text?.() ?? '');
    if (!text) return;
    if (shouldIgnore(text)) return;
    if (FAIL_WORD_RE.test(text)) {
      issues.push(`[console:${msg.type?.() || 'log'}] ${text}`);
    }
  };

  const onPageError = (err) => {
    const text = String(err?.message || err || '');
    if (!text) return;
    if (shouldIgnore(text)) return;
    issues.push(`[pageerror] ${text}`);
  };

  page.on('console', onConsole);
  page.on('pageerror', onPageError);

  return {
    assertClean() {
      if (issues.length > 0) {
        throw new Error(`Playwright console/page errors detected:\n${issues.join('\n')}`);
      }
    },
    detach() {
      page.off('console', onConsole);
      page.off('pageerror', onPageError);
    },
  };
}
