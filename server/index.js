// Sample Socket.IO collector for the keystroker research project.
//
// SECURITY: This server is intentionally minimal and is meant to run on your own
// machine / lab for authorized testing. It validates a bearer token, logs received
// events, acks the synced ids back to the device, and writes a JSONL archive.
//
// For HTTPS + cert pinning tests, run it behind ngrok or mkcert (see README.md).

const fs = require('fs');
const path = require('path');
const http = require('http');
const express = require('express');
const { Server } = require('socket.io');

const PORT = process.env.PORT || 3000;
const AUTH_TOKEN = process.env.AUTH_TOKEN || 'replace-with-your-research-token';
const ARCHIVE_DIR = path.join(__dirname, 'archive');

if (!fs.existsSync(ARCHIVE_DIR)) fs.mkdirSync(ARCHIVE_DIR, { recursive: true });

function archivePathFor(deviceId) {
    const safe = String(deviceId || 'unknown').replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 64);
    return path.join(ARCHIVE_DIR, `${safe}.jsonl`);
}

function authorize(payload) {
    if (!payload || typeof payload !== 'object') return false;
    return payload.auth === AUTH_TOKEN;
}

const app = express();
app.get('/', (_req, res) => res.send('keystroker research server: ok'));
const server = http.createServer(app);
const io = new Server(server, {
    cors: { origin: '*' },
    transports: ['websocket'],
});

io.on('connection', (socket) => {
    console.log(`[+] client connected: ${socket.id}`);

    socket.on('log_data', (payload, ack) => {
        if (!authorize(payload)) {
            console.warn(`[!] unauthorized log_data from ${socket.id}`);
            return; // do not ack -> client will retry later
        }
        const events = Array.isArray(payload.events) ? payload.events : [];
        const syncedIds = [];
        try {
            const out = fs.createWriteStream(archivePathFor(payload.device_id), { flags: 'a' });
            for (const ev of events) {
                out.write(JSON.stringify({ ...ev, device_id: payload.device_id }) + '\n');
                if (ev && ev.id != null) syncedIds.push(ev.id);
            }
            out.end();
        } catch (e) {
            console.error(`[!] archive write failed: ${e.message}`);
        }
        console.log(`[*] ${payload.device_id}: received ${events.length} events`);
        if (typeof ack === 'function') ack({ synced_ids: syncedIds });
        // Also emit so the socket.io-client "on" handler fires (client expects an event).
        socket.emit('data_received', { synced_ids: syncedIds });
    });

    socket.on('heartbeat', (payload) => {
        if (!authorize(payload)) return;
        console.log(`[hb] ${payload.device_id}`);
    });

    socket.on('disconnect', () => console.log(`[-] client disconnected: ${socket.id}`));
});

server.listen(PORT, () => {
    console.log(`keystroker research server listening on http://localhost:${PORT}`);
    console.log(`expected AUTH_TOKEN: ${AUTH_TOKEN}`);
});
