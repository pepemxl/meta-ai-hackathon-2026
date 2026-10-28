'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

// Files of a clip on disk (docs/12-videos-largos-chunks.md#archivos).
const INDEX = 'chunks.yaml';
const FILE_PATTERN = /^(video_part_\d{3}\.(mp4|yaml)|chunks\.yaml|reference_\d\.jpg)$/;

const pad = (n, width) => String(n).padStart(width, '0');
const mp4Name = (n) => `video_part_${pad(n, 3)}.mp4`;
const yamlName = (n) => `video_part_${pad(n, 3)}.yaml`;
const partialName = (n) => `${mp4Name(n)}.partial`;
const referenceName = (n) => `reference_${n}.jpg`;
const clipDirName = (clipId) => `clip_${pad(clipId, 6)}`;
const clipDir = (storageDir, clipId) => path.join(storageDir, clipDirName(clipId));

function sha256File(file) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    fs.createReadStream(file)
      .on('error', reject)
      .on('data', (d) => hash.update(d))
      .on('end', () => resolve(hash.digest('hex')));
  });
}

/** The top-level `sha256:` of a chunk YAML, or null. */
function declaredSha(yamlText) {
  const m = /^sha256:\s*"?([0-9a-fA-F]{64})"?\s*$/m.exec(String(yamlText));
  return m ? m[1].toLowerCase() : null;
}

/** Bytes already received for a chunk (its .partial), 0 when there is none. */
function partialSize(storageDir, clipId, n) {
  try {
    return fs.statSync(path.join(clipDir(storageDir, clipId), partialName(n))).size;
  } catch (e) {
    return 0;
  }
}

/** Partial chunks of a clip: [{number, received_bytes}], sorted. */
function partials(storageDir, clipId) {
  let names = [];
  try {
    names = fs.readdirSync(clipDir(storageDir, clipId));
  } catch (e) {
    return [];
  }
  return names
    .map((name) => /^video_part_(\d{3})\.mp4\.partial$/.exec(name))
    .filter(Boolean)
    .map((m) => Number(m[1]))
    .sort((a, b) => a - b)
    .map((number) => ({ number, received_bytes: partialSize(storageDir, clipId, number) }));
}

/** The reference pictures of a clip on disk: [names], sorted. */
function references(storageDir, clipId) {
  try {
    return fs.readdirSync(clipDir(storageDir, clipId)).filter((name) => /^reference_\d\.jpg$/.test(name)).sort();
  } catch (e) {
    return [];
  }
}

/** Drop every chunk, YAML, partial and index of a clip (its profile changed). */
function wipeClip(storageDir, clipId) {
  fs.rmSync(clipDir(storageDir, clipId), { recursive: true, force: true });
}

/** Move a temp file into place; falls back to copy across devices. */
function moveInto(src, dest) {
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  try {
    fs.renameSync(src, dest);
  } catch (e) {
    if (e.code !== 'EXDEV') throw e;
    fs.copyFileSync(src, dest);
    fs.unlinkSync(src);
  }
}

module.exports = {
  INDEX, FILE_PATTERN, mp4Name, yamlName, partialName, referenceName, clipDir, sha256File, declaredSha,
  partialSize, partials, references, wipeClip, moveInto,
};
