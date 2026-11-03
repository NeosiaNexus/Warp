// Where the jars the harness builds end up. Their names carry the version Gradle builds, which is
// not version.txt's: outside a release it is the next snapshot (0.1.0-beta.6-SNAPSHOT after
// 0.1.0-beta.5, build.gradle.kts). So the harness asks Gradle for it, in the build's own invocation,
// rather than guessing it, and build/libs may keep the jars of older versions without confusing it.
import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

/** A jar the harness needs is missing or ambiguous: printed as is, without a stack trace. */
export class JarNotFoundError extends Error {}

/** The Gradle arguments that print a project's version, to append to a build's tasks. */
export function versionTasks(project) {
  return [`${project}:properties`, '--property', 'version'];
}

/** The version in the output of {@link versionTasks}, among whatever else the build printed. */
export function gradleVersion(stdout) {
  const match = /^version: (\S+)\s*$/m.exec(stdout);
  if (!match || match[1] === 'unspecified') {
    throw new JarNotFoundError(`Gradle did not print the version it builds; its output was:\n${stdout.trim()}`);
  }
  return match[1];
}

/** Whether `file` is a main jar of `base`: not sources, javadoc or the plain jar a shadow jar replaces. */
function isMainJar(file, base) {
  return file.startsWith(`${base}-`) && file.endsWith('.jar') && /^\d/.test(file.slice(base.length + 1))
    && !/-(sources|javadoc|plain)\.jar$/.test(file);
}

/**
 * The jar `<base>-<version>.jar` in `dir`. When it is missing, the error names the jars of `base`
 * the directory holds instead (those of older versions, typically) and the option that bypasses it.
 */
export function findJar(dir, base, version, option) {
  const path = join(dir, `${base}-${version}.jar`);
  if (existsSync(path)) return path;
  const jars = existsSync(dir) ? readdirSync(dir).filter((file) => isMainJar(file, base)).sort() : [];
  const held = jars.length ? `only ${jars.join(', ')}` : 'no jar of it';
  throw new JarNotFoundError(`no ${base}-${version}.jar in ${dir}, ${held}: build it, or pass ${option}`);
}

/** The fixed-name jar `file` in `dir`, which must exist. */
export function fixedJar(dir, file, option) {
  const path = join(dir, file);
  if (!existsSync(path)) throw new JarNotFoundError(`no ${file} in ${dir}: build it, or pass ${option}`);
  return path;
}
