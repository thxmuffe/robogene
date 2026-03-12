import { spawnSync } from 'node:child_process';

export function commandAvailable(command, args = ['--version']) {
  const candidates = process.platform === 'win32'
    ? [command, `${command}.cmd`, `${command}.ps1`]
    : [command];

  return candidates.some((candidate) => {
    const result = spawnSync(candidate, args, {
      stdio: 'ignore',
      shell: process.platform === 'win32' && candidate.endsWith('.ps1'),
    });
    return result.status === 0;
  });
}

export function killByPattern(pattern) {
  spawnSync('pkill', ['-f', pattern], { stdio: 'ignore' });
}
