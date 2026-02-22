import net from 'node:net';

export async function getFreePort(preferred = 0) {
  const tryPort = (port) =>
    new Promise((resolve, reject) => {
      const server = net.createServer();
      server.unref();
      server.on('error', reject);
      server.listen({ port, host: '127.0.0.1' }, () => {
        const address = server.address();
        const resolved = typeof address === 'object' && address ? address.port : port;
        server.close(() => resolve(resolved));
      });
    });

  if (preferred > 0) {
    try {
      return await tryPort(preferred);
    } catch {
      return await tryPort(0);
    }
  }
  return await tryPort(0);
}
