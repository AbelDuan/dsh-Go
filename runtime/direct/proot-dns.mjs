// PRoot launches import this through NODE_OPTIONS. There Node resolves names
// through glibc, which sends A and AAAA on one socket: a network that refuses
// AAAA fails both (#152). Use Direct's per-family resolver instead. If it
// cannot start (no usable resolv.conf), keep the native lookup: this must never
// stop a Node process from running.
// Node 24 dials both address families at once (happy eyeballs) and gives each candidate only
// 250 ms by default. Through a Wi-Fi route whose RTT to the endpoint is above that, every
// candidate is dropped and connect fails with ETIMEDOUT (~520 ms), which the engine then
// retries until it gives up. 1500 ms was 15/15 on the network that reproduced it (#152).
import net from 'node:net';

try {
  net.setDefaultAutoSelectFamilyAttemptTimeout(1500);
} catch {}

import { installLookup } from './dns.mjs';

try {
  installLookup({ resolvConf: '/etc/resolv.conf', hostsFile: '/etc/hosts' });
} catch {
  console.error('[dsh-app] proot DNS initialization failed; native lookup retained');
}
