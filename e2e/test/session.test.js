// Player identities (src/session.js): the UUIDs the forwarding scenario expects backends to see.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { offlineUuid, sessionUuid, startSessionServer } from '../src/session.js';

describe('identities', () => {
  it('derive offline UUIDs as the game does', () => {
    // UUID.nameUUIDFromBytes("OfflinePlayer:Notch".getBytes(UTF_8)), what every offline server uses.
    assert.equal(offlineUuid('Notch'), 'b50ad385-829d-3141-a216-7e7d7539ba7f');
  });

  it('give each name a stable session UUID, unlike its offline one', () => {
    assert.equal(sessionUuid('e2e_forwarded'), sessionUuid('e2e_forwarded'));
    assert.notEqual(sessionUuid('e2e_forwarded'), sessionUuid('e2e_other'));
    assert.notEqual(sessionUuid('e2e_forwarded'), offlineUuid('e2e_forwarded'));
  });

  it('are vouched for by the mock session server, the way Mojang writes them', async () => {
    const session = await startSessionServer(0);
    try {
      const response = await fetch(`${session.url}?username=e2e_forwarded&serverId=abc`);

      assert.deepEqual(await response.json(), { id: sessionUuid('e2e_forwarded').replaceAll('-', ''), name: 'e2e_forwarded', properties: [] });
      assert.deepEqual(session.requests, [{ path: '/session/minecraft/hasJoined', name: 'e2e_forwarded' }]);
    } finally {
      await session.close();
    }
  });
});
