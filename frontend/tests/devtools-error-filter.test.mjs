import test from 'node:test';
import assert from 'node:assert/strict';
import { filterDevToolsStartTimeError } from '../lib/devtools-error-filter.ts';

const message = "Cannot read properties of undefined (reading 'startTime')";
const stack = `TypeError: ${message}\n    at et.reportAllChanges (<anonymous>:2:19429)\n    at <anonymous>:2:13070\n    at n.timeout (<anonymous>:2:5652)`;
function filtered({ text = message, trace = stack, filename = '' } = {}) {
  const error = new TypeError(text);
  error.stack = trace;
  let prevented = false;
  filterDevToolsStartTimeError({ error, filename, preventDefault() { prevented = true; } });
  return prevented;
}
// 판정은 스택 서명만 본다. `filename` 은 브라우저와 주입 방식에 따라 빈 문자열,
// `VM325`, `VM325:2`, 페이지 주소 중 무엇으로도 오므로 그 값에 기대면 정작 막아야
// 할 오류를 놓친다 — 실제로 놓쳤다.
test('known injected DevTools error is filtered, whatever the filename says', () => {
  assert.equal(filtered(), true);
  assert.equal(filtered({ filename: 'VM325' }), true);
  assert.equal(filtered({ filename: 'VM325:2' }), true);
  assert.equal(filtered({ filename: '<anonymous>' }), true);
  assert.equal(filtered({ filename: 'https://example.com/app.js' }), true);
});
test('application errors remain visible, even with the same message', () => {
  assert.equal(filtered({ trace: stack + '\n    at play (https://example.com/app.js:1:2)' }), false);
  assert.equal(filtered({ trace: 'TypeError\n    at play (<anonymous>:2:19429)' }), false);
  assert.equal(filtered({ text: 'Other error' }), false);
});
