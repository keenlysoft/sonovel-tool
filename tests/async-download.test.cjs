const assert = require('node:assert/strict');
const test = require('node:test');
const { selectDownloadedBook } = require('../web/async-download.js');

const unknownAuthor = { bookName: '凡人修仙传', author: '' };
const knownAuthor = { bookName: '凡人修仙传', author: '忘语' };
const file = (name, size = 100, timestamp = 20) => ({ name, size, timestamp });

test('missing search author resolves the author supplied by the book parser', () => {
  const downloaded = file('凡人修仙传(忘语).txt');
  assert.equal(selectDownloadedBook(unknownAuthor, [], [downloaded]), downloaded);
});

test('missing author also accepts an exact newly created bare title', () => {
  const downloaded = file('凡人修仙传.txt');
  assert.equal(selectDownloadedBook(unknownAuthor, [], [downloaded]), downloaded);
});

test('only a new or changed matching file is eligible', () => {
  const oldBook = file('凡人修仙传(忘语).txt');
  const newBook = file('凡人修仙传(另一作者).txt', 200, 30);
  assert.equal(selectDownloadedBook(unknownAuthor, [oldBook], [oldBook, newBook]), newBook);
  assert.equal(selectDownloadedBook(unknownAuthor, [oldBook], [oldBook]), null);
});

test('size or timestamp changes can confirm an overwritten matching file', () => {
  const before = file('凡人修仙传(忘语).txt');
  const changedSize = file(before.name, 200, before.timestamp);
  const changedTime = file(before.name, before.size, 30);
  assert.equal(selectDownloadedBook(unknownAuthor, [before], [changedSize]), changedSize);
  assert.equal(selectDownloadedBook(unknownAuthor, [before], [changedTime]), changedTime);
});

test('unrelated prefixes, sequels, extensions and empty authors are rejected', () => {
  for (const name of [
    '前传凡人修仙传(忘语).txt',
    '凡人修仙传续集(忘语).txt',
    '凡人修仙传(忘语).txt.bak',
    '凡人修仙传(忘语)补充.txt',
    '凡人修仙传().txt',
    '凡人修仙传( ).txt'
  ]) {
    assert.equal(selectDownloadedBook(unknownAuthor, [], [file(name)]), null, name);
  }
});

test('ambiguous newly written authors are rejected instead of guessing the latest', () => {
  const candidates = [file('凡人修仙传(忘语).txt'), file('凡人修仙传(另一作者).txt', 300, 99)];
  assert.throws(() => selectDownloadedBook(unknownAuthor, [], candidates), /多个/);
  assert.throws(() => selectDownloadedBook(unknownAuthor, [], [
    file('凡人修仙传.txt'), candidates[0]
  ]), /多个/);
});

test('known authors retain exact filename identity', () => {
  for (const name of ['凡人修仙传(另一作者).txt', '凡人修仙传.txt', '凡人修仙传(忘语续).txt']) {
    assert.equal(selectDownloadedBook(knownAuthor, [], [file(name)]), null, name);
  }
  const downloaded = file('凡人修仙传(忘语).txt');
  assert.equal(selectDownloadedBook(knownAuthor, [], [downloaded]), downloaded);
  assert.equal(selectDownloadedBook(knownAuthor, [downloaded], [downloaded]), null);
});

test('whitespace-only authors and the filename field alias are supported', () => {
  const downloaded = { filename: '凡人修仙传(忘语).txt', size: 100, timestamp: 20 };
  assert.equal(selectDownloadedBook({ ...unknownAuthor, author: '  ' }, [], [downloaded]), downloaded);
});

test('an unchanged exact bare-title file cannot mask a changed author-suffixed result', () => {
  const oldBook = file('凡人修仙传.txt');
  const downloaded = file('凡人修仙传(忘语).txt');
  assert.equal(selectDownloadedBook(unknownAuthor, [oldBook], [oldBook, downloaded]), downloaded);
});
