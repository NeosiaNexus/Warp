// The backend plugin of the `bungeecord` scenario: plugins/bungee-probe, compiled for Java 8
// against the Bukkit stubs in plugins/bukkit-stubs, so that one jar loads on every Paper of the
// matrix. Only the plugin's own classes and its plugin.yml go into the jar: the server provides
// Bukkit.
import { existsSync, mkdirSync, readdirSync, rmSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { run } from './proc.js';
import { E2E_DIR } from './versions.js';

const PLUGIN_DIR = join(E2E_DIR, 'plugins', 'bungee-probe');
const STUBS_DIR = join(E2E_DIR, 'plugins', 'bukkit-stubs');

/**
 * Finds a JDK tool (javac, jar) next to the JDK that runs Gradle: JAVA_HOME, else the PATH.
 * @param {string} tool
 */
export function jdkTool(tool, env = process.env) {
  const home = env.JAVA_HOME;
  if (home && existsSync(join(home, 'bin', tool))) return join(home, 'bin', tool);
  return tool;
}

/** Every .java file under `dir`. */
function javaSources(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return javaSources(path);
    return name.endsWith('.java') ? [path] : [];
  });
}

/**
 * Builds the probe plugin's jar into `outDir` and returns its path.
 * @param {string} outDir
 */
export async function buildProbePlugin(outDir) {
  const work = join(outDir, 'bungee-probe');
  const stubs = join(work, 'stubs');
  const classes = join(work, 'classes');
  rmSync(work, { recursive: true, force: true });
  mkdirSync(stubs, { recursive: true });
  mkdirSync(classes, { recursive: true });
  const javac = jdkTool('javac');
  const quiet = ['--release', '8', '-nowarn', '-Xlint:-options', '-encoding', 'UTF-8'];
  await run(javac, [...quiet, '-d', stubs, ...javaSources(STUBS_DIR)]);
  await run(javac, [...quiet, '-cp', stubs, '-d', classes, ...javaSources(join(PLUGIN_DIR, 'src'))]);
  const jar = join(outDir, 'BungeeProbe.jar');
  rmSync(jar, { force: true });
  await run(jdkTool('jar'), ['cf', jar, '-C', classes, '.', '-C', PLUGIN_DIR, 'plugin.yml']);
  return jar;
}
