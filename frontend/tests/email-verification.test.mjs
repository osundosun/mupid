import test from 'node:test';
import assert from 'node:assert/strict';
import { parseEmailLink, emailVerificationService } from '../lib/email-verification.ts';
test('accepts both uppercase actions without treating the code as a number', () => {
  for (const action of ['SIGNUP', 'PW_RESET']) assert.deepEqual(parseEmailLink(new URLSearchParams({action, code:'0K5AOC'})), {action, code:'0K5AOC'});
});
test('rejects missing, duplicate, lowercase and malformed parameters', () => {
  for (const query of ['', 'action=SIGNUP', 'action=signup&code=x', 'action=OTHER&code=x', 'action=SIGNUP&code=', 'action=SIGNUP&code=x&code=y', 'action=SIGNUP&action=PW_RESET&code=x', 'action=SIGNUP&code=%20']) assert.equal(parseEmailLink(new URLSearchParams(query)), null);
});
test('unconnected service never reports success for either action', async () => {
  for (const action of ['SIGNUP', 'PW_RESET']) assert.deepEqual(await emailVerificationService.verify({action,code:'0K5AOC'}), {status:'unavailable'});
});
