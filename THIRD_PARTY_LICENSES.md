# Third-Party Licenses

This project includes the following third-party binaries in `androidApp/src/main/jniLibs/`:

## PRoot (Termux fork)

- **Files:** `libproot.so`, `libproot-loader.so`, `libproot-loader32.so`
- **Source:** https://github.com/termux/proot
- **License:** GPL-2.0
- **Copyright:** Copyright (C) PRoot developers

PRoot is a user-space implementation of chroot, mount --bind, and binfmt_misc. It is used to run an Alpine Linux environment inside the Android app without requiring root access. PRoot is executed as a separate process and is not linked into the application code.

The full GPL-2.0 license text is available at: https://www.gnu.org/licenses/old-licenses/gpl-2.0.html

## talloc

- **Files:** `libtalloc.so`
- **Source:** https://talloc.samba.org/
- **License:** LGPL-3.0
- **Copyright:** Copyright (C) Andrew Tridgell, Stefan Metzmacher, and contributors

talloc is a hierarchical memory allocator used as a dependency of PRoot. It is dynamically linked.

The full LGPL-3.0 license text is available at: https://www.gnu.org/licenses/lgpl-3.0.html

## In-app WireGuard tunnel (`libgojni.so`)

- **Files:** `libgojni.so` (arm64-v8a, x86_64), built from `wgbridge/` by `wgbridge/build.sh` and packaged from the local Maven repo, not from `jniLibs/`
- **Statically linked Go modules:**
  - wireguard-go (`golang.zx2c4.com/wireguard`): MIT, Copyright (C) 2017-2025 WireGuard LLC. https://git.zx2c4.com/wireguard-go
  - gVisor (`gvisor.dev/gvisor`): Apache-2.0, Copyright The gVisor Authors. https://github.com/google/gvisor
  - `github.com/google/btree`: Apache-2.0, Copyright Google Inc.
  - `golang.org/x/crypto`, `golang.org/x/net`, `golang.org/x/sys`, `golang.org/x/time`, `golang.org/x/mobile` (gomobile runtime): BSD-3-Clause, Copyright The Go Authors
  - Go runtime and standard library: BSD-3-Clause, Copyright The Go Authors

"WireGuard" is a registered trademark of Jason A. Donenfeld. Lefty is not affiliated with or endorsed by the WireGuard project.

