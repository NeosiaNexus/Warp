// Resource packs the backends offer (`resource-pack` in server.properties): a small, valid pack per
// backend, served over HTTP by the harness. A bot checks that the offer it gets through Warp is the
// one its backend makes, then downloads the pack and checks its SHA-1, as a client does.
import { createHash, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { crc32 } from 'node:zlib';

/** A resource pack whose `pack.mcmeta` carries `description`, and the SHA-1 servers announce. */
export function resourcePack(description) {
  const mcmeta = `${JSON.stringify({ pack: { pack_format: 1, description } })}\n`;
  const zip = storedZip({ 'pack.mcmeta': Buffer.from(mcmeta) });
  return { zip, sha1: sha1(zip) };
}

export const sha1 = (bytes) => createHash('sha1').update(bytes).digest('hex');

/** 1980-01-01 in MS-DOS format (years since 1980, month, day): the earliest date a zip can hold. */
const DOS_DATE = (1 << 5) | 1;

/**
 * Serves a pack per name at `http://127.0.0.1:<port>/<name>.zip`, on a port the system picks.
 * @param {string[]} names one pack each ("lobby", "survival")
 * @returns {Promise<{packs: Record<string, {url: string, sha1: string, id: string}>, close: () => Promise<void>}>}
 *   `id`: the UUID servers send with the pack from 1.20.3
 */
export function startPackServer(names) {
  const zips = new Map(names.map((name) => [`/${name}.zip`, resourcePack(`warp-e2e ${name}`)]));
  const server = createServer((req, res) => {
    const pack = req.method === 'GET' ? zips.get(req.url) : undefined;
    if (!pack) return void res.writeHead(404).end();
    res.writeHead(200, { 'content-type': 'application/zip', 'content-length': pack.zip.length }).end(pack.zip);
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const base = `http://127.0.0.1:${server.address().port}`;
      const pack = (name) => ({ url: `${base}/${name}.zip`, sha1: zips.get(`/${name}.zip`).sha1, id: randomUUID() });
      resolve({
        packs: Object.fromEntries(names.map((name) => [name, pack(name)])),
        close: () => new Promise((done) => server.close(done)),
      });
    });
  });
}

/**
 * A zip archive whose entries are stored uncompressed, dated 1980-01-01: the same files always give
 * the same bytes, so the same SHA-1.
 * @param {Record<string, Buffer>} files entry name (ASCII) → content
 */
export function storedZip(files) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, data] of Object.entries(files)) {
    const fileName = Buffer.from(name, 'ascii');
    // Fields shared by the local and the central header: version needed (1.0), flags, method
    // (stored), time, date, CRC-32, compressed and uncompressed sizes, name length, extra length.
    const common = Buffer.alloc(26);
    common.writeUInt16LE(10, 0);
    common.writeUInt16LE(DOS_DATE, 8);
    common.writeUInt32LE(crc32(data), 10);
    common.writeUInt32LE(data.length, 14);
    common.writeUInt32LE(data.length, 18);
    common.writeUInt16LE(fileName.length, 22);
    locals.push(uint32(0x04034b50), common, fileName, data);
    // Central header: signature, version made by, the shared fields, comment length, disk number,
    // internal and external attributes, then where the local header starts.
    const tail = Buffer.alloc(14);
    tail.writeUInt32LE(offset, 10);
    centrals.push(uint32(0x02014b50), uint16(10), common, tail, fileName);
    offset += 4 + common.length + fileName.length + data.length;
  }
  const central = Buffer.concat(centrals);
  const count = Object.keys(files).length;
  // End of central directory: disk numbers, entry counts, directory size and offset, comment length.
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(count, 8);
  end.writeUInt16LE(count, 10);
  end.writeUInt32LE(central.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, central, end]);
}

function uint16(value) {
  const buffer = Buffer.alloc(2);
  buffer.writeUInt16LE(value);
  return buffer;
}

function uint32(value) {
  const buffer = Buffer.alloc(4);
  buffer.writeUInt32LE(value);
  return buffer;
}
