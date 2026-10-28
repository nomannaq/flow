// Include license texts for every production dependency, including transitive dependencies.
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
const lock = JSON.parse(readFileSync('package-lock.json', 'utf8'));
const notices = [
  'Flow React chat — third-party notices\n\nUI implementation by Flow, inspired by Agent UI (https://www.agentui.pro/). No Agent UI source is redistributed.',
  readFileSync('../src/main/resources/icons/providers/NOTICE.txt', 'utf8'),
  readFileSync('../src/main/resources/icons/flow/NOTICE.txt', 'utf8'),
];
for (const [path, dependency] of Object.entries(lock.packages)) {
  if (!path || dependency.dev) continue;
  const manifest = JSON.parse(readFileSync(join(path, 'package.json'), 'utf8'));
  const files = readdirSync(path).filter((name) =>
    /^(licen[cs]e|copying|notice)(\.|$)/i.test(name),
  );
  if (!files.length) throw new Error(`Missing license text: ${manifest.name}`);
  notices.push(
    `${manifest.name}@${manifest.version}\n${manifest.license || dependency.license || ''}\n${files.map((name) => readFileSync(join(path, name), 'utf8')).join('\n')}`,
  );
}
writeFileSync(
  '../build/react-chat/THIRD_PARTY_NOTICES.txt',
  notices.join('\n\n----------------------------\n\n'),
);
