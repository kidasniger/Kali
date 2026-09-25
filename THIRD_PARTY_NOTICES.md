# Third-party notices

## PRoot Android backend

Kali VNC v1.7 builds the ARM64 PRoot backend from:

https://github.com/oonid/pr/tree/fcf25cb2396361f0be2edfc96fdd61a6e738c9d9

Pinned commit:
`fcf25cb2396361f0be2edfc96fdd61a6e738c9d9`

The PRoot implementation is GPL-2.0-or-later. The upstream project documents
that its `src/proot/` component derives from upstream PRoot and the Termux
PRoot Android fork.

The APK contains the resulting statically linked ARM64 PRoot executable
(`libproot.so`) and its dedicated loader (`libproot-loader.so`), installed
by Android in `nativeLibraryDir`.

## Historical proroot 1.2.8 backend

The former proprietary proroot 1.2.8 backend was removed from v1.7 and is no
longer used.
