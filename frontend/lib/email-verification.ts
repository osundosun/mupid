export type EmailAction = 'SIGNUP' | 'PW_RESET';
export type EmailLink = { action: EmailAction; code: string };

/** Query validation only. This never establishes identity or validates a token. */
export function parseEmailLink(params: URLSearchParams): EmailLink | null {
  if (params.getAll('action').length !== 1 || params.getAll('code').length !== 1) return null;
  const action = params.get('action');
  const code = params.get('code');
  if (action !== 'SIGNUP' && action !== 'PW_RESET') return null;
  // The backend has not specified length/encoding; don't assume a six-digit number.
  if (!code || code.length > 2048 || /\s|[\u0000-\u001f\u007f]/u.test(code)) return null;
  return { action, code };
}
export type EmailVerificationResult =
  | { status: 'unavailable' }
  | { status: 'invalid' }
  | { status: 'expired' };

// Add verified grants and reset-password mutation only after the backend contract is agreed.
// Do not log, persist, echo, or send codes to a guessed endpoint.
export interface EmailVerificationService {
  verify(link: EmailLink): Promise<EmailVerificationResult>;
}
export const emailVerificationService: EmailVerificationService = {
  async verify() { return { status: 'unavailable' }; },
};
