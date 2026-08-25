using Workerd = import "/workerd/workerd.capnp";
const config :Workerd.Config = (
  services = [ (name = "main", worker = .flintWorker) ],
  sockets = [ (name = "http", address = "*:8791", http = (), service = "main") ],
);
const flintWorker :Workerd.Worker = (
  modules = [
    (name = "worker", esModule = embed "worker.js"),
    (name = "xrt-loader.wasm", wasm = embed "xrt-loader.wasm"),
    (name = "xrt.image", data = embed "xrt.image"),
    (name = "xrt0.image", data = embed "xrt0.image"),
  ],
  compatibilityDate = "2024-10-01",
);
