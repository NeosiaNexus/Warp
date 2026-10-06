// Locates a Java runtime of an exact major version, downloading Eclipse Temurin into the cache
// when none is installed. Old Minecraft servers are picky about their JVM, so "close enough" is not
// good enough: a server pinned to Java 8 gets Java 8.
import { existsSync, readdirSync, readFileSync, mkdirSync, renameSync, rmSync } from 'node:fs';
import { homedir, arch as osArch, platform } from 'node:os';
import { join } from 'node:path';

import { download } from './download.js';
import { run } from './proc.js';

const ADOPTIUM = 'https://api.adoptium.net/v3';

/** Lookups by major version: the server and Warp may both need Java 25, and must share one install. */
const lookups = new Map();

/** Returns the `java` executable of the requested major version. */
export function findJava(major, cacheDir) {
  const key = `${major}:${cacheDir}`;
  if (!lookups.has(key)) lookups.set(key, locateJava(major, cacheDir).catch((e) => { lookups.delete(key); throw e; }));
  return lookups.get(key);
}

async function locateJava(major, cacheDir) {
  const fromEnv = [process.env[`WARP_E2E_JAVA_${major}`], process.env[`JAVA_HOME_${major}_X64`], process.env[`JAVA_HOME_${major}_ARM64`]]
    .filter(Boolean)
    .map((home) => (home.endsWith('/java') ? home : join(home, 'bin', 'java')));
  for (const java of fromEnv) {
    if (existsSync(java)) return java;
  }
  for (const home of candidateHomes(cacheDir)) {
    if (javaMajor(home) === major && existsSync(join(home, 'bin', 'java'))) return join(home, 'bin', 'java');
  }
  return join(await installTemurin(major, cacheDir), 'bin', 'java');
}

function candidateHomes(cacheDir) {
  const roots = [
    join(cacheDir, 'jdks'),
    join(homedir(), '.gradle', 'jdks'),
    '/usr/lib/jvm',
    join(homedir(), '.sdkman', 'candidates', 'java'),
    '/Library/Java/JavaVirtualMachines',
  ];
  const homes = [];
  for (const root of roots) {
    if (!existsSync(root)) continue;
    for (const name of readdirSync(root)) {
      const dir = join(root, name);
      homes.push(dir, join(dir, 'Contents', 'Home'));
    }
  }
  return homes;
}

/** Reads the major version from a JDK's `release` file (`JAVA_VERSION="1.8.0_492"` → 8). */
export function javaMajor(home) {
  try {
    const release = readFileSync(join(home, 'release'), 'utf8');
    const version = /^JAVA_VERSION="([^"]+)"/m.exec(release)?.[1];
    if (!version) return null;
    const [first, second] = version.split(/[._+-]/).map(Number);
    return first === 1 ? second : first;
  } catch {
    return null;
  }
}

async function installTemurin(major, cacheDir) {
  const target = join(cacheDir, 'jdks', `temurin-${major}`);
  if (javaMajor(target) === major) return target;
  const os = platform() === 'darwin' ? 'mac' : platform();
  const arch = { x64: 'x64', arm64: 'aarch64' }[osArch()] ?? osArch();
  const query = `os=${os}&architecture=${arch}&image_type=jre&jvm_impl=hotspot&vendor=eclipse`;
  const response = await fetch(`${ADOPTIUM}/assets/latest/${major}/hotspot?${query}`);
  if (!response.ok) throw new Error(`Adoptium: no Temurin ${major} JRE for ${os}/${arch} (HTTP ${response.status})`);
  const [asset] = await response.json();
  const pkg = asset?.binary?.package;
  if (!pkg) throw new Error(`Adoptium: no Temurin ${major} JRE for ${os}/${arch}`);
  console.log(`Downloading Temurin ${asset.version.semver} (${os}/${arch}): no local Java ${major} found`);
  const archive = await download(pkg.link, join(cacheDir, 'downloads', pkg.name), { sha256: pkg.checksum });
  const staging = `${target}.tmp`;
  rmSync(staging, { recursive: true, force: true });
  mkdirSync(staging, { recursive: true });
  await run('tar', ['-xzf', archive, '-C', staging, '--strip-components', os === 'mac' ? '3' : '1']);
  rmSync(target, { recursive: true, force: true });
  renameSync(staging, target);
  return target;
}
