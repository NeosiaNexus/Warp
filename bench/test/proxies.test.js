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
