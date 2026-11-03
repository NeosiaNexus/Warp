// Where the harness finds the jars it builds (src/jars.js): the version comes from Gradle, since a
// snapshot build is not named after version.txt, and a missing jar fails with what to do about it.
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, it } from 'node:test';

import { JarNotFoundError, findJar, fixedJar, gradleVersion, versionTasks } from '../src/jars.js';

/** A build/libs directory holding `files`. */
function libs(...files) {
  const dir = mkdtempSync(join(tmpdir(), 'warp-e2e-jars-'));
  for (const file of files) writeFileSync(join(dir, file), '');
  return dir;
}

describe('gradleVersion', () => {
  it('reads the version among the rest of what the build printed', () => {
    const out = 'w: some warning\n\n------------------------------------------------------------\n'
      + "Project ':proxy'\n------------------------------------------------------------\n\n"
      + 'version: 0.1.0-beta.6-SNAPSHOT\n';
    assert.equal(gradleVersion(out), '0.1.0-beta.6-SNAPSHOT');
  });

  it('reads a release version', () => {
    assert.equal(gradleVersion('version: 0.1.0-beta.5\r\n'), '0.1.0-beta.5');
  });

  it('fails clearly when the build printed no version', () => {
    assert.throws(() => gradleVersion('BUILD SUCCESSFUL\n'), (e) => e instanceof JarNotFoundError && /did not print the version/.test(e.message));
  });

  it('fails clearly when the project has no version', () => {
    assert.throws(() => gradleVersion('version: unspecified\n'), JarNotFoundError);
  });

  it('asks the given project for its version, appended to the build tasks', () => {
    assert.deepEqual(versionTasks(':proxy'), [':proxy:properties', '--property', 'version']);
  });
});

describe('findJar', () => {
  it('finds the snapshot jar that version.txt does not name', () => {
    const dir = libs('warp-0.1.0-beta.6-SNAPSHOT.jar');
    assert.equal(findJar(dir, 'warp', '0.1.0-beta.6-SNAPSHOT', '--jar'), join(dir, 'warp-0.1.0-beta.6-SNAPSHOT.jar'));
  });

  it('picks the jar of the version built over the stale jars of older versions', () => {
    const dir = libs('warp-0.1.0-beta.5.jar', 'warp-0.1.0-beta.6-SNAPSHOT.jar', 'warp-0.1.0-beta.6-SNAPSHOT-sources.jar');
    assert.equal(findJar(dir, 'warp', '0.1.0-beta.6-SNAPSHOT', '--jar'), join(dir, 'warp-0.1.0-beta.6-SNAPSHOT.jar'));
  });

  it('names the jars present and the bypassing option when the one built is missing', () => {
    const dir = libs('warp-0.1.0-beta.5.jar', 'warp-0.1.0-beta.5-javadoc.jar', 'warp-0.1.0-beta.5-plain.jar', 'proxy-0.1.0-beta.5.jar');
    assert.throws(
      () => findJar(dir, 'warp', '0.1.0-beta.6-SNAPSHOT', '--jar'),
      (e) => e instanceof JarNotFoundError
        && e.message === `no warp-0.1.0-beta.6-SNAPSHOT.jar in ${dir}, only warp-0.1.0-beta.5.jar: build it, or pass --jar`,
    );
  });

  it('does not count the jars of another base sharing the prefix', () => {
    const dir = libs('warp-via-0.1.0-beta.5.jar');
    assert.throws(() => findJar(dir, 'warp', '0.1.0-beta.6-SNAPSHOT', '--jar'), /, no jar of it: build it, or pass --jar$/);
  });

  it('fails clearly when the directory does not exist', () => {
    const dir = join(libs(), 'missing');
    assert.throws(
      () => findJar(dir, 'warp-via', '1.0.0', '--via-jar'),
      (e) => e instanceof JarNotFoundError && e.message === `no warp-via-1.0.0.jar in ${dir}, no jar of it: build it, or pass --via-jar`,
    );
  });
});

describe('fixedJar', () => {
  it('finds a jar named without a version', () => {
    const dir = libs('e2e-audience-probe.jar');
    assert.equal(fixedJar(dir, 'e2e-audience-probe.jar', '--plugin'), join(dir, 'e2e-audience-probe.jar'));
  });

  it('fails clearly when it is missing', () => {
    assert.throws(() => fixedJar(libs(), 'e2e-audience-probe.jar', '--plugin'), (e) => e instanceof JarNotFoundError && /or pass --plugin$/.test(e.message));
  });
});
