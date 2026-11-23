"""Exercise initial-connection deadlines, source guards and renderer rollback."""
import importlib.machinery
import importlib.util
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[1]
SCRIPT = REPO / "core/bin/omarchy-repair-hermes-onboarding"
NODE = shutil.which("node")


def load_helper():
    if not SCRIPT.exists():
        return None
    loader = importlib.machinery.SourceFileLoader("hermes_onboarding", str(SCRIPT))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    with patch.object(sys, "dont_write_bytecode", True):
        loader.exec_module(module)
    return module


helper = load_helper()


class HermesOnboardingTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(helper, "Version-checked Hermes onboarding repair is missing")

    @unittest.skipUnless(NODE, "Node is needed for virtual-clock renderer checks")
    def test_choice_wait_and_bootstrap_pause_only_the_connection_deadline(self):
        harness = r'''
const vm=require('node:vm'), assert=require('node:assert/strict');
const repair=JSON.parse(process.argv[1]);
class Clock {
  constructor(){this.now=0;this.next=1;this.tasks=new Map();}
  set(fn,delay,interval=0){const id=this.next++;this.tasks.set(id,{fn,at:this.now+delay,interval});return id;}
  clear(id){this.tasks.delete(id);}
  async flush(){for(let n=0;n<8;n++)await Promise.resolve();}
  async advance(ms){const end=this.now+ms;await this.flush();while(true){
    const next=[...this.tasks].filter(([,t])=>t.at<=end).sort((a,b)=>a[1].at-b[1].at)[0];
    if(!next)break;const [id,t]=next;this.now=t.at;
    if(t.interval)t.at+=t.interval;else this.tasks.delete(id);
    t.fn();await this.flush();
  }this.now=end;await this.flush();}
}
// Exact timeout wrapper from hermes-CVux_Zfz.js in the fingerprinted vendor build.
const vendorWait='var M=class extends Error{constructor(e){super(e),this.name=`TimeoutError`}};function N(e,t,n,r){return new Promise((i,a)=>{let o=setTimeout(()=>{let e=new M(n);try{r?.(e)}catch(e){a(e);return}a(e)},t);Promise.resolve(e).then(e=>{clearTimeout(o),i(e)},e=>{clearTimeout(o),a(e)})})};function __omarchyWaitInitialConnection(_bridge,connection,ms){return N(connection,ms,"Timed out connecting to Hermes backend")}';
function setup(state,code=repair){const clock=new Clock();let resolve,reject;
  const connection=new Promise((r,j)=>{resolve=r;reject=j});
  const sandbox={setTimeout:(f,t)=>clock.set(f,t),clearTimeout:i=>clock.clear(i),
    setInterval:(f,t)=>clock.set(f,t,t),clearInterval:i=>clock.clear(i),Promise,Error};
  vm.runInNewContext(code+';globalThis.wait=__omarchyWaitInitialConnection;',sandbox);
  let current=state, outcome='pending', error;
  const bridge={getBootstrapState:()=>Promise.resolve(current)};
  const result=sandbox.wait(bridge,connection,45000).then(v=>{outcome=v},e=>{outcome='rejected';error=e});
  return {clock,bridge,result,resolve,reject,setState:s=>{current=s},get outcome(){return outcome},get error(){return error}};
}
(async()=>{
  const vendor=setup({setupChoice:{platform:'linux'},active:false},vendorWait);
  await vendor.clock.advance(45000);assert.equal(vendor.outcome,'rejected');
  assert.match(vendor.error.message,/Timed out connecting/);
  const waiting=setup({setupChoice:{platform:'linux'},active:false});
  await waiting.clock.advance(180000);assert.equal(waiting.outcome,'pending');
  waiting.setState({setupChoice:null,active:true});
  await waiting.clock.advance(180000);assert.equal(waiting.outcome,'pending');
  waiting.setState({setupChoice:null,active:false});
  await waiting.clock.advance(500);await waiting.clock.advance(44999);
  assert.equal(waiting.outcome,'pending');await waiting.clock.advance(1);
  assert.equal(waiting.outcome,'rejected');assert.match(waiting.error.message,/Timed out connecting/);
  assert.equal(waiting.clock.tasks.size,0);
  const failed=setup({setupChoice:{platform:'linux'}});await failed.clock.advance(90000);
  const backendError=new Error('backend exited with a real dependency error');failed.reject(backendError);
  await failed.clock.flush();assert.equal(failed.error,backendError);assert.equal(failed.clock.tasks.size,0);
  const ready=setup({setupChoice:{platform:'linux'}});await ready.clock.advance(90000);
  const descriptor={mode:'local',port:1234};ready.resolve(descriptor);await ready.clock.flush();
  assert.equal(ready.outcome,descriptor);assert.equal(ready.clock.tasks.size,0);
  const unknown=setup(null);await unknown.clock.advance(45000);assert.equal(unknown.outcome,'rejected');
  const reportedFailure=setup({setupChoice:{platform:'linux'},active:true});
  await reportedFailure.clock.advance(60000);
  reportedFailure.setState({setupChoice:{platform:'linux'},active:true,error:'installer failed'});
  await reportedFailure.clock.advance(500+45000);assert.equal(reportedFailure.outcome,'rejected');
  const lost=setup({setupChoice:{platform:'linux'}});await lost.clock.advance(60000);
  lost.bridge.getBootstrapState=()=>new Promise(()=>{});await lost.clock.advance(500+2000+45000);
  assert.equal(lost.outcome,'rejected');assert.equal(lost.clock.tasks.size,0);
  console.log('choice/bootstrap pause, real failure, bounded reconnect, IPC loss and cleanup passed');
})().catch(e=>{console.error(e.stack);process.exitCode=1});
'''
        result = subprocess.run([NODE, "-e", harness, json.dumps(helper.WAIT_JS)],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)

    def fixture(self, base):
        root = base / "Hermes"
        assets = root / "resources/app.asar.unpacked/dist/assets"
        assets.mkdir(parents=True)
        (root / "version").write_text(helper.PROFILE["electron"])
        (root / "resources/install-stamp.json").write_text(json.dumps({"commit": helper.PROFILE["commit"]}))
        renderer = assets / helper.PROFILE["renderer"]
        renderer.write_text("const begin=()=>" + helper.ORIGINAL_CALL + ";\n")
        main = assets.parent / "electron-main.mjs"
        main.write_text("official main fixture")
        shared = assets / helper.PROFILE["shared"]
        shared.write_text("official timeout fixture")
        profile = dict(helper.PROFILE, renderer_sha256=hashlib.sha256(renderer.read_bytes()).hexdigest(),
                       main_sha256=hashlib.sha256(main.read_bytes()).hexdigest(),
                       shared_sha256=hashlib.sha256(shared.read_bytes()).hexdigest())
        return root, renderer, profile

    def test_known_source_patch_is_idempotent_and_has_verified_rollback(self):
        with tempfile.TemporaryDirectory() as temp:
            root, renderer, profile = self.fixture(Path(temp))
            original = renderer.read_bytes()
            with patch.object(helper, "PROFILE", profile):
                helper.apply(root)
                first = renderer.read_bytes()
                self.assertIn(b"__omarchyWaitInitialConnection", first)
                helper.apply(root)
                self.assertEqual(renderer.read_bytes(), first)
                helper.check(root)
                helper.rollback(root)
                self.assertEqual(renderer.read_bytes(), original)

    def test_modified_vendor_file_is_rejected_before_patch(self):
        with tempfile.TemporaryDirectory() as temp:
            root, renderer, profile = self.fixture(Path(temp))
            renderer.write_text(renderer.read_text() + "// unrecognized vendor modification")
            unrecognized = renderer.read_bytes()
            with patch.object(helper, "PROFILE", profile), self.assertRaisesRegex(RuntimeError, "fingerprint"):
                helper.apply(root)
            self.assertEqual(renderer.read_bytes(), unrecognized)
            self.assertFalse((root / ".omarchy-onboarding").exists())

    def test_changed_upstream_version_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root, renderer, profile = self.fixture(Path(temp))
            original = renderer.read_bytes()
            (root / "version").write_text("41.0.0")
            with patch.object(helper, "PROFILE", profile), self.assertRaisesRegex(RuntimeError, "version"):
                helper.apply(root)
            self.assertEqual(renderer.read_bytes(), original)

    def test_changed_main_source_is_rejected_without_touching_renderer(self):
        with tempfile.TemporaryDirectory() as temp:
            root, renderer, profile = self.fixture(Path(temp))
            original = renderer.read_bytes()
            (renderer.parent.parent / "electron-main.mjs").write_text("changed first-run gate")
            with patch.object(helper, "PROFILE", profile), self.assertRaisesRegex(RuntimeError, "fingerprint"):
                helper.apply(root)
            self.assertEqual(renderer.read_bytes(), original)
            self.assertFalse((root / ".omarchy-onboarding").exists())

    def test_rollback_rejects_later_unmanaged_renderer_edit(self):
        with tempfile.TemporaryDirectory() as temp:
            root, renderer, profile = self.fixture(Path(temp))
            with patch.object(helper, "PROFILE", profile):
                helper.apply(root)
                renderer.write_bytes(renderer.read_bytes() + b"// later third-party edit\n")
                unrecognized = renderer.read_bytes()
                with self.assertRaisesRegex(RuntimeError, "fingerprint"):
                    helper.rollback(root)
                self.assertEqual(renderer.read_bytes(), unrecognized)

    def test_bootstrap_cache_is_seeded_from_verified_package_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            root, renderer, profile = self.fixture(base)
            script = base / "packaged-install.sh"
            script.write_bytes(b"reviewed pinned bootstrap fixture")
            profile["bootstrap_installer_sha256"] = helper.sha(script.read_bytes())
            home = base / "hermes-home"
            original_renderer = renderer.read_bytes()
            with patch.object(helper, "PROFILE", profile):
                helper.seed_bootstrap_cache(root, home, script)
                target = home / "bootstrap-cache" / ("install-" + profile["commit"] + ".sh")
                self.assertEqual(target.read_bytes(), script.read_bytes())
                self.assertEqual(target.stat().st_mode & 0o777, 0o600)
                timestamp = target.stat().st_mtime_ns
                helper.seed_bootstrap_cache(root, home, script)
                self.assertEqual(target.stat().st_mtime_ns, timestamp)
                self.assertFalse(list(target.parent.glob(".omarchy-seed-*")))
            self.assertEqual(renderer.read_bytes(), original_renderer)

    def test_bootstrap_cache_preserves_an_existing_different_file(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            root, _, profile = self.fixture(base)
            script = base / "packaged-install.sh"
            script.write_bytes(b"reviewed pinned bootstrap fixture")
            profile["bootstrap_installer_sha256"] = helper.sha(script.read_bytes())
            home = base / "hermes-home"
            cache = home / "bootstrap-cache"
            cache.mkdir(parents=True)
            target = cache / ("install-" + profile["commit"] + ".sh")
            target.write_bytes(b"existing cache must not be replaced")
            with patch.object(helper, "PROFILE", profile), self.assertRaisesRegex(RuntimeError, "preserved"):
                helper.seed_bootstrap_cache(root, home, script)
            self.assertEqual(target.read_bytes(), b"existing cache must not be replaced")

    def test_unknown_bootstrap_script_or_stamp_never_creates_cache(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            root, _, profile = self.fixture(base)
            script = base / "packaged-install.sh"
            script.write_bytes(b"unknown bootstrap")
            home = base / "hermes-home"
            with patch.object(helper, "PROFILE", profile), self.assertRaisesRegex(RuntimeError, "fingerprint"):
                helper.seed_bootstrap_cache(root, home, script)
            self.assertFalse(home.exists())
            profile["bootstrap_installer_sha256"] = helper.sha(script.read_bytes())
            (root / "resources/install-stamp.json").write_text('{"commit":"different-source"}')
            with patch.object(helper, "PROFILE", profile), self.assertRaisesRegex(RuntimeError, "commit"):
                helper.seed_bootstrap_cache(root, home, script)
            self.assertFalse(home.exists())

    def test_bootstrap_cache_created_concurrently_is_not_replaced(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            root, _, profile = self.fixture(base)
            script = base / "packaged-install.sh"
            script.write_bytes(b"reviewed pinned bootstrap fixture")
            profile["bootstrap_installer_sha256"] = helper.sha(script.read_bytes())
            home = base / "hermes-home"
            target = home / "bootstrap-cache" / ("install-" + profile["commit"] + ".sh")
            original_link = helper.os.link
            def competing_publish(source, destination):
                destination.write_bytes(b"concurrently published different script")
                original_link(source, destination)
            with patch.object(helper, "PROFILE", profile), patch.object(helper.os, "link", competing_publish):
                with self.assertRaisesRegex(RuntimeError, "concurrently"):
                    helper.seed_bootstrap_cache(root, home, script)
            self.assertEqual(target.read_bytes(), b"concurrently published different script")
            self.assertFalse(list(target.parent.glob(".omarchy-seed-*")))


if __name__ == "__main__":
    unittest.main()
