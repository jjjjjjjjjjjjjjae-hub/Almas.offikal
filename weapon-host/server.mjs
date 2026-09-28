import http from 'node:http';
import { createReadStream, statSync, existsSync } from 'node:fs';
import { extname, join, normalize } from 'node:path';

const root = join(process.cwd(), 'public');
const port = Number(process.env.PORT || 3000);
const mime = {
  '.glb': 'model/gltf-binary',
  '.json': 'application/json; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8'
};

const server = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,HEAD,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', '*');
  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    return res.end();
  }
  if (req.url === '/health') {
    res.writeHead(200, {'Content-Type':'application/json; charset=utf-8'});
    return res.end(JSON.stringify({ok:true}));
  }
  const pathname = decodeURIComponent((req.url || '/').split('?')[0]);
  const rel = normalize(pathname).replace(/^([/\\])+/, '');
  const file = join(root, rel);
  if (!file.startsWith(root) || !existsSync(file)) {
    res.writeHead(404, {'Content-Type':'text/plain; charset=utf-8'});
    return res.end('Not found');
  }
  const st = statSync(file);
  if (!st.isFile()) {
    res.writeHead(404);
    return res.end();
  }
  res.setHeader('Content-Type', mime[extname(file).toLowerCase()] || 'application/octet-stream');
  res.setHeader('Content-Length', st.size);
  res.setHeader('Cache-Control', 'public, max-age=31536000, immutable');
  res.setHeader('Accept-Ranges', 'bytes');

  const range = req.headers.range;
  if (range) {
    const m = /^bytes=(\d*)-(\d*)$/.exec(range);
    if (m) {
      let start = m[1] ? Number(m[1]) : 0;
      let end = m[2] ? Number(m[2]) : st.size - 1;
      start = Math.max(0, start);
      end = Math.min(st.size - 1, end);
      if (start <= end) {
        res.writeHead(206, {
          'Content-Range': `bytes ${start}-${end}/${st.size}`,
          'Content-Length': end - start + 1
        });
        if (req.method === 'HEAD') return res.end();
        return createReadStream(file, {start, end}).pipe(res);
      }
    }
  }

  res.writeHead(200);
  if (req.method === 'HEAD') return res.end();
  createReadStream(file).pipe(res);
});

server.listen(port, '0.0.0.0', () => console.log(`weapon host listening on ${port}`));
