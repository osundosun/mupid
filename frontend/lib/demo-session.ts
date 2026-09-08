/** UI-only demo state. This is not an authentication token or authorization check. */
export type DemoSession = { kind: 'demo'; nickname: string };

export function createDemoSession(nickname: string): DemoSession | null {
  const name = nickname.trim().replace(/\s+/g, ' ').slice(0, 20);
  return name ? { kind: 'demo', nickname: name } : null;
}
