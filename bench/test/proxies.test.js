// The config each proxy adapter generates, checked for the settings that make the comparison fair:
// offline mode, the shared compression threshold, the right forwarding, and rate limits off.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { makeProxy } from '../src/proxies/index.js';

const common = {
  port: 25565,
  backends: { lobby: 25700, survival: 25701, mini: 25702 },
  defaultBackend: 'lobby',
  compressionThreshold: 256,
  compressionLevel: -1,
  secret: 'SECRET',
  cacheDir: '/tmp/cache',
  workDir: '/tmp/work',
  logDir: '/tmp/logs',
  cpus: null,
};

function make(id) {
  const spec = { label: id, version: '1', build: 1, sha256: 'x', url: 'http://x', java: 25 };
  return makeProxy(id, spec, common);
}

describe('Warp config', () => {
  const cfg = make('warp').config();
  it('is offline, modern forwarding, shared compression', () => {
    assert.match(cfg, /online-mode = false/);
    assert.match(cfg, /mode = "velocity"/);
    assert.match(cfg, /threshold = 256/);
    assert.match(cfg, /passthrough = true/);
  });
  it('raises the compression and transport loggers so natives can be verified', () => {
    assert.match(cfg, /dev\.warp\.proxy\.compression\.Compression" = debug/);
  });
  it('uses modern forwarding', () => assert.equal(make('warp').forwarding, 'modern'));
  it('turns the anti-bot and its verification off, as no other proxy has an equivalent', () => {
    assert.match(cfg, /anti-bot \{\n  enabled = false\n  verification \{\n    offline-mode = "never"\n    online-mode = "never"/);
  });
  it('treats a tripped circuit breaker or an anti-bot reaction as a protection', () => {
    const proxy = make('warp');
    proxy.process = { alive: true, lines: ['ok', "Server 'lobby' is skipped by the balancers: it refused the logins of 3 players in a row", 'Bot attack detected: logins of unknown identities at 200/s'] };
    assert.equal(proxy.protections(0).protections, 2);
    assert.match(proxy.protections(0).protectionSample, /skipped by the balancers/);
    assert.equal(proxy.protections(2).protections, 1, 'only lines after the mark count');
  });
});

describe('Velocity config', () => {
  const cfg = make('velocity').config();
  it('is offline, modern, rate-limit off, shared compression', () => {
    assert.match(cfg, /online-mode = false/);
    assert.match(cfg, /player-info-forwarding-mode = "modern"/);
    assert.match(cfg, /compression-threshold = 256/);
    assert.match(cfg, /login-ratelimit = 0/);
  });
});

describe('Gate config', () => {
  it('full mode: offline, velocity forwarding, quotas off', () => {
    const cfg = make('gate').fullConfig();
    assert.match(cfg, /onlineMode: false/);
    assert.match(cfg, /mode: velocity/);
    assert.match(cfg, /threshold: 256/);
    assert.match(cfg, /enabled: false/); // quota
    assert.equal(make('gate').forwarding, 'modern');
  });
  it('lite mode: a route to the default backend and no forwarding', () => {
    const lite = make('gatelite');
    const cfg = lite.liteConfig();
    assert.match(cfg, /lite:/);
    assert.match(cfg, /25700/);
    assert.equal(lite.forwarding, 'none');
  });
});

describe('BungeeCord config', () => {
  const cfg = make('bungeecord').config();
  it('is offline, legacy ip_forward, throttle off, shared compression', () => {
    assert.match(cfg, /online_mode: false/);
    assert.match(cfg, /ip_forward: true/);
    assert.match(cfg, /connection_throttle: -1/);
    assert.match(cfg, /network_compression_threshold: 256/);
    assert.equal(make('bungeecord').forwarding, 'legacy');
  });
});

// The natives check reads the start-up log: the fallback line must not pass as "confirmed".
describe('natives check', () => {
  const withLines = (id, lines) => {
    const p = make(id);
    p.process = { lines };
    return p.verifyNatives();
  };

  it('Velocity: confirms libdeflate and epoll from the connections line', () => {
    const r = withLines('velocity', ['[INFO]: Connections will use epoll channels, libdeflate (Linux x86_64) compression, OpenSSL 3.x.x (Linux x86_64) ciphers']);
    assert.equal(r.ok, true);
    assert.equal(r.checked, true);
  });

  it('Velocity: marks the Java compression fallback unfair even with epoll', () => {
    const r = withLines('velocity', ['[INFO]: Connections will use epoll channels, Java compression, Java ciphers']);
    assert.equal(r.ok, false);
    assert.ok(r.notes.some((n) => /^compression: NOT CONFIRMED/.test(n)), r.notes.join('\n'));
  });

  it('BungeeCord: requires the native compressor and epoll, reports the cipher', () => {
    const ok = withLines('bungeecord', ['Using mbed TLS based native cipher.', 'Using zlib based native compressor.', 'Epoll is working, utilising it!']);
    assert.equal(ok.ok, true);
    const javaZlib = withLines('bungeecord', ['Using mbed TLS based native cipher.', 'Epoll is working, utilising it!']);
    assert.equal(javaZlib.ok, false);
  });

  it('Warp: requires libdeflate and the epoll transport', () => {
    const r = withLines('warp', ['Compression: libdeflate 1.26', 'Using epoll native transport']);
    assert.equal(r.ok, true);
    assert.equal(withLines('warp', ['Compression: java.util.zip', 'Using epoll native transport']).ok, false);
  });

  it('Gate: has nothing native to check', () => {
    const r = withLines('gate', ['INFO listening on 0.0.0.0:25565']);
    assert.equal(r.checked, false);
    assert.equal(r.ok, true);
  });
});
