import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';
const context = { exports: {}, DOMException };
vm.runInNewContext(ts.transpileModule(readFileSync(new URL('../lib/phrase-search.ts', import.meta.url), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText, context);
const expand = context.exports.expandPhraseSearch;
const empty = { items: [], total_elements: 0, total_pages: 0, page: 0, size: 8 };
test('phrase fallback preserves all filters, visits all pages and excludes unrelated songs', async () => {
  const calls = [];
  const filters = { vocal: 'male', mood: 'bright', genre: 'pop' };
  const result = await expand(empty, 'Tenderness in', filters, new AbortController().signal, async options => {
    calls.push(options);
    return { ...empty, total_pages: 2, items: [{ music_id: options.page + 1, title: options.page ? 'Tenderness in the Shadow' : 'Tenderness elsewhere', artist: { name: 'Wrenfield' }, album: { title: 'Album' } }] };
  });
  assert.equal(calls.length, 2);
  for (const call of calls) for (const [key, value] of Object.entries(filters)) assert.equal(call[key], value);
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].music_id, 2);
  assert.equal(result.total_elements, 1);
});
test('normal paginated results remain intact and cancelled fallback makes no request', async () => {
  const page = { ...empty, total_elements: 20, total_pages: 3 };
  const unexpected = () => { throw Error('unexpected request'); };
  assert.equal(await expand(page, 'Tenderness in', {}, new AbortController().signal, unexpected), page);
  const controller = new AbortController(); controller.abort();
  await assert.rejects(expand(empty, 'Tenderness in', {}, controller.signal, unexpected), { name: 'AbortError' });
});
