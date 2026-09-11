// A host that GRANTS one port, so a program can be asked what may cross it.
//
// `host/flint.mjs` grants nothing, which is the right default and makes it
// useless for this: every `open` is refused before the interesting part runs.
// This grants "thing" with a handler that does nothing, so the port is real and
// what the runtime allows through it is the only thing under test.
import { load, instantiate } from '../host/flint.mjs';

const { module } = await load(process.argv[2]);
const i = instantiate(module);
// A SYSTEM PORT first: `open` is a request ON one (`DECISIONS.md#ports-are-the-hosts`), and a
// sandbox given none cannot ask for anything at all.
i.install(1, { label: 'system', system: true });
i.capabilities({ thing: { open() {}, message() {} } });
const r = i.run('crossing/main', []);
process.stdout.write(r.out);
process.exit(r.code === 0 ? 0 : 1);
