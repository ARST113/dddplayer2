/*
 * serve.js — минимальный HTTP-сервер с поддержкой Range для теста сетевого пути
 * движка. Нужен потому, что серверные функции DDD (TorrServer `/cache`,
 * LocalBridgeServer) отдают видео именно так: HTTP + байтовые диапазоны, и
 * seek в плеере превращается в Range-запрос. Если этот путь не работает —
 * не работает половина DDD, независимо от качества декодера.
 *
 *   node serve.js <файл> [порт]
 *
 * Дальше на хосте: adb -s <serial> reverse tcp:<порт> tcp:<порт>
 * и на устройстве адрес http://127.0.0.1:<порт>/
 */

const http = require('http');
const fs = require('fs');
const path = require('path');

const file = process.argv[2];
const port = Number(process.argv[3] || 8099);

if (!file || !fs.existsSync(file)) {
    console.error(`нет файла: ${file}`);
    process.exit(1);
}

const size = fs.statSync(file).size;
const name = path.basename(file);
let requests = 0;
let ranged = 0;

const server = http.createServer((req, res) => {
    requests++;
    const range = req.headers.range;
    // Лог по каждому запросу: без него нельзя утверждать, что seek по сети
    // действительно превратился в Range-запрос, а не в перекачивание файла.
    console.log(`  #${requests} ${req.method} ${req.url} Range: ${range || '(нет)'}`);

    // HEAD: FFmpeg сначала спрашивает размер и поддержку диапазонов.
    if (req.method === 'HEAD') {
        res.writeHead(200, {
            'Content-Length': size,
            'Content-Type': 'video/mp4',
            'Accept-Ranges': 'bytes',
        });
        return res.end();
    }

    if (!range) {
        res.writeHead(200, {
            'Content-Length': size,
            'Content-Type': 'video/mp4',
            'Accept-Ranges': 'bytes',
        });
        return fs.createReadStream(file).pipe(res);
    }

    ranged++;
    const m = /bytes=(\d*)-(\d*)/.exec(range);
    const start = m[1] ? parseInt(m[1], 10) : 0;
    const end = m[2] ? parseInt(m[2], 10) : size - 1;
    if (start >= size || end >= size || start > end) {
        res.writeHead(416, { 'Content-Range': `bytes */${size}` });
        return res.end();
    }
    res.writeHead(206, {
        'Content-Range': `bytes ${start}-${end}/${size}`,
        'Accept-Ranges': 'bytes',
        'Content-Length': end - start + 1,
        'Content-Type': 'video/mp4',
    });
    fs.createReadStream(file, { start, end }).pipe(res);
});

server.listen(port, '127.0.0.1', () => {
    console.log(`serve: ${name} (${size} байт) на http://127.0.0.1:${port}/${encodeURIComponent(name)}`);
});

// Печатаем статистику при остановке: сколько было Range-запросов — это и есть
// доказательство, что seek по сети реально дошёл до сервера.
const bye = () => {
    console.log(`serve: запросов ${requests}, из них Range ${ranged}`);
    server.close(() => process.exit(0));
};
process.on('SIGINT', bye);
process.on('SIGTERM', bye);
