// The compose.uiTest smoke flows (src/commonTest/.../uitest/) compose the full App() and walk a
// multi-screen flow — well past karma-mocha's 2s default per-test timeout. Unit tests are
// unaffected (they finish in ms); this only raises the ceiling for the wasm browser run.
config.set({
    client: {
        mocha: {
            timeout: 60000
        }
    }
});
