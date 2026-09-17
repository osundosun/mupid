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
test('known injected DevTools error is filtered', () => {
  assert.equal(filtered(), true);
  assert.equal(filtered({ filename: 'VM325' }), true);
});
test('application errors remain visible, even with the same message', () => {
  assert.equal(filtered({ filename: 'https://example.com/app.js' }), false);
  assert.equal(filtered({ trace: stack + '\n    at play (https://example.com/app.js:1:2)' }), false);
  assert.equal(filtered({ trace: 'TypeError\n    at play (<anonymous>:2:19429)' }), false);
  assert.equal(filtered({ text: 'Other error' }), false);
});
